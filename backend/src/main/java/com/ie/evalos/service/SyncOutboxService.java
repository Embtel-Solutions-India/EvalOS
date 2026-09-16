package com.ie.evalos.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.SyncEntity;
import com.ie.evalos.domain.SyncOutboxEntry;
import com.ie.evalos.integration.GhlFailure;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.integration.GhlWriteClient;
import com.ie.evalos.repository.OpportunityRepository;
import com.ie.evalos.repository.PipelineRepository;
import com.ie.evalos.repository.SyncOutboxRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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
	private final UUID sellingBrandId;
	private final String correlationFieldId;

	SyncOutboxService(SyncOutboxRepository outbox, OpportunityRepository opportunities,
			PipelineRepository pipelines, GhlWriteClient ghl, GhlPipelineClient ghlReads,
			@Value("${evalos.ghl.sales-brand:}") String salesBrandId,
			@Value("${evalos.ghl.opportunity-correlation-field:}") String correlationFieldId) {
		this.outbox = outbox;
		this.opportunities = opportunities;
		this.pipelines = pipelines;
		this.ghl = ghl;
		this.ghlReads = ghlReads;
		this.sellingBrandId = salesBrandId == null || salesBrandId.isBlank() ? null
				: UUID.fromString(salesBrandId);
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
	 * current row rather than a queued payload. {@code ON CONFLICT DO NOTHING} in effect — a
	 * constraint violation here means "already queued", which is success, not an error.
	 *
	 * <p>{@code REQUIRES_NEW} so that queueing survives the caller's transaction rolling back. A push
	 * that was lost because the request that asked for it failed afterwards is precisely the class of
	 * loss this table exists to stop.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void enqueue(UUID brandId, UUID opportunityId, SyncOutboxEntry.Intent intent) {
		try {
			outbox.saveAndFlush(
					new SyncOutboxEntry(brandId, SyncEntity.OPPORTUNITY, opportunityId, intent));
		}
		catch (DataIntegrityViolationException alreadyQueued) {
			log.debug("{} for opportunity {} is already pending; collapsed", intent, opportunityId);
		}
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
		if (sellingBrandId == null) {
			return new DrainResult(0, 0, 0, 0, false);
		}
		List<SyncOutboxEntry> pending = outbox
				.findByBrandIdAndSentAtIsNullAndDeadAtIsNullOrderByQueuedAtAsc(sellingBrandId,
						Limit.of(BATCH));

		int sent = 0;
		int retrying = 0;
		int dead = 0;
		int attempted = 0;
		for (SyncOutboxEntry entry : pending) {
			attempted++;
			try {
				push(entry);
				record(entry, Outcome.SENT, null, null);
				sent++;
			}
			catch (GhlUnavailableException refused) {
				if (refused.failure().stopsEverything()) {
					// Not this row's fault and not this row's problem: every other pending push
					// would fail identically. Left pending, and the drain stops here.
					record(entry, Outcome.RETRY, refused.failure(), refused.getMessage());
					log.error("Outbox halted on {}: {}", refused.failure(), refused.getMessage());
					return new DrainResult(attempted, sent, retrying + 1, dead, true);
				}
				if (!refused.isRetriable() || entry.getAttempts() + 1 >= MAX_ATTEMPTS) {
					record(entry, Outcome.DEAD, refused.failure(), refused.getMessage());
					dead++;
				}
				else {
					record(entry, Outcome.RETRY, refused.failure(), refused.getMessage());
					retrying++;
				}
			}
			catch (RuntimeException ourBug) {
				// An EvalOS defect, not an upstream one. Dead-lettered rather than retried: a
				// NullPointerException does not become true on the fourth attempt, and looping on
				// it would hide it behind a queue that never drains.
				record(entry, Outcome.DEAD, GhlFailure.REFUSED, ourBug.toString());
				log.error("Outbox row {} failed inside EvalOS and was dead-lettered", entry.getId(), ourBug);
				dead++;
			}
		}
		return new DrainResult(attempted, sent, retrying, dead, false);
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
	private void push(SyncOutboxEntry entry) {
		Opportunity row = opportunities.findById(entry.getEntityId())
				.orElseThrow(() -> new IllegalStateException(
						"Outbox names opportunity " + entry.getEntityId() + ", which no longer exists"));

		if (row.getGhlId() == null) {
			create(row);
			return;
		}
		switch (entry.getIntent()) {
			case UPSERT -> ghl.updateOpportunity(row.getGhlId(), ghlPipelineOf(row), row.getName(),
					row.getAmount(), null);
			case CLOSE -> ghl.setStatus(row.getGhlId(), ghlPipelineOf(row),
					row.getStatus() == null ? "won" : row.getStatus());
			case DELETE -> throw new IllegalStateException("DELETE is not implemented; no caller queues it");
		}
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

		java.util.Map<String, String> fields = correlationFieldId.isEmpty()
				? null
				: java.util.Map.of(correlationFieldId, row.getId().toString());
		GhlWriteClient.UpsertedOpportunity created = ghl.createOpportunity(ghlPipelineOf(row),
				row.getGhlContactId(), row.getName(), row.getAmount(), null, null, fields);
		row.linkGhl(created.id());
		opportunities.save(row);
	}

	/** The contact's opportunities, matched on the correlation key EvalOS wrote when it created. */
	private Optional<String> alreadyCreated(Opportunity row) {
		if (correlationFieldId.isEmpty() || row.getGhlContactId() == null) {
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

	/** How much is waiting and how much never arrived — the status surface's numbers. */
	@Transactional(readOnly = true)
	public Backlog backlog() {
		if (sellingBrandId == null) {
			return new Backlog(0, 0, null, List.of());
		}
		List<SyncOutboxEntry> oldest = outbox
				.findByBrandIdAndSentAtIsNullAndDeadAtIsNullOrderByQueuedAtAsc(sellingBrandId, Limit.of(1));
		return new Backlog(
				(int) outbox.countByBrandIdAndSentAtIsNullAndDeadAtIsNull(sellingBrandId),
				(int) outbox.countByBrandIdAndDeadAtIsNotNull(sellingBrandId),
				oldest.isEmpty() ? null : oldest.getFirst().getQueuedAt(),
				outbox.findByBrandIdAndDeadAtIsNotNullOrderByDeadAtDesc(sellingBrandId, Limit.of(20)));
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
