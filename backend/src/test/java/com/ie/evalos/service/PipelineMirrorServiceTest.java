package com.ie.evalos.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.PipelinePurpose;
import com.ie.evalos.domain.PipelineStage;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.repository.PipelineRepository;
import com.ie.evalos.repository.PipelineStageRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The mirror's four behaviours that are not obvious from its shape.
 *
 * <p>A row is <strong>never deleted</strong>, a stage GHL recreated under a new id is
 * <strong>repointed rather than duplicated</strong>, an ambiguous re-match is
 * <strong>left alone</strong>, and {@code purpose} — the one column EvalOS owns — is
 * <strong>never touched by a sweep</strong>. Each of those is a one-line change away from being
 * wrong in a way no screen would show.
 */
class PipelineMirrorServiceTest {

	private static final UUID BRAND = UUID.randomUUID();

	private final GhlPipelineClient ghl = mock(GhlPipelineClient.class);

	private final PipelineRepository pipelines = mock(PipelineRepository.class);

	private final PipelineStageRepository stages = mock(PipelineStageRepository.class);
	private final com.ie.evalos.repository.TeamMemberPipelineRepository assignments =
			mock(com.ie.evalos.repository.TeamMemberPipelineRepository.class);

	private final PipelineMirrorService mirror =
			new PipelineMirrorService(ghl, pipelines, stages, assignments, new SellingBrand(BRAND));

	/** Rows the fakes hand back, so a save is observable without a database. */
	private final List<Pipeline> heldPipelines = new ArrayList<>();

	private final List<PipelineStage> heldStages = new ArrayList<>();

	@BeforeEach
	void wireRepositories() {
		given(pipelines.findByBrandIdOrderByPositionAscNameAsc(BRAND)).willReturn(heldPipelines);
		given(pipelines.findByBrandIdAndGhlId(eq(BRAND), anyString())).willAnswer((call) ->
				heldPipelines.stream().filter((row) -> row.getGhlId().equals(call.getArgument(1))).findFirst());
		given(pipelines.save(any())).willAnswer((call) -> call.getArgument(0));
		given(pipelines.saveAndFlush(any())).willAnswer((call) -> withId(call.getArgument(0)));

		given(stages.findByPipelineIdOrderByPositionAsc(any())).willAnswer((call) ->
				heldStages.stream().filter((row) -> row.getPipelineId().equals(call.getArgument(0))).toList());
		given(stages.findByBrandIdAndGhlId(eq(BRAND), anyString())).willAnswer((call) ->
				heldStages.stream().filter((row) -> row.getGhlId().equals(call.getArgument(1))).findFirst());
		given(stages.findByPipelineIdAndPositionAndName(any(), anyInt(), anyString())).willAnswer((call) ->
				heldStages.stream()
						.filter((row) -> row.getPipelineId().equals(call.getArgument(0)))
						.filter((row) -> row.getPosition() == (int) call.getArgument(1))
						.filter((row) -> row.getName().equals(call.getArgument(2)))
						.toList());
		given(stages.save(any())).willAnswer((call) -> call.getArgument(0));
		given(stages.saveAndFlush(any())).willAnswer((call) -> withId(call.getArgument(0)));
	}

	private static <T> T withId(T row) {
		ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
		return row;
	}

	private Pipeline held(String ghlId, String name) {
		Pipeline pipeline = withId(new Pipeline(BRAND, ghlId, name, 0));
		heldPipelines.add(pipeline);
		return pipeline;
	}

	private PipelineStage heldStage(Pipeline pipeline, String ghlId, String name, int position) {
		PipelineStage stage = withId(new PipelineStage(BRAND, pipeline.getId(), ghlId, name, position));
		heldStages.add(stage);
		return stage;
	}

	private static GhlPipelineClient.Pipeline fromGhl(String id, String name,
			GhlPipelineClient.Pipeline.Stage... stages) {
		return new GhlPipelineClient.Pipeline(id, name, List.of(stages));
	}

	private static GhlPipelineClient.Pipeline.Stage stage(String id, String name, int position) {
		return new GhlPipelineClient.Pipeline.Stage(id, name, position);
	}

	/**
	 * <strong>The reason the whole table is an upsert and not a refill.</strong>
	 *
	 * <p>A pipeline GHL stops returning keeps its row and its {@code purpose}. The cache this
	 * replaces refilled by delete-all-then-insert-all, so a pipeline hidden in GHL for an afternoon
	 * would have come back as a new row meaning nothing — and any foreign key into it would already
	 * have been broken.
	 */
	@Test
	void aPipelineGhlStopsReturningIsMarkedMissingAndKeepsItsPurpose() {
		Pipeline sales = held("p-1", "Sales — Evaluation");
		sales.setPurpose(PipelinePurpose.SALES);
		given(ghl.pipelines()).willReturn(List.of());

		var result = mirror.sync();

		assertThat(sales.getMissingSince()).isNotNull();
		assertThat(sales.getPurpose()).isEqualTo(PipelinePurpose.SALES);
		assertThat(result.missing()).isEqualTo(1);
	}

	/**
	 * <strong>The repoint, and the failure it prevents.</strong>
	 *
	 * <p>GHL stage ids are not stable across a delete-and-recreate. Without matching on
	 * {@code (pipeline, position, name)} the recreated stage would insert as a second row, the old
	 * one would be stamped missing, and every opportunity pointing at the old id would read as
	 * having drifted — which is exactly the false alarm {@code 00d} §6.7 names.
	 */
	@Test
	void aStageRecreatedUnderANewGhlIdIsRepointedRatherThanDuplicated() {
		Pipeline sales = held("p-1", "Sales");
		PipelineStage warm = heldStage(sales, "old-stage-id", "Warm", 1);
		UUID keptRowId = warm.getId();

		given(ghl.pipelines()).willReturn(List.of(fromGhl("p-1", "Sales", stage("new-stage-id", "Warm", 1))));

		var result = mirror.sync();

		assertThat(result.repointed()).isEqualTo(1);
		assertThat(result.created()).isZero();
		// The row survives — same primary key, new GHL id — so anything pointing at it still does.
		assertThat(warm.getId()).isEqualTo(keptRowId);
		assertThat(warm.getGhlId()).isEqualTo("new-stage-id");
		assertThat(warm.getMissingSince()).isNull();
	}

	/**
	 * <strong>An ambiguous re-match inserts and reports rather than guessing.</strong>
	 *
	 * <p>GHL promises neither distinct positions nor distinct names within a pipeline. Repointing
	 * the wrong one of two candidates silently moves every opportunity in a stage, which is worse
	 * than a duplicate row a drift report can name.
	 */
	@Test
	void twoCandidatesForOneStageAreLeftAloneAndCounted() {
		Pipeline sales = held("p-1", "Sales");
		heldStage(sales, "a", "Review", 2);
		heldStage(sales, "b", "Review", 2);

		given(ghl.pipelines()).willReturn(List.of(fromGhl("p-1", "Sales", stage("c", "Review", 2))));

		var result = mirror.sync();

		assertThat(result.ambiguous()).isEqualTo(1);
		assertThat(result.repointed()).isZero();
		assertThat(result.created()).isEqualTo(1);
	}

	/**
	 * <strong>A sweep never writes {@code purpose}.</strong>
	 *
	 * <p>GHL has no such concept, so a sync that set it would overwrite a GM's own judgement with
	 * the default on every pass — an hourly, silent reset of the column that decides where a
	 * client's request is routed.
	 */
	@Test
	void aRoutineSyncLeavesTheGmsPurposeAlone() {
		Pipeline intake = held("p-1", "Client Intake");
		intake.setPurpose(PipelinePurpose.INTAKE);

		given(ghl.pipelines()).willReturn(List.of(fromGhl("p-1", "Client Intake renamed")));
		mirror.sync();

		assertThat(intake.getPurpose()).isEqualTo(PipelinePurpose.INTAKE);
		assertThat(intake.getName()).isEqualTo("Client Intake renamed");
	}

	/**
	 * No selling brand means no tenant to mirror into.
	 *
	 * <p>Silently mirroring into a guessed brand would put one location's pipelines under another
	 * brand's scope, which is the leak invariant 1's exception is narrowed to prevent. GHL is not
	 * even asked.
	 */
	@Test
	void aBlankSellingBrandMirrorsNothingAndDoesNotCallGhl() {
		PipelineMirrorService unconfigured = new PipelineMirrorService(ghl, pipelines, stages, assignments, new SellingBrand((java.util.UUID) null));

		var result = unconfigured.sync();

		assertThat(unconfigured.isConfigured()).isFalse();
		assertThat(result.seen()).isZero();
		org.mockito.BDDMockito.then(ghl).shouldHaveNoInteractions();
	}

	/** A pipeline that returns after an absence is live again, with the meaning it had before. */
	@Test
	void aPipelineThatComesBackIsLiveAgain() {
		Pipeline sales = held("p-1", "Sales");
		sales.setPurpose(PipelinePurpose.SALES);
		sales.markMissing(java.time.Instant.now());

		given(ghl.pipelines()).willReturn(List.of(fromGhl("p-1", "Sales")));
		mirror.sync();

		assertThat(sales.getMissingSince()).isNull();
		assertThat(sales.getPurpose()).isEqualTo(PipelinePurpose.SALES);
	}

	/** Optional-returning finders must be empty, not null, before the first row exists. */
	@Test
	void aFirstSyncInsertsEveryPipelineAndStage() {
		given(pipelines.findByBrandIdAndGhlId(eq(BRAND), anyString())).willReturn(Optional.empty());
		given(ghl.pipelines()).willReturn(List.of(
				fromGhl("p-1", "Sales", stage("s-1", "New", 0), stage("s-2", "Won", 1))));

		var result = mirror.sync();

		assertThat(result.seen()).isEqualTo(1);
		// One pipeline plus two stages.
		assertThat(result.created()).isEqualTo(3);
		assertThat(result.missing()).isZero();
	}

}
