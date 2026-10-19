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

	private final AuditService audit = mock(AuditService.class);

	private final PipelineMirrorService mirror =
			new PipelineMirrorService(ghl, pipelines, stages, assignments, new SellingBrand(BRAND), audit);

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
		Pipeline sales = held("p-1", "Sales Desk");
		sales.setPurpose(PipelinePurpose.SALES);

		given(ghl.pipelines()).willReturn(List.of(fromGhl("p-1", "Sales Desk renamed")));
		mirror.sync();

		assertThat(sales.getPurpose()).isEqualTo(PipelinePurpose.SALES);
		assertThat(sales.getName()).isEqualTo("Sales Desk renamed");
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
		PipelineMirrorService unconfigured = new PipelineMirrorService(ghl, pipelines, stages, assignments, new SellingBrand((java.util.UUID) null), audit);

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

	/** V94: a new stage called "Won" starts out won, so nothing changes out of the box; no other name does. */
	@Test
	void aNewStageNamedWonStartsOutWon() {
		given(ghl.pipelines()).willReturn(List.of(
				fromGhl("p-1", "Sales", stage("s-1", "Closed won", 0), stage("s-2", " WON ", 1))));
		org.mockito.ArgumentCaptor<PipelineStage> inserted = org.mockito.ArgumentCaptor.forClass(PipelineStage.class);

		mirror.sync();

		org.mockito.BDDMockito.then(stages).should(org.mockito.Mockito.times(2)).saveAndFlush(inserted.capture());
		assertThat(inserted.getAllValues()).extracting(PipelineStage::getName, PipelineStage::isWon)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("Closed won", false),
						org.assertj.core.groups.Tuple.tuple(" WON ", true));
	}

	/**
	 * <strong>A sweep never writes {@code won}</strong>, like {@code purpose}: GHL has no won marker,
	 * so a re-sync that recomputed it from the name would undo an Admin's choice every hour.
	 */
	@Test
	void aRoutineSyncLeavesTheAdminsWonFlagAlone() {
		Pipeline sales = held("p-1", "Sales");
		PipelineStage won = heldStage(sales, "s-won", "Won", 0);
		won.setWon(false);
		PipelineStage closed = heldStage(sales, "s-closed", "Closed won", 1);
		closed.setWon(true);

		given(ghl.pipelines()).willReturn(List.of(fromGhl("p-1", "Sales",
				stage("s-won", "Won", 0), stage("s-closed", "Closed won (renamed)", 1))));
		mirror.sync();

		assertThat(won.isWon()).isFalse();
		assertThat(closed.isWon()).isTrue();
	}

	/** Signs in as the GM, who sets pipeline config (the Admin only reads it), and returns their id. */
	private static UUID signInAsGm() {
		UUID gm = UUID.randomUUID();
		var principal = new com.ie.evalos.security.StaffPrincipal(gm, "gm@evalos.local", "GM",
				com.ie.evalos.domain.Role.GM, BRAND, null, null, true);
		org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
				new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
						principal, null, principal.getAuthorities()));
		return gm;
	}

	/** The GM's change to the flag is audited, before and after; a no-op writes nothing. */
	@Test
	void settingWonIsAuditedAndANoOpIsNot() {
		UUID gm = signInAsGm();
		try {
			PipelineStage closed = heldStage(held("p-1", "Sales"), "s-closed", "Closed won", 1);
			given(stages.findById(closed.getId())).willReturn(Optional.of(closed));

			assertThat(mirror.setWon(closed.getId(), true).isWon()).isTrue();
			mirror.setWon(closed.getId(), true);

			org.mockito.BDDMockito.then(audit).should().recordEvent("PIPELINE_STAGE", closed.getId(),
					com.ie.evalos.domain.AuditAction.UPDATED, gm, java.util.Map.of("won", false),
					java.util.Map.of("won", true));
			org.mockito.BDDMockito.then(audit).shouldHaveNoMoreInteractions();
		}
		finally {
			org.springframework.security.core.context.SecurityContextHolder.clearContext();
		}
	}

	/** A purpose change is audited too (Q23); setting the purpose a pipeline already has writes nothing. */
	@Test
	void settingPurposeIsAuditedAndANoOpIsNot() {
		UUID gm = signInAsGm();
		try {
			Pipeline leads = held("p-1", "Leads");
			given(pipelines.findById(leads.getId())).willReturn(Optional.of(leads));

			assertThat(mirror.setPurpose(leads.getId(), PipelinePurpose.SALES).getPurpose()).isEqualTo(PipelinePurpose.SALES);
			mirror.setPurpose(leads.getId(), PipelinePurpose.SALES);

			org.mockito.BDDMockito.then(audit).should().recordEvent("PIPELINE", leads.getId(),
					com.ie.evalos.domain.AuditAction.UPDATED, gm, java.util.Map.of("purpose", "UNASSIGNED"),
					java.util.Map.of("purpose", "SALES"));
			org.mockito.BDDMockito.then(audit).shouldHaveNoMoreInteractions();
		}
		finally {
			org.springframework.security.core.context.SecurityContextHolder.clearContext();
		}
	}

	/** Brand-scoped: a stage that is not the selling brand's is "no such stage", never flipped. */
	@Test
	void anotherBrandsStageCannotBeMarkedWon() {
		PipelineStage theirs = withId(new PipelineStage(UUID.randomUUID(), UUID.randomUUID(), "x", "Won", 0));
		given(stages.findById(theirs.getId())).willReturn(Optional.of(theirs));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> mirror.setWon(theirs.getId(), false))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class);
		assertThat(theirs.isWon()).isTrue();
	}

	/** One Master Pipeline per brand: a second is refused with a sentence naming the first, and nothing is saved. */
	@Test
	void aSecondMasterPipelineIsRefused() {
		Pipeline master = held("p-1", "Master Pipeline");
		master.setPurpose(PipelinePurpose.MASTER);
		Pipeline other = held("p-2", "Leads");
		given(pipelines.findById(other.getId())).willReturn(Optional.of(other));
		given(pipelines.findById(master.getId())).willReturn(Optional.of(master));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> mirror.setPurpose(other.getId(), PipelinePurpose.MASTER))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class)
				.hasMessageContaining("Master Pipeline");
		assertThat(other.getPurpose()).isEqualTo(PipelinePurpose.UNASSIGNED);

		// Re-saving the one that already holds it is not a second one.
		assertThat(mirror.setPurpose(master.getId(), PipelinePurpose.MASTER).getPurpose())
				.isEqualTo(PipelinePurpose.MASTER);
	}

}
