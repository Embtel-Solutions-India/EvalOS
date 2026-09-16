package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.repository.OpportunityRepository;
import com.ie.evalos.repository.PipelineRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit 45d's two writes: what a webhook absorbs, and what the delta sweep refreshes.
 *
 * <p>The absence pass is the thing to hold still here. {@code absorb} marks a row GHL stopped
 * returning as missing, because it was given a pipeline's whole list; {@code absorbForContact} must
 * not, because it was given one person's deals — and a person not having a deal is not that deal
 * being gone.
 */
class OpportunityMirrorSyncTest {

	private static final UUID BRAND = UUID.randomUUID();

	private final GhlPipelineClient ghl = mock(GhlPipelineClient.class);

	private final OpportunityRepository opportunities = mock(OpportunityRepository.class);

	private final PipelineRepository pipelines = mock(PipelineRepository.class);

	private final OpportunityMirrorService mirror =
			new OpportunityMirrorService(ghl, opportunities, pipelines, BRAND.toString());

	private final List<Opportunity> saved = new ArrayList<>();

	private Pipeline sales;

	@BeforeEach
	void oneMirroredPipeline() {
		sales = pipeline("pipe-1", "Sales");
		given(pipelines.findByBrandIdAndGhlId(BRAND, "pipe-1")).willReturn(Optional.of(sales));
		given(pipelines.findByBrandIdAndGhlId(BRAND, "pipe-unmirrored")).willReturn(Optional.empty());
		given(opportunities.save(any())).willAnswer((call) -> {
			saved.add(call.getArgument(0));
			return call.getArgument(0);
		});
	}

	private Pipeline pipeline(String ghlId, String name) {
		Pipeline row = new Pipeline(BRAND, ghlId, name, 0);
		ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
		return row;
	}

	private static GhlPipelineClient.Opportunity fromGhl(String id, String pipelineId, String stageId,
			String name, String status) {
		return new GhlPipelineClient.Opportunity(id, name, "ghl-c-1", pipelineId, stageId, status,
				new BigDecimal("1450.00"), "CRM UI", "ghl-user-7", Instant.parse("2026-09-01T00:00:00Z"),
				Instant.parse("2026-09-17T00:00:00Z"), null, null);
	}

	@Test
	void aDealTheMirrorHasNeverSeenIsCreatedFromGhlsOwnFields() {
		given(opportunities.findByBrandIdAndGhlId(BRAND, "opp-1")).willReturn(Optional.empty());

		int written = mirror.absorbForContact(List.of(fromGhl("opp-1", "pipe-1", "stage-2", "Rao", "open")));

		assertThat(written).isEqualTo(1);
		assertThat(saved).singleElement().satisfies((row) -> {
			assertThat(row.getGhlId()).isEqualTo("opp-1");
			assertThat(row.getPipelineId()).isEqualTo(sales.getId());
			assertThat(row.getGhlStageId()).isEqualTo("stage-2");
			assertThat(row.getStatus()).isEqualTo("open");
		});
	}

	/**
	 * The staleness {@code isOnPipeline} names: a deal dragged onto another rep's board changes
	 * hands in the mirror at the moment GHL says so, rather than staying authorised to the old
	 * owner until something happened to re-read it.
	 */
	@Test
	void aDealThatMovedPipelineLandsOnTheOneGhlNamesNotTheOneItHeld() {
		Pipeline delivery = pipeline("pipe-2", "Case Delivery");
		given(pipelines.findByBrandIdAndGhlId(BRAND, "pipe-2")).willReturn(Optional.of(delivery));
		Opportunity held = new Opportunity(BRAND, "opp-1", sales.getId());
		given(opportunities.findByBrandIdAndGhlId(BRAND, "opp-1")).willReturn(Optional.of(held));

		mirror.absorbForContact(List.of(fromGhl("opp-1", "pipe-2", "stage-9", "Rao", "won")));

		assertThat(saved).singleElement().satisfies((row) -> {
			assertThat(row.getPipelineId()).isEqualTo(delivery.getId());
			assertThat(row.getStatus()).isEqualTo("won");
		});
	}

	/**
	 * A contact's deals are not a pipeline's whole list, so nothing here may mark a row missing —
	 * doing so would stamp every deal the contact does not happen to have.
	 */
	@Test
	void aDealMissingFromOnePersonsAnswerIsNotMarkedMissing() {
		Opportunity other = new Opportunity(BRAND, "opp-other", sales.getId());
		given(opportunities.findByBrandIdAndGhlId(BRAND, "opp-1")).willReturn(Optional.empty());

		mirror.absorbForContact(List.of(fromGhl("opp-1", "pipe-1", "stage-2", "Rao", "open")));

		assertThat(other.isLive()).isTrue();
		verify(opportunities, never()).findByPipelineIdIn(any());
	}

	/** A pipeline the 44a sweep has not mirrored yet is skipped and logged, never guessed at. */
	@Test
	void aDealOnAnUnmirroredPipelineIsSkippedRatherThanFiledSomewhere() {
		int written = mirror.absorbForContact(List.of(fromGhl("opp-1", "pipe-unmirrored", "s", "Rao", "open")));

		assertThat(written).isZero();
		assertThat(saved).isEmpty();
	}

	/** The sweep's whole promise: a pipeline inside the TTL costs no GHL call. */
	@Test
	void theDeltaSweepSkipsAPipelineADeskHasAlreadyWarmed() {
		given(pipelines.findByBrandIdOrderByPositionAscNameAsc(BRAND)).willReturn(List.of(sales));
		given(opportunities.lastSyncedFor(sales.getId())).willReturn(Instant.now());

		assertThat(mirror.refreshStale(Duration.ofMinutes(10))).isEqualTo(1);
		verify(ghl, never()).allIn(any());
	}

	@Test
	void theDeltaSweepRereadsAPipelineNobodyHasLookedAt() {
		given(pipelines.findByBrandIdOrderByPositionAscNameAsc(BRAND)).willReturn(List.of(sales));
		given(opportunities.lastSyncedFor(sales.getId()))
				.willReturn(Instant.now().minus(Duration.ofHours(2)));
		given(opportunities.findByPipelineIdIn(List.of(sales.getId()))).willReturn(List.of());
		given(ghl.allIn("pipe-1")).willReturn(List.of(fromGhl("opp-1", "pipe-1", "s", "Rao", "open")));

		mirror.refreshStale(Duration.ofMinutes(10));

		verify(ghl).allIn("pipe-1");
		assertThat(saved).hasSize(1);
	}

	/** A pipeline GHL stopped returning keeps its row and is not re-read — 44a stamps it missing. */
	@Test
	void theDeltaSweepLeavesAPipelineThatDisappearedFromGhlAlone() {
		sales.markMissing(Instant.now());
		given(pipelines.findByBrandIdOrderByPositionAscNameAsc(BRAND)).willReturn(List.of(sales));

		assertThat(mirror.refreshStale(Duration.ofMinutes(10))).isZero();
		verify(ghl, never()).allIn(any());
	}
}
