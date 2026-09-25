package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.GhlNote;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.repository.OpportunityRepository;
import com.ie.evalos.repository.PipelineRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the {@code opportunity} mirror in step with GHL — Unit 44, slice D.
 *
 * <p><strong>This replaces {@code OpportunityCache}, and the write path is the whole difference.</strong>
 * That class did {@code deleteByGhlPipelineId} then {@code saveAll}: every row's identity destroyed
 * on every board refresh. {@code 00d} §6.5 calls it the deepest of five reasons the cache cannot be
 * salvaged — nothing can hold a foreign key into it, and any row carrying local state dies, which
 * includes the portal-born row that has no {@code ghl_id} yet. Here every row is upserted by its
 * GHL id and a row GHL stops returning is stamped {@code missing_since}, never deleted.
 *
 * <p><strong>Refresh-on-read, not a sweep, and that is deliberate for this entity.</strong> The
 * pipeline mirror (44a) uses a sweep because only a full pass can notice an absence and pipelines
 * change a few times a year. Opportunities are the opposite: a salesperson drags a card and expects
 * the board to move, so the read path keeps the same two-minute staleness bound the cache had and
 * the behaviour a desk sees is unchanged. Unit 45's delta sweep is what adds the background pass;
 * it is not this slice's, and inventing it here would be the machinery `00c` §4 already owns.
 *
 * <p><strong>The GHL read happens outside any transaction</strong>, exactly as the cache's did:
 * holding a pooled connection open across a network round trip is how one slow upstream becomes an
 * exhausted pool. {@link #refreshIfStale} reads, then writes — see {@link #absorb} for why that
 * write is deliberately not one transaction either.
 */
@Service
public class OpportunityMirrorService {

	private static final Logger log = LoggerFactory.getLogger(OpportunityMirrorService.class);

	private final GhlPipelineClient ghl;
	private final OpportunityRepository opportunities;
	private final PipelineRepository pipelines;
	/** Unit 47b: the three tier-2/3 collections that ride on the opportunity search. */
	private final com.ie.evalos.repository.GhlNoteRepository notes;
	private final com.ie.evalos.repository.FollowUpRepository followUps;
	private final com.ie.evalos.repository.MeetingRepository meetings;
	private final UUID sellingBrandId;

	OpportunityMirrorService(GhlPipelineClient ghl, OpportunityRepository opportunities,
			PipelineRepository pipelines, com.ie.evalos.repository.GhlNoteRepository notes,
			com.ie.evalos.repository.FollowUpRepository followUps,
			com.ie.evalos.repository.MeetingRepository meetings,
			SellingBrand sellingBrand) {
		this.ghl = ghl;
		this.opportunities = opportunities;
		this.pipelines = pipelines;
		this.notes = notes;
		this.followUps = followUps;
		this.meetings = meetings;
		this.sellingBrandId = sellingBrand.id();
	}

	/**
	 * Re-reads one pipeline from GHL when the mirror's copy has aged past the TTL.
	 *
	 * @param ghlPipelineId GHL's id, because that is what a caller's principal and the board both
	 *                      carry. Resolved to the mirrored row here — slice 44b is where the
	 *                      principal itself starts naming EvalOS rows
	 */
	public void refreshIfStale(String ghlPipelineId, Duration ttl) {
		Optional<Pipeline> mirrored = mirroredPipeline(ghlPipelineId);
		if (mirrored.isEmpty()) {
			// A pipeline the 44a sweep has not seen. Refusing loudly would take a board down over
			// a different sweep being behind; an empty board with a log line is recoverable by
			// POST /api/jobs/PIPELINE_MIRROR/run.
			log.warn("Pipeline {} is not mirrored yet, so its opportunities cannot be. "
					+ "Run the PIPELINE_MIRROR sweep.", ghlPipelineId);
			return;
		}
		Pipeline pipeline = mirrored.get();
		// **The freshness check and the stamp must read the same thing.** This asked
		// `opportunities.lastSyncedFor(...)` — max(synced_at) over the pipeline's DEALS — while
		// `absorb` stamps the PIPELINE. A pipeline with recent deals therefore looked fresh, was
		// skipped, and so never got the stamp the board reads: every board said "never synced"
		// while showing 31 deals. Two sources for one question is how that happens.
		Instant lastSynced = pipeline.getOpportunitiesSyncedAt();
		if (lastSynced != null && Duration.between(lastSynced, Instant.now()).compareTo(ttl) < 0) {
			return;
		}
		// Deliberately outside the transaction below.
		absorb(pipeline, ghl.allIn(ghlPipelineId));
	}

	/**
	 * Writes one GHL read into the mirror: upsert what came back, stamp what did not.
	 *
	 * <p><strong>A row that has never reached GHL is skipped by the absence pass, not marked
	 * missing.</strong> A portal-born opportunity with no {@code ghl_id} was never in GHL's answer
	 * to begin with, so treating its absence as a disappearance would stamp every one of them on
	 * the first refresh after it was created.
	 *
	 * <p><strong>Not {@code @Transactional}, and that is now said rather than implied.</strong> It
	 * carried the annotation and never honoured it: the only caller is {@link #refreshIfStale} in
	 * this same class, so the call never crossed the proxy. Making it real would be worse than
	 * removing it — the location's Master Pipeline is five figures of rows, and wrapping that in
	 * one transaction holds a pooled connection for the whole absorb to buy atomicity nothing here
	 * needs. Every write below is an upsert keyed by GHL's id, so a pass that dies half way is
	 * re-absorbed by the next request; the freshness stamp lands last precisely so that happens.
	 */
	public void absorb(Pipeline pipeline, List<GhlPipelineClient.Opportunity> fromGhl) {
		Instant now = Instant.now();
		Set<String> seen = new HashSet<>();

		for (GhlPipelineClient.Opportunity row : fromGhl) {
			if (row.id() == null || row.id().isBlank()) {
				log.warn("GHL returned an opportunity with no id on pipeline {}; skipped", pipeline.getName());
				continue;
			}
			seen.add(row.id());
			Opportunity held = opportunities.findByBrandIdAndGhlId(pipeline.getBrandId(), row.id())
					.orElseGet(() -> new Opportunity(pipeline.getBrandId(), row.id(), pipeline.getId()));
			held.syncFromGhl(row.contactId(), pipeline.getId(), row.pipelineStageId(), row.name(),
					row.monetaryValue(), row.status(), row.source(), row.assignedTo(), row.createdAt(),
					row.updatedAt(), row.lastStatusChangeAt(), row.lastStageChangeAt());
			absorbTier23(held, row);
			opportunities.save(held);
		}

		for (Opportunity held : opportunities.findByPipelineIdIn(List.of(pipeline.getId()))) {
			if (held.isLocalOnly() || !held.isLive() || seen.contains(held.getGhlId())) {
				continue;
			}
			held.markMissing(now);
			opportunities.save(held);
			log.info("Opportunity {} is no longer on pipeline {} in GHL; marked missing, not deleted",
					held.getGhlId(), pipeline.getName());
		}

		// **Stamped last, and that ordering is the correction.** It was stamped first, so a failure
		// anywhere in the two loops above — a constraint race against a concurrent
		// absorbForContact, a GHL row this code cannot map — left a half-absorbed mirror
		// advertising itself as fresh, and the TTL then suppressed the re-read that would have
		// finished the job. Freshness is a claim about work that has been done, so it is written
		// once the work is done; an unstamped pipeline is simply refreshed again on the next request,
		// and every row write above is an idempotent upsert, so re-absorbing costs nothing.
		//
		// **Still stamped when the answer was empty**, which is the whole point of the column.
		// Deriving this from the rows meant an empty pipeline could never say it had been synced —
		// see V61, and the boards that read "never synced" because of it.
		pipeline.opportunitiesSynced();
		pipelines.save(pipeline);
	}

	/**
	 * One contact's deals, upserted from GHL's own answer — <strong>the webhook's write</strong>
	 * (Unit 45d).
	 *
	 * <p><strong>The webhook is a trigger, not a payload, and that is the whole design.</strong>
	 * GHL's Custom Webhook action posts the <em>contact record</em> flat — no stage, no status, no
	 * value — as {@code GhlOpportunityHandler}'s javadoc records after the nested envelope it was
	 * first built for turned out not to exist. Trusting a workflow author's hand-typed
	 * {@code customData} for a mirrored field would make the mirror only as correct as whoever
	 * edited the workflow last. So the event supplies one thing, the contact id, and the field
	 * values come from GHL where they are authoritative.
	 *
	 * <p><strong>Each row lands on the pipeline GHL says it is on</strong>, not on one the caller
	 * chose — which is what closes the staleness {@link #isOnPipeline} names: a deal moved to
	 * another rep's pipeline changes hands here, at the moment it moves, instead of staying
	 * authorised until something refreshed.
	 *
	 * <p><strong>No absence pass, unlike {@link #absorb}.</strong> A contact's deals are not a
	 * pipeline's whole list, so a row that is missing from this answer is missing from a question
	 * that never asked about it. Marking it would stamp every other deal the contact does not have.
	 * A genuine disappearance is the nightly audit's to notice.
	 *
	 * @return how many rows were written; rows on a pipeline the 44a sweep has not mirrored yet are
	 *         skipped and logged, exactly as {@link #refreshIfStale} skips one
	 */
	@Transactional
	public int absorbForContact(List<GhlPipelineClient.Opportunity> fromGhl) {
		int written = 0;
		for (GhlPipelineClient.Opportunity row : fromGhl) {
			if (row.id() == null || row.id().isBlank()) {
				continue;
			}
			Optional<Pipeline> pipeline = mirroredPipeline(row.pipelineId());
			if (pipeline.isEmpty()) {
				log.warn("Opportunity {} is on unmirrored pipeline {}; not absorbed. "
						+ "Run the PIPELINE_MIRROR sweep.", row.id(), row.pipelineId());
				continue;
			}
			UUID brandId = pipeline.get().getBrandId();
			Opportunity held = opportunities.findByBrandIdAndGhlId(brandId, row.id())
					.orElseGet(() -> new Opportunity(brandId, row.id(), pipeline.get().getId()));
			held.syncFromGhl(row.contactId(), pipeline.get().getId(), row.pipelineStageId(), row.name(),
					row.monetaryValue(), row.status(), row.source(), row.assignedTo(), row.createdAt(),
					row.updatedAt(), row.lastStatusChangeAt(), row.lastStageChangeAt());
			absorbTier23(held, row);
			opportunities.save(held);
			written++;
		}
		return written;
	}

	/**
	 * The background half of refresh-on-read — <strong>the delta sweep</strong> (Unit 45d).
	 *
	 * <p><strong>"Delta" means a stale pipeline, not a changed row, and the API is why.</strong>
	 * GHL's opportunity search filters on {@code createdAt} and offers no updated-since filter at
	 * all — {@code GhlPipelineClient.opportunitiesIn} carries the verification, and it is the same
	 * absence that forces "won this month" to be bucketed locally. So there is no read that returns
	 * only what changed, and a sweep claiming to be one would be a full list wearing a smaller name.
	 *
	 * <p><strong>It costs only the pipelines nobody is looking at.</strong> {@link #refreshIfStale}
	 * returns without a GHL call when the mirror is inside the TTL, so a pipeline a desk has open
	 * is already warm and this pass skips it. What it buys is the pipelines no screen reads: they
	 * stop being permanently stale, which is what the nightly audit needs if its findings are to
	 * mean drift rather than "nobody looked".
	 */
	public int refreshStale(Duration ttl) {
		if (sellingBrandId == null) {
			log.warn("Delta sweep skipped: evalos.ghl.sales-brand is blank, so there is no mirror to refresh.");
			return 0;
		}
		int refreshed = 0;
		for (Pipeline pipeline : pipelines.findByBrandIdOrderByPositionAscNameAsc(sellingBrandId)) {
			if (!pipeline.isLive()) {
				continue;
			}
			refreshIfStale(pipeline.getGhlId(), ttl);
			refreshed++;
		}
		return refreshed;
	}

	/**
	 * The deal's custom field values, notes, tasks and appointments — <strong>Unit 47b</strong>.
	 *
	 * <p><strong>All four arrive on the read that just happened.</strong> {@code getNotes},
	 * {@code getTasks} and {@code getCalendarEvents} are parameters on the opportunity search the
	 * mirror already runs, and {@code customFields} comes back with every row. Unit 47 §4 cut task
	 * read-back believing tasks could only be listed one contact at a time; they cannot only be —
	 * and the cost of having them here is zero extra requests.
	 *
	 * <p><strong>GHL wins outright on all four, so 45e never applies.</strong> A note, a task and an
	 * appointment are GHL's objects: EvalOS creates some of them and edits none, so there is no
	 * field to contest. Custom field values are the same until the unit that lets a desk edit them.
	 *
	 * <p><strong>The values are set, not saved, here.</strong> They are columns on the row the caller
	 * is about to write, so a second {@code save} would be a second write of the same row — which is
	 * exactly what it was until a test counted them.
	 *
	 * <p><strong>Absence is not deletion here, with one exception.</strong> A task or appointment
	 * missing from this answer is left alone — the search returns what it returns, and a paged read
	 * is not proof of removal. A <em>note</em> the deal no longer carries is stamped
	 * {@code missing_since}, because notes are enumerated per opportunity rather than filtered.
	 */
	private void absorbTier23(Opportunity held, GhlPipelineClient.Opportunity row) {
		held.syncCustomFields(row.customFields().stream()
				.filter((field) -> field.id() != null && field.value() != null)
				.collect(java.util.stream.Collectors.toMap(GhlPipelineClient.CustomFieldValue::id,
						GhlPipelineClient.CustomFieldValue::value, (first, second) -> second,
						java.util.LinkedHashMap::new)));

		absorbNotes(held, row);
		absorbTasks(held.getBrandId(), row);
		absorbAppointments(held.getBrandId(), row);
	}

	private void absorbNotes(Opportunity held, GhlPipelineClient.Opportunity row) {
		java.util.Set<String> seen = new HashSet<>();
		for (GhlPipelineClient.Note note : row.noteList()) {
			if (note.id() == null || note.id().isBlank()) {
				continue;
			}
			seen.add(note.id());
			// **Filed under the deal the note is about, not the deal it was listed under** (Unit 54).
			// The search repeats a contact's notes under every one of that contact's deals, so
			// filing by listing made a note flip to whichever deal was read last. GHL's own
			// `relations` names the deal — or none, for a note on the contact alone, which is
			// filed with no deal. An answer with no `relations` at all keeps the old filing.
			String dealId = note.relations() == null ? row.id() : note.relatedOpportunityId();
			GhlNote mirrored = notes.findByBrandIdAndGhlId(held.getBrandId(), note.id())
					.orElseGet(() -> new GhlNote(held.getBrandId(), note.id(), row.contactId(), dealId));
			mirrored.syncFromGhl(note.title(), note.body(), note.authorId(), note.dateAdded(), dealId);
			notes.save(mirrored);
		}
		// Every deal's answer carries the contact's whole note set, so a note filed under this deal
		// or under the contact alone that is not in it has been deleted in GHL.
		Instant now = Instant.now();
		List<GhlNote> filedHere = new java.util.ArrayList<>(
				notes.findByBrandIdAndGhlOpportunityIdOrderByDateAddedDesc(held.getBrandId(), row.id()));
		if (row.contactId() != null) {
			filedHere.addAll(notes.findByBrandIdAndGhlContactIdAndGhlOpportunityIdIsNull(
					held.getBrandId(), row.contactId()));
		}
		for (GhlNote mirrored : filedHere) {
			if (mirrored.isLive() && !seen.contains(mirrored.getGhlId())) {
				mirrored.markMissing(now);
				notes.save(mirrored);
			}
		}
	}

	/**
	 * <strong>The read-back `47` §4 said was impossible.</strong> A task completed in GHL now closes
	 * on the desk instead of sitting open for ever.
	 *
	 * <p>Only tasks EvalOS already knows are updated: a task created in GHL's own UI has no
	 * {@code follow_up} row, and inventing one would put work on a desk's list that nobody here
	 * asked for. That is a decision with a screen behind it, not a side effect of a sync.
	 */
	private void absorbTasks(UUID brandId, GhlPipelineClient.Opportunity row) {
		for (GhlPipelineClient.Task task : row.tasks()) {
			if (task.id() == null || task.id().isBlank()) {
				continue;
			}
			// Brand-scoped, as both repositories insist: a GHL id is unique within a location, and
			// a finder taking one alone is a mistyped caller away from another brand's row.
			followUps.findByBrandIdAndGhlTaskId(brandId, task.id()).ifPresent((held) -> {
				held.syncFromGhl(task.title(), task.body(), task.dueDate(),
						Boolean.TRUE.equals(task.completed()));
				followUps.save(held);
			});
		}
	}

	/** The same, for an appointment cancelled or moved in GHL's own UI. */
	private void absorbAppointments(UUID brandId, GhlPipelineClient.Opportunity row) {
		for (GhlPipelineClient.CalendarEvent event : row.calendarEvents()) {
			if (event.id() == null || event.id().isBlank()) {
				continue;
			}
			meetings.findByBrandIdAndGhlAppointmentId(brandId, event.id()).ifPresent((held) -> {
				held.syncFromGhl(event.title(), event.startTime(), event.endTime(),
						event.appointmentStatus() != null ? event.appointmentStatus() : event.status());
				meetings.save(held);
			});
		}
	}

	/** Every live deal on a set of GHL pipelines — the board read. */
	@Transactional(readOnly = true)
	public List<Opportunity> onPipelines(List<String> ghlPipelineIds) {
		List<UUID> mirrored = ghlPipelineIds.stream()
				.map(this::mirroredPipeline)
				.filter(Optional::isPresent)
				.map((found) -> found.get().getId())
				.toList();
		return mirrored.isEmpty() ? List.of()
				: opportunities.findByPipelineIdIn(mirrored).stream().filter(Opportunity::isLive).toList();
	}

	/**
	 * Whether a deal is on a pipeline — the authorisation question every Sales and Marketing write
	 * asks through {@code PipelineScope.requireMine}.
	 *
	 * <p><strong>{@code 00d} §6.5 records that this used to have a TTL.</strong> Access control was
	 * answered against a cache whose rows were destroyed and recreated on every board refresh, so a
	 * newly opened lead was <em>immediately</em> unauthorised until the next refill. A mirror that
	 * is upserted rather than replaced fixes that half by construction: a row written on create
	 * stays written. The other half — a deal moved to another rep's pipeline in GHL staying
	 * authorised here until something refreshes — is a staleness bound rather than a hole, and
	 * Unit 45's delta sweep is what tightens it.
	 */
	@Transactional(readOnly = true)
	public boolean isOnPipeline(String ghlOpportunityId, String ghlPipelineId) {
		return mirroredPipeline(ghlPipelineId)
				.map((pipeline) -> opportunities.existsByBrandIdAndGhlIdAndPipelineId(
						pipeline.getBrandId(), ghlOpportunityId, pipeline.getId()))
				.orElse(false);
	}

	/**
	 * When this pipeline's copy was last confirmed against GHL, or null if never.
	 *
	 * <p>The board prints it, so a reader is told how old the answer is rather than left to assume
	 * it is live — the same contract the cache's {@code readAt} carried.
	 */
	/**
	 * <strong>Read from the pipeline, not derived from its deals</strong> (V61).
	 *
	 * <p>It was {@code max(opportunity.synced_at)}, which cannot express "synced, and there was
	 * nothing there" — so every empty pipeline reported never-synced, and after Unit 46 made that a
	 * banner, every board on the screen accused the sync of being broken.
	 *
	 * <p><strong>The oldest of the caller's pipelines, not the newest.</strong> A board covering
	 * several is only as current as its stalest one; reporting the freshest would let one
	 * recently-read pipeline vouch for others nothing has touched in hours.
	 */
	@Transactional(readOnly = true)
	public Instant lastSynced(List<String> ghlPipelineIds) {
		List<Optional<Pipeline>> mirrored = ghlPipelineIds.stream().map(this::mirroredPipeline).toList();
		if (mirrored.stream().anyMatch((found) -> found.isEmpty()
				|| found.get().getOpportunitiesSyncedAt() == null)) {
			// A pipeline that is not mirrored at all, or has never been read, makes the whole
			// board's age unknown. Answering with the others' timestamp would vouch for it.
			return null;
		}
		return mirrored.stream()
				.map((found) -> found.get().getOpportunitiesSyncedAt())
				.min(java.util.Comparator.naturalOrder())
				.orElse(null);
	}

	/**
	 * A deal EvalOS opened, recorded before GHL is asked — the row {@code 00c} §2a exists for.
	 *
	 * <p>It has no {@code ghl_id} until {@link #linkGhl} is called with GHL's answer, and its own
	 * {@code id} is the correlation key written into a GHL custom field on create ({@code 00d}
	 * §6.1). That is what makes a retry after a timeout answerable: the row already names itself,
	 * so "did my create land?" is a question with an answer rather than a guess.
	 *
	 * <p><strong>Takes GHL's pipeline id, because that is what every caller holds</strong> — a
	 * salesperson's principal and the intake pipeline lookup both yield GHL's string, and slice 44b
	 * is where the principal starts naming EvalOS rows. Empty when that pipeline is not mirrored
	 * yet: the caller then creates in GHL without a correlation key, which is exactly the duplicate
	 * exposure that exists today rather than a new one.
	 */
	@Transactional
	public Optional<Opportunity> openLocally(String ghlPipelineId, String ghlContactId, String name) {
		return mirroredPipeline(ghlPipelineId).map((pipeline) -> {
			Opportunity opened = new Opportunity(pipeline.getBrandId(), null, pipeline.getId());
			opened.syncFromGhl(ghlContactId, pipeline.getId(), null, name, null, "open", null, null,
					null, null, null, null);
			opened.touchedLocally();
			return opportunities.saveAndFlush(opened);
		});
	}

	/**
	 * A desk just created this deal in GHL: put it in the mirror now.
	 *
	 * <p><strong>Without this a create was invisible to the very next request.</strong> Both desks
	 * create straight in GHL and answer from GHL's reply, while every desk <em>edit</em> refuses a
	 * deal the mirror has not absorbed — so correcting a lead's name or value seconds after
	 * opening it answered 400 for up to a full {@code MIRROR_DELTA}, and the marketer's own
	 * correction looked like a bug in the screen they were standing on.
	 *
	 * <p><strong>From the create's own answer, not a second GHL read.</strong> GHL has just told us
	 * what it stored; asking again would spend another request on a question already answered.
	 *
	 * <p>GHL's timestamps are left null deliberately rather than filled with EvalOS's clock: they
	 * are GHL's to state, the next sweep states them, and a fabricated {@code ghl_updated_at} is
	 * exactly the kind of invented value {@link FieldOwnership} exists to keep out of the mirror.
	 *
	 * <p>Skipped with a warning when the pipeline is not mirrored yet, the same way
	 * {@link #refreshIfStale} skips one: the deal is in GHL and the sweep will bring it in.
	 */
	@Transactional
	public void absorbCreated(String ghlPipelineId, String ghlId, String ghlContactId, String name,
			java.math.BigDecimal amount, String status, String ghlStageId, String source) {
		if (ghlId == null || ghlId.isBlank()) {
			return;
		}
		Optional<Pipeline> mirrored = mirroredPipeline(ghlPipelineId);
		if (mirrored.isEmpty()) {
			log.warn("Pipeline {} is not mirrored yet, so the deal just created on it cannot be. "
					+ "The next sweep brings it in.", ghlPipelineId);
			return;
		}
		Pipeline pipeline = mirrored.get();
		Opportunity held = opportunities.findByBrandIdAndGhlId(pipeline.getBrandId(), ghlId)
				.orElseGet(() -> new Opportunity(pipeline.getBrandId(), ghlId, pipeline.getId()));
		held.syncFromGhl(ghlContactId, pipeline.getId(), ghlStageId, name, amount, status, source,
				null, null, null, null, null);
		opportunities.save(held);
	}

	/**
	 * A desk edited a deal — <strong>the mirror is written first and GHL hears about it after</strong>
	 * (Unit 46).
	 *
	 * <p><strong>This is the inversion the unit is named for.</strong> A desk used to call GHL and
	 * show whatever came back; it now writes the row it is already looking at, queues the push, and
	 * redraws from local state. The screen stops waiting on a network round trip, and an edit stops
	 * being lost when GHL is down.
	 *
	 * <p>Takes GHL's opportunity id because that is what every desk route carries — the board's
	 * cards, the URL and {@code PipelineScope.requireMine} all speak it.
	 *
	 * @return the edited row, or empty when no mirrored deal has that id. Empty is a real answer
	 *         rather than an exception: a deal GHL knows and the mirror has not absorbed yet is a
	 *         staleness the caller should report as such, not a 500
	 */
	@Transactional
	public Optional<Opportunity> editLocally(String ghlOpportunityId, String name,
			java.math.BigDecimal amount, String ghlStageId, String status) {
		if (sellingBrandId == null) {
			return Optional.empty();
		}
		return opportunities.findByBrandIdAndGhlId(sellingBrandId, ghlOpportunityId)
				.map((row) -> {
					row.editedLocally(name, amount, ghlStageId, status);
					return opportunities.save(row);
				});
	}

	/**
	 * One mirrored deal, by GHL's id — what every desk route carries.
	 *
	 * <p>Empty when the mirror has not absorbed it, which is a staleness for the caller to report
	 * rather than an error: the deal exists in GHL and will be here after the next sweep.
	 */
	@Transactional(readOnly = true)
	public Optional<Opportunity> byGhlId(String ghlOpportunityId) {
		return sellingBrandId == null ? Optional.empty()
				: opportunities.findByBrandIdAndGhlId(sellingBrandId, ghlOpportunityId);
	}

	/** GHL answered a create. The row keeps its id and gains GHL's — identity never changes. */
	@Transactional
	public void linkGhl(UUID opportunityId, String ghlId) {
		opportunities.findById(opportunityId).ifPresent((row) -> {
			row.linkGhl(ghlId);
			opportunities.save(row);
		});
	}

	private Optional<Pipeline> mirroredPipeline(String ghlPipelineId) {
		return sellingBrandId == null ? Optional.empty()
				: pipelines.findByBrandIdAndGhlId(sellingBrandId, ghlPipelineId);
	}

}
