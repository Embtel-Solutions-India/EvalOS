package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.FieldOwnership;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.OpportunityNote;
import com.ie.evalos.domain.OpportunityNoteGhlLink;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.SyncEntity;
import com.ie.evalos.domain.SyncOutboxEntry;
import com.ie.evalos.integration.GhlFailure;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.integration.GhlWriteClient;
import com.ie.evalos.repository.GhlNoteRepository;
import com.ie.evalos.repository.OpportunityNoteGhlLinkRepository;
import com.ie.evalos.repository.OpportunityNoteRepository;
import com.ie.evalos.repository.OpportunityRepository;
import com.ie.evalos.repository.PipelineRepository;
import com.ie.evalos.repository.SyncOutboxRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The durable EvalOS→GHL push — Unit 45, slice C.
 *
 * <p>{@code 00c} §4d, and the chain of reasoning is short: no mismatch requires that a failed push
 * is retried; a retry is only safe if it cannot double-apply; and <strong>invariant 2 said "writes
 * do not retry" precisely because EvalOS had no key scheme.</strong> Unit 44d's correlation key is
 * that scheme, which is why this could not be built before it.
 *
 * <p><strong>What it queues today, and what it does not.</strong> The desks still write to GHL
 * synchronously and read the answer back — a salesperson needs the created id and GHL's
 * {@code isNew}, and taking that away is Unit 46's job, not this one's. What this drains is the two
 * writes that were previously <em>swallowed and lost</em>: the client portal's opportunity create
 * when GHL is unreachable, and its "request submitted" marker. Both carried a {@code ponytail:} note
 * naming this slice as their fix.
 *
 * <p><strong>The retry-after-timeout is the dangerous case and is handled by name.</strong> A create
 * that did not answer leaves EvalOS unable to tell "GHL never got it" from "GHL got it and the reply
 * was lost" — {@code 00d} §6.1's duplicate. Before creating, this asks GHL for the contact's
 * opportunities and looks for its own correlation key; finding it means the create already landed,
 * and the row is linked rather than made twice.
 */
@Service
public class SyncOutboxService {

	private static final Logger log = LoggerFactory.getLogger(SyncOutboxService.class);

	/**
	 * How many pending rows one drain may send.
	 *
	 * <p>The pacer serialises them anyway, so this is not about throughput — it is about a sweep that
	 * runs for ten minutes being a sweep the next tick overlaps, and about one backlog not spending
	 * the whole shared GHL budget in a single pass.
	 */
	private static final int BATCH = 25;

	/**
	 * Attempts before a row is dead-lettered.
	 *
	 * <p>Five, and the number matters less than the fact there is one: a row retried forever is a row
	 * that spends budget forever and never reaches anybody's attention. `00d` §6.3's classification
	 * already stops the two failures that should not be retried at all, so this only bounds the
	 * genuinely-transient ones that stay broken.
	 */
	private static final int MAX_ATTEMPTS = 5;

	private final SyncOutboxRepository outbox;
	private final OpportunityRepository opportunities;
	private final PipelineRepository pipelines;
	private final GhlWriteClient ghl;
	private final GhlPipelineClient ghlReads;
	private final SellingBrand sellingBrand;
	private final String correlationFieldId;

	/** Absent only in hand-built tests, which keep the value they were given. */
	private com.ie.evalos.config.AppSettings settings;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	void useSettings(com.ie.evalos.config.AppSettings settings) {
		this.settings = settings;
	}

	/** Links a client account's GHL contact for a sign-in that queued it. Absent only in hand-built tests. */
	private ClientAccountService clientAccounts;

	@org.springframework.beans.factory.annotation.Autowired
	void useClientAccounts(ClientAccountService clientAccounts) {
		this.clientAccounts = clientAccounts;
	}

	/** The Administrator's value from Settings when saved (D83), else the configured one. */
	private String correlationFieldId() {
		return com.ie.evalos.config.AppSettings.or(settings, com.ie.evalos.config.Setting.GHL_CORRELATION_FIELD, this.correlationFieldId);
	}

	private final OpportunityNoteRepository notes;
	private final OpportunityNoteGhlLinkRepository noteLinks;
	private final TeamMemberRepository teamMembers;
	private final GhlNoteRepository ghlNotes;

	SyncOutboxService(SyncOutboxRepository outbox, OpportunityRepository opportunities,
			PipelineRepository pipelines, GhlWriteClient ghl, GhlPipelineClient ghlReads,
			SellingBrand sellingBrand,
			@Value("${evalos.ghl.opportunity-correlation-field:}") String correlationFieldId,
			OpportunityNoteRepository notes, OpportunityNoteGhlLinkRepository noteLinks,
			TeamMemberRepository teamMembers, GhlNoteRepository ghlNotes) {
		this.ghlNotes = ghlNotes;
		this.notes = notes;
		this.noteLinks = noteLinks;
		this.teamMembers = teamMembers;
		this.outbox = outbox;
		this.opportunities = opportunities;
		this.pipelines = pipelines;
		this.ghl = ghl;
		this.ghlReads = ghlReads;
		this.sellingBrand = sellingBrand;
		this.correlationFieldId = correlationFieldId == null ? "" : correlationFieldId.trim();
	}

	/** What one drain did, for the sweep ledger and the status surface. */
	public record DrainResult(int attempted, int sent, int retrying, int dead, boolean halted) {

		public int changed() {
			return sent + dead;
		}
	}

	/**
	 * Queues a push, collapsing onto one that is already pending.
	 *
	 * <p><strong>The collapse is the dedupe key doing its job</strong> ({@code 00d} §6.3): three
	 * edits to one opportunity in a minute become one row and one send, because the sender reads the
	 * current row rather than a queued payload. It is a literal {@code ON CONFLICT DO NOTHING} (see
	 * {@code SyncOutboxRepository.enqueueIfAbsent}): "already queued" is success, not an error.
	 *
	 * <p>{@code REQUIRES_NEW} so that queueing survives the caller's transaction rolling back. A push
	 * that was lost because the request that asked for it failed afterwards is precisely the class of
	 * loss this table exists to stop.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void enqueue(UUID brandId, UUID opportunityId, SyncOutboxEntry.Intent intent) {
		enqueue(brandId, SyncEntity.OPPORTUNITY, opportunityId, intent);
	}

	/** The same, for any outbox entity — an opportunity note is the second (Unit 54). */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void enqueue(UUID brandId, SyncEntity entityType, UUID entityId, SyncOutboxEntry.Intent intent) {
		if (outbox.enqueueIfAbsent(brandId, entityType.name(), entityId, intent.name()) == 0) {
			log.debug("{} for {} {} is already pending; collapsed", intent, entityType, entityId);
		}
	}

	/** D56: an opportunity with a push still waiting — a new lead must not overwrite it. */
	@Transactional(readOnly = true)
	public boolean isPending(UUID brandId, UUID opportunityId) {
		return outbox.existsByBrandIdAndEntityTypeAndEntityIdAndSentAtIsNullAndDeadAtIsNull(brandId,
				SyncEntity.OPPORTUNITY, opportunityId);
	}

	/**
	 * Sends what is pending, oldest first.
	 *
	 * <p><strong>The stop conditions are {@code 00d} §6.3's, by name.</strong> A {@code 429} pauses
	 * the <em>whole</em> drain — the budget belongs to the GHL location, so backing off one row while
	 * the next fires immediately is not a back-off. A {@code 401}/{@code 403} halts it and says so —
	 * "retrying a scope failure ten times across every pending row spends the entire budget on a
	 * credential problem", and nothing in EvalOS can fix a missing grant.
	 *
	 * <p>Each row is sent outside a transaction and its outcome written in its own, so one bad row
	 * cannot roll back the sends that already succeeded.
	 */
	public DrainResult drain() {
		if (sellingBrand.id() == null) {
			return new DrainResult(0, 0, 0, 0, false);
		}
		// Paused by the Administrator (D83): touch no row. Counting the pause as an attempt would spend the first
		// row's retry budget for nothing, and one ordinary failure after resuming would then dead-letter it.
		if (paused()) {
			return new DrainResult(0, 0, 0, 0, true);
		}
		recoverDead();
		sending.lock();
		try {
			List<SyncOutboxEntry> pending = outbox
					.findByBrandIdAndSentAtIsNullAndDeadAtIsNullOrderByQueuedAtAsc(sellingBrand.id(),
							Limit.of(BATCH));

			int sent = 0;
			int retrying = 0;
			int dead = 0;
			int attempted = 0;
			for (SyncOutboxEntry entry : pending) {
				attempted++;
				switch (send(entry)) {
					case SENT -> sent++;
					case RETRYING -> retrying++;
					case DEAD -> dead++;
					// Paused between the check above and this row: left exactly as it was.
					case PAUSED -> {
						return new DrainResult(attempted - 1, sent, retrying, dead, true);
					}
					case HALTED -> {
						return new DrainResult(attempted, sent, retrying + 1, dead, true);
					}
				}
			}
			return new DrainResult(attempted, sent, retrying, dead, false);
		}
		finally {
			sending.unlock();
		}
	}

	/**
	 * Sends one deal's pending pushes now, in the request that queued them — a <strong>won deal</strong>, so
	 * GHL's {@code opportunity.won} (and the case behind it) does not wait for the next drain.
	 *
	 * <p>Called after the caller's transaction commits, because the push reads the row at send time. A
	 * failure leaves the row exactly where the drain would, under the same retry and dead-letter rules, so
	 * nothing is lost by trying early. Never throws.
	 *
	 * <p>ponytail: skipped while a drain holds the lock (the drain sends it anyway), so the two never push one
	 * row twice in this JVM. A second backend instance would need a row lock ({@code FOR UPDATE SKIP LOCKED}).
	 */
	public void sendNow(UUID brandId, UUID opportunityId) {
		if (paused() || !sending.tryLock()) {
			return;
		}
		try {
			for (SyncOutboxEntry entry : outbox
					.findByBrandIdAndEntityTypeAndEntityIdAndSentAtIsNullAndDeadAtIsNullOrderByQueuedAtAsc(brandId,
							SyncEntity.OPPORTUNITY, opportunityId)) {
				Sent outcome = send(entry);
				if (outcome == Sent.PAUSED || outcome == Sent.HALTED) {
					return;
				}
			}
		}
		catch (RuntimeException unexpected) {
			log.warn("Immediate push for {} failed; the drain sends it: {}", opportunityId, unexpected.toString());
		}
		finally {
			sending.unlock();
		}
	}

	private boolean paused() {
		return settings != null && !settings.enabled(com.ie.evalos.config.Setting.GHL_WRITES_ENABLED);
	}

	/** The drain and {@link #sendNow} never send at the same time, so one row is never pushed twice. */
	private final java.util.concurrent.locks.ReentrantLock sending = new java.util.concurrent.locks.ReentrantLock();

	private enum Sent {
		SENT, RETRYING, DEAD, PAUSED, HALTED
	}

	/** One row: push it and record the outcome. */
	private Sent send(SyncOutboxEntry entry) {
		try {
			boolean editedUnderUs = push(entry);
			record(entry, Outcome.SENT, null, null);
			if (editedUnderUs) {
				// After SENT, never before: the pending-row constraint is what collapses
				// duplicates, so queueing while this row is still pending would be swallowed
				// as "already queued" and the newer edit would never travel.
				enqueue(entry.getBrandId(), entry.getEntityType(), entry.getEntityId(), entry.getIntent());
			}
			return Sent.SENT;
		}
		catch (GhlUnavailableException refused) {
			if (refused.failure() == GhlFailure.PAUSED) {
				return Sent.PAUSED;
			}
			if (refused.failure().stopsEverything()) {
				// Not this row's fault and not this row's problem: every other pending push
				// would fail identically. Left pending, and the drain stops here.
				record(entry, Outcome.RETRY, refused.failure(), refused.getMessage());
				log.error("Outbox halted on {}: {}", refused.failure(), refused.getMessage());
				return Sent.HALTED;
			}
			if (!refused.isRetriable() || entry.getAttempts() + 1 >= MAX_ATTEMPTS) {
				record(entry, Outcome.DEAD, refused.failure(), refused.getMessage());
				return Sent.DEAD;
			}
			record(entry, Outcome.RETRY, refused.failure(), refused.getMessage());
			return Sent.RETRYING;
		}
		catch (RuntimeException ourBug) {
			// An EvalOS defect, not an upstream one. Dead-lettered rather than retried: a
			// NullPointerException does not become true on the fourth attempt, and looping on
			// it would hide it behind a queue that never drains.
			record(entry, Outcome.DEAD, GhlFailure.REFUSED, ourBug.toString());
			log.error("Outbox row {} failed inside EvalOS and was dead-lettered", entry.getId(), ourBug);
			return Sent.DEAD;
		}
	}

	private enum Outcome {
		SENT, RETRY, DEAD
	}

	@Transactional
	void record(SyncOutboxEntry entry, Outcome outcome, GhlFailure failure, String message) {
		SyncOutboxEntry row = outbox.findById(entry.getId()).orElse(entry);
		switch (outcome) {
			case SENT -> row.delivered();
			case RETRY -> row.failedRetriably(failure, message);
			case DEAD -> row.dead(failure, message);
		}
		outbox.save(row);
	}

	/**
	 * Makes GHL agree with the EvalOS row.
	 *
	 * <p><strong>The row is read here, at send time</strong>, which is the whole reason the queue
	 * stores an id rather than a payload ({@code 00d} §6.3).
	 */
	private boolean push(SyncOutboxEntry entry) {
		if (entry.getEntityType() == SyncEntity.OPPORTUNITY_NOTE) {
			return pushNote(entry);
		}
		if (entry.getEntityType() == SyncEntity.CLIENT_ACCOUNT) {
			// Idempotent on the account's link, so a retry after a lost reply is a no-op once it lands.
			clientAccounts.linkCrmIdentity(entry.getBrandId(), entry.getEntityId());
			return false;
		}
		Opportunity row = opportunities.findById(entry.getEntityId())
				.orElseThrow(() -> new IllegalStateException(
						"Outbox names opportunity " + entry.getEntityId() + ", which no longer exists"));

		if (row.getGhlId() == null) {
			create(row);
			return false;
		}
		// Read before the GHL call, because the clear afterwards is conditional on it not having
		// moved. Null means the edit has already been confirmed by something else, most likely a
		// sync that arrived with GHL's own answer.
		Instant edit = row.getLocalUpdatedAt();
		// Spec 86 §2: read GHL's copy first, so a value someone set there since the edit is not overwritten.
		GhlPipelineClient.Opportunity inGhl = ghlCopyOf(row);
		switch (entry.getIntent()) {
			case UPSERT -> upsert(row, inGhl);
			case CLOSE -> {
				String status = row.getStatus() == null ? "won" : row.getStatus();
				// Agree-check only (spec 86 §2): a won close is never discarded by timestamp — it opens the case.
				if (inGhl == null || !status.equalsIgnoreCase(inGhl.status())) {
					ghl.setStatus(row.getGhlId(), ghlPipelineOf(row), status);
				}
			}
			case DELETE -> throw new IllegalStateException("DELETE is not implemented; no caller queues it");
		}
		if (edit == null) {
			return false;
		}
		// **Conditional, and a zero here is a real outcome rather than an error.** The row may have
		// been edited again while this push was inside GHL; clearing the stamp then would leave 45e
		// no longer defending an edit that GHL has never been told about. See
		// OpportunityRepository.confirmPushed. The drain re-queues instead.
		if (opportunities.confirmPushed(row.getId(), edit) == 0) {
			log.info("Opportunity {} was edited again while its push was in flight; re-queueing",
					row.getId());
			return true;
		}
		return false;
	}

	/**
	 * Sends the shared fields a desk actually edited, and <strong>only</strong> those.
	 *
	 * <p><strong>Sending all four made a rename undo a GHL automation.</strong> The mirror's stage
	 * is up to one {@code MIRROR_DELTA} behind, so a workflow that moved the card inside that window
	 * had its move overwritten by the stage the mirror still held — and then read back as truth on
	 * the next sweep, permanently. {@link FieldOwnership} calls the stage {@code SHARED} precisely
	 * because GHL writes it too, which is what makes sending it unasked wrong.
	 *
	 * <p>{@code updateOpportunity} omits a null from the body, so an unedited field is left alone in
	 * GHL rather than cleared — the same "null means leave it" contract
	 * {@link Opportunity#editedLocally} is built on.
	 *
	 * <p><strong>A row queued before V63 carries no field list</strong>, and gets the name and the
	 * amount but never the stage: those two only ever change because a desk changed them, so
	 * re-sending them is at worst a no-op, while the stage is the one that does damage.
	 */
	private void upsert(Opportunity row, GhlPipelineClient.Opportunity inGhl) {
		java.util.Set<String> edited = row.locallyEdited();
		boolean legacy = edited.isEmpty() && row.getLocalUpdatedAt() != null;
		String name = legacy || edited.contains(FieldOwnership.NAME) ? row.getName() : null;
		java.math.BigDecimal amount = legacy || edited.contains(FieldOwnership.AMOUNT)
				? row.getAmount() : null;
		String stage = edited.contains(FieldOwnership.STAGE) ? row.getGhlStageId() : null;

		// Conflicts are audited only once the push has landed: a GHL failure below retries, and the retry
		// decides the same conflict again — recorded then, once, rather than once per attempt.
		List<java.util.Map<String, Object>> conflicts = new java.util.ArrayList<>();
		if (inGhl != null) {
			name = merged(row, inGhl, FieldOwnership.NAME, name, inGhl.name(), conflicts) ? name : null;
			amount = merged(row, inGhl, FieldOwnership.AMOUNT, amount == null ? null : amount.toPlainString(),
					inGhl.monetaryValue() == null ? null : inGhl.monetaryValue().toPlainString(), conflicts)
							? amount : null;
			stage = merged(row, inGhl, FieldOwnership.STAGE, stage, inGhl.pipelineStageId(), conflicts) ? stage : null;
		}

		if (name == null && amount == null && stage == null) {
			// Nothing outstanding to say. GHL answers 422 to an empty body, and "they already
			// agree" is success rather than a failure worth retrying.
			log.info("Outbox UPSERT for opportunity {} has no unconfirmed edit left to send", row.getId());
		}
		else {
			ghl.updateOpportunity(row.getGhlId(), ghlPipelineOf(row), name, amount, stage);
		}
		if (audit != null) {
			conflicts.forEach((after) -> audit.recordSystemEvent(row.getBrandId(), "GHL_OPPORTUNITY", row.getId(),
					com.ie.evalos.domain.AuditAction.UPDATED, null, after));
		}
	}

	/**
	 * GHL's current copy of this deal, or null when it cannot be told apart (no contact, or GHL does not
	 * return it — the push then goes out as before and GHL's own answer decides). Read through the
	 * opportunity search by contact, which the mirror already relies on and which carries {@code updatedAt}.
	 */
	private GhlPipelineClient.Opportunity ghlCopyOf(Opportunity row) {
		if (row.getGhlContactId() == null || row.getGhlContactId().isBlank()) {
			return null;
		}
		return ghlReads.forContact(row.getGhlContactId()).stream()
				.filter((candidate) -> row.getGhlId().equals(candidate.id()))
				.findFirst().orElse(null);
	}

	/**
	 * Whether EvalOS's value for one edited field should be sent — the three-way rule of spec 86 §2.
	 *
	 * <ul>
	 * <li>GHL already holds EvalOS's value: nothing to send.</li>
	 * <li>GHL still holds the value the field had before the edit (the base): GHL did not touch it; send.</li>
	 * <li>Otherwise both changed it. The newer edit wins — GHL's {@code updatedAt} against EvalOS's
	 * {@code local_updated_at}. When GHL wins, the field is left out of the push; once the push is
	 * confirmed EvalOS stops defending it and the next mirror read brings GHL's value in.</li>
	 * </ul>
	 */
	private boolean merged(Opportunity row, GhlPipelineClient.Opportunity inGhl, String field, String mine,
			String theirs, List<java.util.Map<String, Object>> conflicts) {
		if (mine == null) {
			return false;
		}
		if (sameValue(field, mine, theirs)) {
			return false;
		}
		String base = row.ghlBaseOf(field);
		if (row.hasGhlBase(field) && sameValue(field, base, theirs)) {
			return true;
		}
		boolean ghlNewer = inGhl.updatedAt() != null && row.getLocalUpdatedAt() != null
				&& inGhl.updatedAt().isAfter(row.getLocalUpdatedAt());
		String winner = ghlNewer ? "GHL" : "EVALOS";
		log.info("Sync conflict on opportunity {} field {}: EvalOS '{}', GHL '{}', base '{}' — {} wins",
				row.getId(), field, mine, theirs, base, winner);
		java.util.Map<String, Object> after = new java.util.LinkedHashMap<>();
		after.put("conflict", field);
		after.put("evalos", mine);
		after.put("ghl", String.valueOf(theirs));
		after.put("base", String.valueOf(base));
		after.put("winner", winner);
		conflicts.add(after);
		return !ghlNewer;
	}

	/**
	 * Money compares by value (150 = 150.00), and an unset amount is 0 — GHL reports a deal without a
	 * value either way. Text and ids compare by their trimmed form.
	 */
	private static boolean sameValue(String field, String a, String b) {
		if (FieldOwnership.AMOUNT.equals(field)) {
			try {
				return new java.math.BigDecimal(a == null ? "0" : a.trim())
						.compareTo(new java.math.BigDecimal(b == null ? "0" : b.trim())) == 0;
			}
			catch (NumberFormatException notMoney) {
				// fall through to the text comparison
			}
		}
		if (a == null || b == null) {
			return a == null && b == null;
		}
		return a.trim().equals(b.trim());
	}

	/**
	 * Creates the opportunity in GHL — <strong>after asking whether it is already there</strong>.
	 *
	 * <p>This is {@code 00d} §6.1's fix, in the only form GHL's API allows. A create that timed out
	 * leaves EvalOS unable to tell "GHL never got it" from "GHL got it and the reply was lost", and
	 * at-least-once delivery over a non-idempotent create is how one opportunity becomes two. So:
	 * look for the correlation key on the contact's own opportunities first, and if it is there, the
	 * create already landed and this row just needs linking.
	 *
	 * <p><strong>Without a configured correlation field there is nothing to look for</strong>, and
	 * the create goes out unguarded — the duplicate exposure that exists today rather than a new one.
	 * The alternative, refusing to retry at all, loses the client's request outright.
	 */
	private void create(Opportunity row) {
		Optional<String> already = alreadyCreated(row);
		if (already.isPresent()) {
			log.info("Opportunity {} was already created in GHL as {}; linking rather than duplicating",
					row.getId(), already.get());
			row.linkGhl(already.get());
			opportunities.save(row);
			return;
		}

		java.util.Map<String, String> fields = correlationFieldId().isEmpty()
				? null
				: java.util.Map.of(correlationFieldId(), row.getId().toString());
		GhlWriteClient.UpsertedOpportunity created = ghl.createOpportunity(ghlPipelineOf(row),
				row.getGhlContactId(), row.getName(), row.getAmount(), null, null, fields, null);
		row.linkGhl(created.id());
		opportunities.save(row);
	}

	/** The contact's opportunities, matched on the correlation key EvalOS wrote when it created. */
	private Optional<String> alreadyCreated(Opportunity row) {
		if (correlationFieldId().isEmpty() || row.getGhlContactId() == null) {
			return Optional.empty();
		}
		String key = row.getId().toString();
		return ghlReads.forContact(row.getGhlContactId()).stream()
				.filter((candidate) -> key.equals(candidate.id()) || carries(candidate, key))
				.map(GhlPipelineClient.Opportunity::id)
				.findFirst();
	}

	/**
	 * Whether GHL's row carries EvalOS's correlation key.
	 *
	 * <p><strong>Matched against the whole row's text, deliberately.</strong> The search projection
	 * binds nine fields and not {@code customFields} — that narrowing is what keeps marketing PII out
	 * of EvalOS responses, and widening it here would undo that for every caller. A UUID is specific
	 * enough that a false positive would require GHL to be echoing EvalOS's own id somewhere else,
	 * which would mean the create landed anyway.
	 *
	 * <p>ponytail: a string scan over a handful of rows, on a path that runs only after a timeout.
	 * Bind {@code customFields} on a dedicated projection if this ever runs anywhere hot.
	 */
	private static boolean carries(GhlPipelineClient.Opportunity candidate, String key) {
		return candidate.toString().contains(key);
	}

	private String ghlPipelineOf(Opportunity row) {
		return pipelines.findById(row.getPipelineId()).map(Pipeline::getGhlId)
				.orElseThrow(() -> new IllegalStateException(
						"Opportunity " + row.getId() + " names pipeline " + row.getPipelineId()
								+ ", which is not mirrored"));
	}

	/**
	 * Sends one EvalOS note to its deal's GHL contact (Unit 54 §1) — created once, then overwritten on
	 * each edit and deleted on delete (Unit 54a).
	 *
	 * <p><strong>The contact, because a GHL note has nowhere else to live</strong> — and so the
	 * title names the deal: a repeat client's contact carries notes from every deal they have had.
	 *
	 * <p><strong>Waiting is a retry, not a failure.</strong> A note on a deal the mirror has not
	 * linked to GHL, or whose contact is not known yet, is recorded RETRY; the deal's own push, or the
	 * next sweep, supplies what is missing. Five ticks later it is dead-lettered where the status
	 * surface shows it, which is the right outcome for a deal that never reaches GHL.
	 *
	 * <p><strong>No double posts.</strong> GHL has no upsert for notes, so a retry first reads the
	 * contact's notes for this note's reference and links the one it finds. A first attempt skips
	 * that read: nothing can have landed yet.
	 */
	private boolean pushNote(SyncOutboxEntry entry) {
		if (entry.getIntent() == SyncOutboxEntry.Intent.DELETE) {
			deleteNote(entry);
			return false;
		}
		// Missing means deleted (Unit 54a): a note is enqueued only after its transaction commits,
		// so it cannot be "not visible yet". Its DELETE row, queued behind this one, does the rest.
		OpportunityNote note = notes.findById(entry.getEntityId()).orElse(null);
		if (note == null) {
			return false;
		}
		java.util.Optional<OpportunityNoteGhlLink> link = noteLinks.findById(note.getId())
				.filter((existing) -> existing.getGhlNoteId() != null);
		if (link.isPresent() && com.ie.evalos.integration.StubGhlWriteClient.isStubId(link.get().getGhlNoteId())) {
			// A fake id from the stub's first version: there is no GHL note to edit.
			return false;
		}
		Opportunity deal = opportunities.findByBrandIdAndGhlId(note.getBrandId(), note.getGhlOpportunityId())
				.orElse(null);
		if (deal == null || deal.getGhlContactId() == null) {
			throw new GhlUnavailableException(
					"Note " + note.getId() + " waits for its deal and contact to reach GHL", null,
					GhlFailure.NO_ANSWER, null);
		}

		String reference = noteReference(note.getId());
		String title = "EvalOS · " + (deal.getName() == null ? "deal" : deal.getName());
		if (link.isPresent()) {
			// Already in GHL, so this is an edit: overwrite it there too. PUT is idempotent, so a
			// retry after a timeout is harmless.
			OpportunityNoteGhlLink linked = link.get();
			ghl.updateContactNote(linked.getGhlContactId() != null ? linked.getGhlContactId() : deal.getGhlContactId(),
					linked.getGhlNoteId(), title, noteBody(note, reference));
			return editedSince(note);
		}
		String ghlNoteId = entry.getAttempts() == 0 ? null
				: ghlReads.notesOnContact(deal.getGhlContactId()).stream()
						.filter((existing) -> existing.body() != null && existing.body().contains(reference))
						.map(GhlPipelineClient.Note::id)
						.findFirst().orElse(null);
		if (ghlNoteId == null) {
			ghlNoteId = ghl.addContactNote(deal.getGhlContactId(), deal.getGhlId(), title,
					noteBody(note, reference));
		}
		else {
			// Found by reference after an ambiguous attempt: what landed is the text of that attempt,
			// and an edit since then collapsed onto this row. Send the current text over it.
			ghl.updateContactNote(deal.getGhlContactId(), ghlNoteId, title, noteBody(note, reference));
		}
		if (ghlNoteId == null) {
			// Only the local stub answers null ("not sent"). Nothing is linked: this note has not
			// reached GHL, and a link says it has.
			log.info("Note {} was not sent (GHL writes are stubbed); left unlinked", note.getId());
			return false;
		}
		noteLinks.save(new OpportunityNoteGhlLink(note.getId(), note.getBrandId(), ghlNoteId,
				deal.getGhlContactId()));
		return editedSince(note);
	}

	/**
	 * Whether the note was edited while its push was inside GHL — the drain then re-queues it.
	 *
	 * <p><strong>Without this an edit in that window was lost.</strong> The edit's enqueue collapses
	 * onto the row being sent (it is still pending until the drain records it), so the newer text
	 * never travelled and GHL kept the old one. It is the note-side twin of the opportunity path's
	 * {@code confirmPushed} returning zero. A note deleted meanwhile is not "edited": its DELETE row
	 * is queued separately and does the rest.
	 */
	private boolean editedSince(OpportunityNote sent) {
		return notes.findById(sent.getId())
				.map((now) -> !java.util.Objects.equals(now.getUpdatedAt(), sent.getUpdatedAt()))
				.orElse(false);
	}

	/**
	 * Deletes the GHL copy of a note its author deleted (Unit 54a).
	 *
	 * <p>The EvalOS row is already gone, which is why the link carries the contact. No link means the
	 * note never reached GHL, so there is nothing to delete. GHL's 404 is success — gone is what was
	 * asked for. The link is dropped only after GHL confirms, so until then it keeps hiding the echo.
	 */
	private void deleteNote(SyncOutboxEntry entry) {
		OpportunityNoteGhlLink link = noteLinks.findById(entry.getEntityId()).orElse(null);
		if (link == null) {
			return;
		}
		if (com.ie.evalos.integration.StubGhlWriteClient.isStubId(link.getGhlNoteId())) {
			noteLinks.delete(link);
			return;
		}
		if (link.getGhlContactId() == null) {
			// A deal with no known contact never had a note pushed to it, so there is nothing to delete.
			noteLinks.delete(link);
			return;
		}
		// A delete marker (V68): the push never learned the GHL id, so find what landed by its reference.
		List<String> ghlIds = link.getGhlNoteId() != null ? List.of(link.getGhlNoteId())
				: ghlReads.notesOnContact(link.getGhlContactId()).stream()
						.filter((existing) -> existing.body() != null
								&& existing.body().contains(noteReference(link.getNoteId())))
						.map(GhlPipelineClient.Note::id).toList();
		for (String ghlId : ghlIds) {
			try {
				ghl.deleteContactNote(link.getGhlContactId(), ghlId);
			}
			catch (GhlUnavailableException refused) {
				if (!Integer.valueOf(404).equals(refused.status())) {
					throw refused;
				}
			}
			// Stamped here, with the link dropped below: until the next sweep the mirrored copy would
			// otherwise be live with nothing hiding it, and the deleted note would reappear as GHL's.
			ghlNotes.findByBrandIdAndGhlId(link.getBrandId(), ghlId).ifPresent((mirrored) -> {
				mirrored.markMissing(Instant.now());
				ghlNotes.save(mirrored);
			});
		}
		noteLinks.delete(link);
	}

	/** The body GHL gets: the note, then who wrote it and the reference a retry looks for. */
	private String noteBody(OpportunityNote note, String reference) {
		String author = teamMembers.findById(note.getAuthorId())
				.map(com.ie.evalos.domain.TeamMember::getDisplayName).orElse("EvalOS staff");
		return note.getBody() + "\n\n— " + author + ", in EvalOS · " + reference;
	}

	/** A short, unique tag on the pushed body. The note id is a UUID, so eight hex digits suffice. */
	static String noteReference(UUID noteId) {
		return "#" + noteId.toString().substring(0, 8);
	}

	/**
	 * How far back the status surface lists failed pushes (2026-10-28). Older dead rows stay in the
	 * table as history; they stop being shown, so a failure that was dealt with does not sit on the
	 * page for ever.
	 */
	static final java.time.Duration RECENT_FAILURES = java.time.Duration.ofDays(7);

	/** How much is waiting and how much recently never arrived — the status surface's numbers. */
	@Transactional(readOnly = true)
	public Backlog backlog() {
		if (sellingBrand.id() == null) {
			return new Backlog(0, 0, null, List.of());
		}
		List<SyncOutboxEntry> oldest = outbox
				.findByBrandIdAndSentAtIsNullAndDeadAtIsNullOrderByQueuedAtAsc(sellingBrand.id(), Limit.of(1));
		Instant since = Instant.now().minus(RECENT_FAILURES);
		return new Backlog(
				(int) outbox.countByBrandIdAndSentAtIsNullAndDeadAtIsNull(sellingBrand.id()),
				(int) outbox.countByBrandIdAndDeadAtAfter(sellingBrand.id(), since),
				oldest.isEmpty() ? null : oldest.getFirst().getQueuedAt(),
				outbox.findByBrandIdAndDeadAtAfterOrderByDeadAtDesc(sellingBrand.id(), since, Limit.of(20)));
	}

	/** Spec 86 §3: a dead push waits this long before the drain re-queues it on its own. */
	static final java.time.Duration RECOVERY_DELAY = java.time.Duration.ofHours(1);

	/** Spec 86 §3: after this many deaths of one push in {@link #RECENT_FAILURES}, it is left for a person. */
	static final int MAX_AUTOMATIC_DEATHS = 3;

	/**
	 * Re-queues dead pushes a later attempt can fix, with nobody pressing anything (spec 86 §3).
	 *
	 * <p>Only failures that are about timing — no answer, GHL's 5xx, the rate limit — and
	 * {@code EMPTY_RESPONSE}, which is safe to repeat now that every push reads GHL's copy first and
	 * sends nothing GHL already holds. A {@code REFUSED} push (a 4xx, or an EvalOS bug) is left for a person:
	 * the same request fails the same way. A push that has died {@link #MAX_AUTOMATIC_DEATHS} times this week
	 * is left too, so a broken one cannot loop.
	 *
	 * <p>No transaction of its own: {@link #drain} calls it on this object, past the proxy. Each stamp and
	 * each enqueue commits by itself, so a failure part-way leaves every row either untouched or re-queued.
	 */
	public int recoverDead() {
		if (sellingBrand.id() == null) {
			return 0;
		}
		Instant now = Instant.now();
		Instant since = now.minus(RECENT_FAILURES);
		int requeued = 0;
		for (SyncOutboxEntry dead : outbox.findByBrandIdAndLastFailureInAndDeadAtBetweenAndRetriedAtIsNullOrderByDeadAtAsc(
				sellingBrand.id(), RECOVERABLE, since, now.minus(RECOVERY_DELAY))) {
			if (outbox.countByBrandIdAndEntityTypeAndEntityIdAndIntentAndDeadAtAfter(dead.getBrandId(),
							dead.getEntityType(), dead.getEntityId(), dead.getIntent(), since) >= MAX_AUTOMATIC_DEATHS) {
				continue;
			}
			dead.retried(null);
			outbox.save(dead);
			outbox.enqueueIfAbsent(dead.getBrandId(), dead.getEntityType().name(), dead.getEntityId(),
					dead.getIntent().name());
			requeued++;
		}
		if (requeued > 0) {
			log.info("Outbox re-queued {} failed push(es) automatically", requeued);
		}
		return requeued;
	}

	/** The failures a later attempt can fix (spec 86 §3); {@code REFUSED} is not one of them. */
	static final java.util.Set<GhlFailure> RECOVERABLE = java.util.EnumSet.of(GhlFailure.NO_ANSWER,
			GhlFailure.UPSTREAM_ERROR, GhlFailure.RATE_LIMITED, GhlFailure.EMPTY_RESPONSE);

	private AuditService audit;

	@org.springframework.beans.factory.annotation.Autowired
	void useAudit(AuditService audit) {
		this.audit = audit;
	}

	/**
	 * Re-queues a dead push — an Administrator's action from Sync health, once the cause is fixed.
	 *
	 * <p><strong>A new row, not the dead one revived.</strong> The dead row stays as the record of what
	 * failed and is stamped retried; the new row reads the entity's <em>current</em> state at send time
	 * and follows the ordinary retry rules, so a second failure shows up as its own row. If a push for
	 * the same entity is already pending, the queue collapses onto it — that one carries the same
	 * current state.
	 *
	 * <p>Scoped to the selling brand, the only brand whose outbox drains (Q24). An id outside it is the
	 * same "no such push" as an id that does not exist.
	 */
	@Transactional
	public void retry(UUID deadPushId, UUID by) {
		SyncOutboxEntry dead = outbox.findById(deadPushId)
				.filter((row) -> sellingBrand.id() != null && sellingBrand.id().equals(row.getBrandId()))
				.orElseThrow(() -> new com.ie.evalos.common.NotFoundException("No such failed push"));
		try {
			dead.retried(by);
		}
		catch (IllegalStateException refused) {
			throw new com.ie.evalos.common.InvalidRequestException(refused.getMessage());
		}
		outbox.save(dead);
		outbox.enqueueIfAbsent(dead.getBrandId(), dead.getEntityType().name(), dead.getEntityId(),
				dead.getIntent().name());
		if (audit != null) {
			audit.recordEvent("SYNC_OUTBOX", dead.getId(), com.ie.evalos.domain.AuditAction.UPDATED, by,
					java.util.Map.of("deadReason", String.valueOf(dead.getDeadReason())),
					java.util.Map.of("retried", true, "entityType", dead.getEntityType().name(),
							"entityId", dead.getEntityId().toString(), "intent", dead.getIntent().name()));
		}
	}

	/**
	 * @param oldestPending when the longest-waiting push was queued, or null when nothing is waiting.
	 *                      One minute of backlog is a drain doing its job; an hour of it is a drain
	 *                      that is not, and only the age says which
	 */
	public record Backlog(int pending, int dead, java.time.Instant oldestPending,
			List<SyncOutboxEntry> recentlyDead) {
	}

}
