package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.repository.OpportunityRepository;
import com.ie.evalos.repository.PipelineRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * exhausted pool. {@link #refreshIfStale} reads, then calls a transactional write.
 */
@Service
public class OpportunityMirrorService {

	private static final Logger log = LoggerFactory.getLogger(OpportunityMirrorService.class);

	private final GhlPipelineClient ghl;
	private final OpportunityRepository opportunities;
	private final PipelineRepository pipelines;
	private final UUID sellingBrandId;

	OpportunityMirrorService(GhlPipelineClient ghl, OpportunityRepository opportunities,
			PipelineRepository pipelines, @Value("${evalos.ghl.sales-brand:}") String salesBrandId) {
		this.ghl = ghl;
		this.opportunities = opportunities;
		this.pipelines = pipelines;
		this.sellingBrandId = salesBrandId == null || salesBrandId.isBlank() ? null
				: UUID.fromString(salesBrandId);
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
		Instant lastSynced = opportunities.lastSyncedFor(pipeline.getId());
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
	 */
	@Transactional
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
	@Transactional(readOnly = true)
	public Instant lastSynced(List<String> ghlPipelineIds) {
		return ghlPipelineIds.stream()
				.map(this::mirroredPipeline)
				.filter(Optional::isPresent)
				.map((found) -> opportunities.lastSyncedFor(found.get().getId()))
				.filter((at) -> at != null)
				.max(java.util.Comparator.naturalOrder())
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
