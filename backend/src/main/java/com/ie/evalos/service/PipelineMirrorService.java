package com.ie.evalos.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.PipelinePurpose;
import com.ie.evalos.domain.PipelineStage;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.repository.PipelineRepository;
import com.ie.evalos.repository.PipelineStageRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pulls GHL's pipelines and stages into EvalOS rows — Unit 44, slice A.
 *
 * <p><strong>One direction, always.</strong> GHL owns every mirrored column here ({@code 00d}
 * §6.2), so this service reads and writes locally and pushes nothing. That is what makes it the
 * safe first slice of the mirror: these tables can be <em>behind</em> GHL, never in conflict with
 * it, so none of Unit 45's conflict machinery is needed to make them correct.
 *
 * <p><strong>Upsert in place, and never delete.</strong> The cache this supersedes
 * ({@code ghl_opportunity_cache}) is refilled by delete-all-then-insert-all, which destroys every
 * row's identity on every pass — {@code 00d} §6.5 calls that the deepest of five reasons it is
 * unsalvageable, because nothing can hold a foreign key into it and no row can carry local state.
 * Here a row that GHL stops returning is stamped {@code missing_since} and kept: {@code purpose} is
 * EvalOS's own judgement about that pipeline, and a pipeline archived for an afternoon must not
 * come back having lost it.
 *
 * <p><strong>The stage re-match is the subtle part.</strong> GHL stage ids are not stable across a
 * delete-and-recreate, and IE has demonstrated it will recreate things. A stage whose id is unknown
 * but whose {@code (pipeline, position, name)} matches a row already held is a <em>repoint</em>,
 * not an insert — the row keeps its {@code id} and every foreign key into it survives. Without
 * that, a recreated pipeline reads as every opportunity in it having drifted ({@code 00d} §6.7).
 * The repoint happens only on an unambiguous single match; a tie is left alone and reported,
 * because a wrong repoint silently moves every opportunity in a stage.
 *
 * <p><strong>Scope: the selling brand, and only it.</strong> {@code evalos.ghl.location-id} names
 * one sub-account with no link to a brand — invariant 1's stated exception — and
 * {@code evalos.ghl.sales-brand} is the property Unit 36 added to narrow it. Blank means no brand
 * owns the location, so there is nothing to mirror into and this no-ops loudly rather than guessing
 * a tenant. Unit 25 puts the location on {@code brand} and this becomes a loop.
 */
@Service
public class PipelineMirrorService {

	private static final Logger log = LoggerFactory.getLogger(PipelineMirrorService.class);

	/**
	 * What one pass changed, for the sweep ledger and for a human reading the admin panel.
	 *
	 * @param seen     pipelines GHL returned
	 * @param created  rows inserted
	 * @param updated  rows whose GHL-owned fields changed
	 * @param repointed stages re-found by their natural key after GHL recreated them
	 * @param missing  rows GHL no longer returns, newly stamped this pass
	 * @param ambiguous stage re-matches abandoned because more than one row fit
	 */
	public record MirrorResult(int seen, int created, int updated, int repointed, int missing, int ambiguous) {

		/** Anything a reader would call a change — what the sweep ledger records as "acted". */
		public int changed() {
			return created + updated + repointed + missing;
		}
	}

	private final GhlPipelineClient ghl;
	private final PipelineRepository pipelines;
	private final PipelineStageRepository stages;
	/** Unit 44b's join table, so a mirrored pipeline can resolve a desk still on the old column. */
	private final com.ie.evalos.repository.TeamMemberPipelineRepository assignments;
	private final UUID sellingBrandId;

	PipelineMirrorService(GhlPipelineClient ghl, PipelineRepository pipelines, PipelineStageRepository stages,
			com.ie.evalos.repository.TeamMemberPipelineRepository assignments,
			SellingBrand sellingBrand) {
		this.ghl = ghl;
		this.pipelines = pipelines;
		this.stages = stages;
		this.assignments = assignments;
		this.sellingBrandId = sellingBrand.id();
	}

	/** Whether this deployment knows which brand owns the configured GHL location. */
	public boolean isConfigured() {
		return sellingBrandId != null;
	}

	/**
	 * One full pass: read every pipeline GHL has, and make the local rows say the same thing.
	 *
	 * <p>Not incremental, and it does not need to be. GHL's pipeline list is one request and a
	 * handful of rows — the whole point of the pagination machinery next door is that
	 * <em>opportunities</em> are the large collection, not pipelines. A full compare each pass is
	 * also what makes {@code missing_since} correct: a delta read cannot notice an absence.
	 */
	@Transactional
	public MirrorResult sync() {
		if (sellingBrandId == null) {
			log.warn("Pipeline mirror skipped: evalos.ghl.sales-brand is blank, so no brand owns "
					+ "the configured GHL location and there is nothing to mirror into.");
			return new MirrorResult(0, 0, 0, 0, 0, 0);
		}

		List<GhlPipelineClient.Pipeline> fromGhl = ghl.pipelines();
		Instant now = Instant.now();
		Counters counters = new Counters();

		Set<String> seenPipelineIds = new HashSet<>();
		for (int index = 0; index < fromGhl.size(); index++) {
			GhlPipelineClient.Pipeline row = fromGhl.get(index);
			if (row.id() == null || row.id().isBlank()) {
				// A pipeline with no id cannot be mirrored or compared. Skipped rather than
				// inserted under a guessed key — a row that cannot be matched next pass would be
				// re-inserted forever.
				log.warn("GHL returned a pipeline with no id (name={}); skipped", row.name());
				continue;
			}
			seenPipelineIds.add(row.id());
			Pipeline mirrored = upsertPipeline(row, index, counters);
			syncStages(mirrored, row.stages() == null ? List.of() : row.stages(), now, counters);
		}

		// Absence, which is the half a delta read can never see.
		for (Pipeline held : pipelines.findByBrandIdOrderByPositionAscNameAsc(sellingBrandId)) {
			if (!seenPipelineIds.contains(held.getGhlId()) && held.isLive()) {
				held.markMissing(now);
				pipelines.save(held);
				counters.missing++;
				// Its stages go with it: a stage of a pipeline GHL no longer has is not a stage
				// that merely moved, and leaving them live would let a stale id keep resolving.
				for (PipelineStage stage : stages.findByPipelineIdOrderByPositionAsc(held.getId())) {
					if (stage.isLive()) {
						stage.markMissing(now);
						stages.save(stage);
					}
				}
				log.info("Pipeline '{}' ({}) is no longer returned by GHL; marked missing, not deleted",
						held.getName(), held.getGhlId());
			}
		}

		// **Unit 44b's migration, finished here because no seed can finish it.** A member whose desk
		// is still described by the column 44b replaced gets their join row the moment the pipeline
		// it names is mirrored -- see TeamMemberPipelineRepository.backfillFromLegacyColumn. Without
		// it a fresh database leaves every seeded desk with no pipelines at all, and an empty board
		// looks exactly like a GHL problem while being nothing of the kind.
		int backfilled = assignments.backfillFromLegacyColumn();
		if (backfilled > 0) {
			log.info("Backfilled {} desk pipeline assignment(s) from the legacy column", backfilled);
		}

		MirrorResult result = new MirrorResult(fromGhl.size(), counters.created, counters.updated,
				counters.repointed, counters.missing, counters.ambiguous);
		log.info("Pipeline mirror: {}", result);
		return result;
	}

	/**
	 * <strong>The pipeline's position is its index in GHL's own response, because GHL sends no
	 * other order.</strong> {@code GET /opportunities/pipelines} carries a {@code position} on each
	 * <em>stage</em> and nothing on the pipeline itself, so the list order is the display order —
	 * the same assumption every GHL screen makes. Storing it means a board can be drawn without a
	 * second call, and if GHL ever adds a real field this is the one line that changes.
	 */
	private Pipeline upsertPipeline(GhlPipelineClient.Pipeline row, int position, Counters counters) {
		String name = nameOf(row.name(), row.id());
		Optional<Pipeline> existing = pipelines.findByBrandIdAndGhlId(sellingBrandId, row.id());
		if (existing.isEmpty()) {
			counters.created++;
			return pipelines.saveAndFlush(new Pipeline(sellingBrandId, row.id(), name, position));
		}
		Pipeline held = existing.get();
		boolean changed = !name.equals(held.getName()) || position != held.getPosition() || !held.isLive();
		held.syncFromGhl(name, position);
		if (changed) {
			counters.updated++;
		}
		return pipelines.save(held);
	}

	/**
	 * Reconcile one pipeline's stages.
	 *
	 * <p>Three outcomes per incoming stage, in this order: a known {@code ghl_id} is an update; an
	 * unknown id whose natural key matches exactly one held row is a <strong>repoint</strong>; and
	 * anything else is an insert. The order matters — trying the natural key first would repoint
	 * two stages that swapped positions onto each other.
	 */
	private void syncStages(Pipeline pipeline, List<GhlPipelineClient.Pipeline.Stage> fromGhl, Instant now,
			Counters counters) {
		Map<UUID, PipelineStage> held = new HashMap<>();
		stages.findByPipelineIdOrderByPositionAsc(pipeline.getId())
				.forEach((stage) -> held.put(stage.getId(), stage));

		Set<UUID> matched = new HashSet<>();
		List<GhlPipelineClient.Pipeline.Stage> ordered = new ArrayList<>(fromGhl);
		ordered.sort(Comparator.comparingInt(GhlPipelineClient.Pipeline.Stage::position));

		for (GhlPipelineClient.Pipeline.Stage incoming : ordered) {
			if (incoming.id() == null || incoming.id().isBlank()) {
				log.warn("GHL returned a stage with no id on pipeline '{}'; skipped", pipeline.getName());
				continue;
			}

			Optional<PipelineStage> byId = stages.findByBrandIdAndGhlId(sellingBrandId, incoming.id());
			if (byId.isPresent()) {
				PipelineStage stage = byId.get();
				boolean changed = !incoming.name().equals(stage.getName())
						|| incoming.position() != stage.getPosition() || !stage.isLive();
				stage.syncFromGhl(incoming.id(), incoming.name(), incoming.position());
				stages.save(stage);
				matched.add(stage.getId());
				if (changed) {
					counters.updated++;
				}
				continue;
			}

			List<PipelineStage> byNaturalKey = stages
					.findByPipelineIdAndPositionAndName(pipeline.getId(), incoming.position(), incoming.name())
					.stream()
					.filter((candidate) -> !matched.contains(candidate.getId()))
					.toList();
			if (byNaturalKey.size() == 1) {
				PipelineStage stage = byNaturalKey.getFirst();
				log.info("Stage '{}' on '{}' came back under a new GHL id ({} -> {}); repointed rather "
						+ "than duplicated", incoming.name(), pipeline.getName(), stage.getGhlId(),
						incoming.id());
				stage.syncFromGhl(incoming.id(), incoming.name(), incoming.position());
				stages.save(stage);
				matched.add(stage.getId());
				counters.repointed++;
				continue;
			}
			if (byNaturalKey.size() > 1) {
				// Left alone on purpose. Repointing the wrong one moves every opportunity in that
				// stage, which is worse than an ambiguity somebody can be told about.
				log.warn("Stage '{}' at position {} on '{}' matches {} held rows; inserting rather than "
						+ "repointing", incoming.name(), incoming.position(), pipeline.getName(),
						byNaturalKey.size());
				counters.ambiguous++;
			}

			PipelineStage created = stages.saveAndFlush(new PipelineStage(sellingBrandId, pipeline.getId(),
					incoming.id(), incoming.name(), incoming.position()));
			matched.add(created.getId());
			counters.created++;
		}

		for (PipelineStage stage : held.values()) {
			if (!matched.contains(stage.getId()) && stage.isLive()) {
				stage.markMissing(now);
				stages.save(stage);
				counters.missing++;
			}
		}
	}

	/**
	 * A pipeline GHL returned without a name still needs one to be readable on a screen.
	 *
	 * <p>Its id, rather than "Unnamed": a GM who has to go and find this pipeline in GHL can search
	 * for the id and cannot search for a placeholder.
	 */
	private static String nameOf(String name, String ghlId) {
		return name == null || name.isBlank() ? ghlId : name;
	}

	/**
	 * Every mirrored pipeline, missing ones included.
	 *
	 * <p><strong>The brand is the service's, not the caller's.</strong> The only reader of this is
	 * the GM, who has no brand of their own, and the rows belong to whichever brand owns the
	 * configured GHL location. Taking a {@code brandId} parameter would invite a caller to pass
	 * their own and get an empty list that looks like "no pipelines" rather than "not your
	 * location" — the silent-empty failure Unit 36 already had once with a dead pipeline id.
	 */
	@Transactional(readOnly = true)
	public List<Pipeline> all() {
		return sellingBrandId == null ? List.of()
				: pipelines.findByBrandIdOrderByPositionAscNameAsc(sellingBrandId);
	}

	/** One pipeline's stages in GHL's own display order. */
	@Transactional(readOnly = true)
	public List<PipelineStage> stagesOf(UUID pipelineId) {
		return stages.findByPipelineIdOrderByPositionAsc(pipelineId);
	}

	/**
	 * A GM says what a pipeline is for.
	 *
	 * <p>The only write EvalOS makes to a mirrored pipeline, and it never leaves the building —
	 * GHL has no concept of a purpose, so there is nothing to push and nothing to conflict with.
	 * It is also the reason {@code sync()} never touches the column: a sweep that wrote it would
	 * overwrite this with a default on every pass.
	 */
	@Transactional
	public Pipeline setPurpose(UUID pipelineId, PipelinePurpose purpose) {
		Pipeline pipeline = pipelines.findById(pipelineId)
				.filter((row) -> row.getBrandId().equals(sellingBrandId))
				.orElseThrow(() -> new com.ie.evalos.common.InvalidRequestException(
						"No such mirrored pipeline. Run the PIPELINE_MIRROR sweep if this is a new one."));
		pipeline.setPurpose(purpose);
		return pipelines.save(pipeline);
	}

	private static final class Counters {

		private int created;

		private int updated;

		private int repointed;

		private int missing;

		private int ambiguous;
	}

}
