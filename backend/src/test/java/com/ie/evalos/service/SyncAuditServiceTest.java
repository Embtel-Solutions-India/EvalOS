package com.ie.evalos.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.SyncDrift;
import com.ie.evalos.domain.SyncEntity;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.repository.OpportunityRepository;
import com.ie.evalos.repository.PipelineRepository;
import com.ie.evalos.repository.SyncDriftRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The audit's four honest answers, and the two things it must never call drift.
 *
 * <p>A detector that cries wolf is one nobody reads, which makes it worse than none at all — the
 * whole table then reads as "no drift". So the false positives are pinned as hard as the true ones:
 * a portal-born row GHL has never seen is <strong>not</strong> drift ({@code 00c} §2a says it is a
 * legal state), and {@code 1000} against {@code 1000.00} is the same money.
 */
class SyncAuditServiceTest {

	private static final UUID BRAND = UUID.randomUUID();

	private final GhlPipelineClient ghl = mock(GhlPipelineClient.class);

	private final OpportunityRepository opportunities = mock(OpportunityRepository.class);

	private final PipelineRepository pipelines = mock(PipelineRepository.class);

	private final SyncDriftRepository drifts = mock(SyncDriftRepository.class);

	private final SyncAuditService audit =
			new SyncAuditService(ghl, opportunities, pipelines, drifts, BRAND.toString());

	private final List<SyncDrift> saved = new ArrayList<>();

	private Pipeline pipeline;

	@BeforeEach
	void mirroredPipeline() {
		pipeline = new Pipeline(BRAND, "pipe-1", "Sales", 0);
		ReflectionTestUtils.setField(pipeline, "id", UUID.randomUUID());
		given(pipelines.findByBrandIdOrderByPositionAscNameAsc(BRAND)).willReturn(List.of(pipeline));
		given(drifts.findByBrandIdAndEntityTypeAndResolvedAtIsNull(BRAND, SyncEntity.OPPORTUNITY))
				.willReturn(List.of());
		given(drifts.findByBrandIdAndEntityTypeAndGhlIdAndFieldAndResolvedAtIsNull(any(), any(),
				anyString(), anyString())).willReturn(Optional.empty());
		given(drifts.findByBrandIdAndEntityTypeAndGhlIdAndFieldIsNullAndResolvedAtIsNull(any(), any(),
				anyString())).willReturn(Optional.empty());
		given(drifts.save(any())).willAnswer((call) -> {
			saved.add(call.getArgument(0));
			return call.getArgument(0);
		});
	}

	private Opportunity mirrored(String ghlId, String stage, String status, String amount, String name) {
		Opportunity row = new Opportunity(BRAND, ghlId, pipeline.getId());
		ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
		row.syncFromGhl("contact-1", pipeline.getId(), stage, name,
				amount == null ? null : new BigDecimal(amount), status, null, null, null, null, null, null);
		return row;
	}

	private static GhlPipelineClient.Opportunity fromGhl(String id, String stage, String status,
			String amount, String name) {
		return new GhlPipelineClient.Opportunity(id, name, "contact-1", "pipe-1", stage, status,
				amount == null ? null : new BigDecimal(amount), null, null, null, null, null, null);
	}

	private SyncAuditService.AuditResult run(List<Opportunity> held,
			Map<String, GhlPipelineClient.Opportunity> remote) {
		given(opportunities.findByPipelineIdIn(any())).willReturn(held);
		return audit.compare(List.of(pipeline), remote);
	}

	/** GHL has a deal EvalOS has never seen. The mirror is behind, or a sync was lost. */
	@Test
	void aDealOnlyGhlHasIsRecordedAsMissingLocally() {
		var result = run(List.of(), Map.of("opp-1", fromGhl("opp-1", "s1", "open", "100", "Acme")));

		assertThat(result.opened()).isEqualTo(1);
		assertThat(saved).singleElement().satisfies((row) -> {
			assertThat(row.getKind()).isEqualTo(SyncDrift.Kind.MISSING_LOCALLY);
			assertThat(row.getGhlId()).isEqualTo("opp-1");
		});
	}

	/** EvalOS holds a deal with a GHL id that GHL no longer returns. */
	@Test
	void aDealOnlyEvalosHasIsRecordedAsMissingInGhl() {
		var result = run(List.of(mirrored("opp-1", "s1", "open", "100", "Acme")), Map.of());

		assertThat(result.opened()).isEqualTo(1);
		assertThat(saved).singleElement()
				.satisfies((row) -> assertThat(row.getKind()).isEqualTo(SyncDrift.Kind.MISSING_IN_GHL));
	}

	/**
	 * <strong>A portal-born row is not drift, and this is the assertion that keeps the report
	 * readable.</strong>
	 *
	 * <p>{@code 00c} §2a: a deal EvalOS opened exists <em>before GHL has seen it</em> — that is the
	 * whole reason the row has two names. Recording every one of them as MISSING_IN_GHL would fill
	 * the report with rows nobody should act on, on the busiest day the portal has, and a report
	 * full of those is one that stops being read.
	 */
	@Test
	void aPortalBornRowWithNoGhlIdIsNotDrift() {
		Opportunity local = new Opportunity(BRAND, null, pipeline.getId());
		ReflectionTestUtils.setField(local, "id", UUID.randomUUID());

		var result = run(List.of(local), Map.of());

		assertThat(result.opened()).isZero();
		assertThat(saved).isEmpty();
	}

	/** Each disagreeing field is its own finding, so a GM sees what to look at. */
	@Test
	void eachMismatchedFieldIsItsOwnRow() {
		var result = run(List.of(mirrored("opp-1", "s1", "open", "100", "Acme")),
				Map.of("opp-1", fromGhl("opp-1", "s2", "won", "100", "Acme")));

		assertThat(result.opened()).isEqualTo(2);
		assertThat(saved).extracting(SyncDrift::getField).containsExactlyInAnyOrder("ghlStageId", "status");
		// Both sides travel: a row naming a field without saying what each held is one somebody has
		// to investigate by hand.
		assertThat(saved).allSatisfy((row) -> {
			assertThat(row.getLocalValue()).isNotNull();
			assertThat(row.getGhlValue()).isNotNull();
		});
	}

	/**
	 * <strong>{@code 1000} and {@code 1000.00} are the same money.</strong>
	 *
	 * <p>Compared by value, not by string. A report that called this drift would be wrong every
	 * single night, which is precisely how a report stops being read.
	 */
	@Test
	void anEquivalentAmountWrittenDifferentlyIsNotDrift() {
		var result = run(List.of(mirrored("opp-1", "s1", "open", "1000.00", "Acme")),
				Map.of("opp-1", fromGhl("opp-1", "s1", "open", "1000", "Acme")));

		assertThat(result.opened()).isZero();
		assertThat(saved).isEmpty();
	}

	/**
	 * A drift that is still true is one fact, not one per night.
	 *
	 * <p>A row per audit run would bury the new findings under the old ones — the noise that makes a
	 * report get ignored. The values are refreshed with the sighting, because a mismatch whose two
	 * sides have both moved on is still the same disagreement.
	 */
	@Test
	void aDriftSeenAgainIsUpdatedRatherThanDuplicated() {
		SyncDrift existing = new SyncDrift(BRAND, SyncEntity.OPPORTUNITY, UUID.randomUUID(), "opp-1",
				SyncDrift.Kind.FIELD_MISMATCH, "status", "open", "won");
		given(drifts.findByBrandIdAndEntityTypeAndResolvedAtIsNull(BRAND, SyncEntity.OPPORTUNITY))
				.willReturn(List.of(existing));
		given(drifts.findByBrandIdAndEntityTypeAndGhlIdAndFieldAndResolvedAtIsNull(BRAND,
				SyncEntity.OPPORTUNITY, "opp-1", "status")).willReturn(Optional.of(existing));

		var result = run(List.of(mirrored("opp-1", "s1", "open", "100", "Acme")),
				Map.of("opp-1", fromGhl("opp-1", "s1", "lost", "100", "Acme")));

		assertThat(result.opened()).isZero();
		assertThat(result.stillWrong()).isEqualTo(1);
		assertThat(existing.getGhlValue()).isEqualTo("lost");
		assertThat(existing.isOpen()).isTrue();
	}

	/**
	 * An open drift the audit no longer sees has stopped being true — and the row stays.
	 *
	 * <p>"This drifted and then stopped" is the history {@code 00d} §6.3 asks to keep; deleting it
	 * would make the table unable to say whether a fix worked.
	 */
	@Test
	void aDriftThatHasStoppedIsResolvedRatherThanDeleted() {
		SyncDrift existing = new SyncDrift(BRAND, SyncEntity.OPPORTUNITY, UUID.randomUUID(), "opp-1",
				SyncDrift.Kind.FIELD_MISMATCH, "status", "open", "won");
		given(drifts.findByBrandIdAndEntityTypeAndResolvedAtIsNull(BRAND, SyncEntity.OPPORTUNITY))
				.willReturn(List.of(existing));

		var result = run(List.of(mirrored("opp-1", "s1", "open", "100", "Acme")),
				Map.of("opp-1", fromGhl("opp-1", "s1", "open", "100", "Acme")));

		assertThat(result.resolved()).isEqualTo(1);
		assertThat(existing.getResolvedAt()).isNotNull();
	}

	/** No selling brand means no mirror to audit, and GHL is not even asked. */
	@Test
	void aBlankSellingBrandAuditsNothing() {
		SyncAuditService unconfigured =
				new SyncAuditService(ghl, opportunities, pipelines, drifts, "");

		assertThat(unconfigured.audit().compared()).isZero();
		org.mockito.BDDMockito.then(ghl).shouldHaveNoInteractions();
	}

	/**
	 * <strong>The paged full list, never a per-row {@code GET}.</strong>
	 *
	 * <p>{@code 00d} §6.3 asks for this to be said out loud, because the per-row version "is the one
	 * somebody writes first, because it is easier to reason about" — and it costs 11.4k requests,
	 * about 21 minutes of continuous budget, starving every desk while it runs.
	 */
	@Test
	void theAuditReadsOnePagedListPerPipelineAndNeverOneRowAtATime() {
		given(ghl.allIn("pipe-1")).willReturn(List.of(fromGhl("opp-1", "s1", "open", "100", "Acme")));
		given(opportunities.findByPipelineIdIn(any())).willReturn(List.of());

		audit.audit();

		org.mockito.BDDMockito.then(ghl).should().allIn("pipe-1");
		org.mockito.BDDMockito.then(ghl).should(org.mockito.Mockito.never())
				.opportunitiesIn(any(), any(), any());
		org.mockito.BDDMockito.then(ghl).should(org.mockito.Mockito.never())
				.opportunitiesIn(any(), any(), any(), any());
	}

}
