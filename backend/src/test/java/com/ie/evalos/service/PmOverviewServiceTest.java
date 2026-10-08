package com.ie.evalos.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.service.PmOverviewService.PmOverview;
import com.ie.evalos.service.PmOverviewService.StageCount;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** The PM overview, at the places a plausible number would be the wrong one. */
class PmOverviewServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID SARAH = UUID.randomUUID();

	private final PmMetricsService pm = mock(PmMetricsService.class);
	private final TeamMemberQueryService members = mock(TeamMemberQueryService.class);
	private final BusinessCalendar calendar = new BusinessCalendar();
	private final PmOverviewService overview = new PmOverviewService(pm, members,
			new DeadlineRiskCalculator(calendar), calendar);

	private static Instant pt(int month, int day, int hour) {
		return LocalDateTime.of(2026, month, day, hour, 0).atZone(BusinessCalendar.ZONE).toInstant();
	}

	/** The id is generated at persist, so a fresh Case has none; the queue is keyed by it. */
	private static Case caseAt(Stage stage) {
		Case subject = new Case(BRAND, "C-" + UUID.randomUUID().toString().substring(0, 6), stage);
		return set(subject, "id", UUID.randomUUID());
	}

	private static Case set(Case subject, String field, Object value) {
		ReflectionTestUtils.setField(subject, field, value);
		return subject;
	}

	private PmOverview run(List<Case> cases) {
		given(pm.scoped(any())).willReturn(cases);
		given(members.assignable(any(Role.class))).willReturn(List.<TeamMember>of());
		return overview.forCaller(pt(7, 1, 0), pt(7, 8, 0), null);
	}

	private static StageCount stage(PmOverview result, Stage which) {
		return result.stages().stream().filter(row -> row.stage() == which).findFirst().orElseThrow();
	}

	@Test
	void emptyScopeIsZeroFilledNotNull() {
		PmOverview result = run(List.of());
		assertThat(result.stages()).hasSize(11).allMatch(row -> row.count() == 0);
		assertThat(result.active()).isZero();
		assertThat(result.throughput()).hasSize(7).allMatch(point -> point.delivered() == 0);
		assertThat(result.queues().draftReview()).isEmpty();
	}

	@Test
	void stageCountsSumToActivePlusDelivered() {
		PmOverview result = run(List.of(caseAt(Stage.DOC_COLLECTION), caseAt(Stage.DRAFT_REVIEW),
				caseAt(Stage.FINAL_QC), caseAt(Stage.CLOSED),
				set(caseAt(Stage.DELIVERED), "deliveryDate", pt(7, 3, 12))));
		assertThat(result.active()).isEqualTo(3);
		assertThat(result.stages().stream().mapToInt(StageCount::count).sum()).isEqualTo(4);
		assertThat(result.awaitingReview()).isEqualTo(1);
		assertThat(result.awaitingQc()).isEqualTo(1);
	}

	@Test
	void blockedIsAnyExceptionStateOnAnOpenCase() {
		PmOverview result = run(List.of(
				set(caseAt(Stage.DOC_COLLECTION), "exceptionState", ExceptionState.ON_HOLD_AWAITING_CLIENT),
				set(caseAt(Stage.DRAFT_REVIEW), "exceptionState", ExceptionState.NONE)));
		assertThat(result.blocked()).isEqualTo(1);
	}

	@Test
	void caseWithoutStageEnteredAtIsCountedButHasNoAge() {
		PmOverview result = run(List.of(caseAt(Stage.FINAL_QC)));
		StageCount qc = stage(result, Stage.FINAL_QC);
		assertThat(qc.count()).isEqualTo(1);
		assertThat(qc.medianAgeBusinessHours()).isNull();
	}

	@Test
	void queueIsLongestWaitingFirstAndNamesFormerMembers() {
		Case older = set(set(caseAt(Stage.DRAFT_REVIEW), "stageEnteredAt", Instant.now().minusSeconds(86400 * 3)),
				"assignedCm", SARAH);
		Case newer = set(caseAt(Stage.DRAFT_REVIEW), "stageEnteredAt", Instant.now().minusSeconds(3600));
		PmOverview result = run(List.of(newer, older));
		assertThat(result.queues().draftReview()).hasSize(2);
		assertThat(result.queues().draftReview().get(0).caseId()).isEqualTo(older.getId());
		// SARAH is not on the (empty) roster: dropping the row would lose a case; a name keeps it visible.
		assertThat(result.queues().draftReview().get(0).ownerName()).isEqualTo("Former team member");
	}

	@Test
	void deliveryOnTheWindowEndBelongsToTheNextWindow() {
		PmOverview result = run(List.of(set(caseAt(Stage.DELIVERED), "deliveryDate", pt(7, 8, 0)),
				set(caseAt(Stage.DELIVERED), "deliveryDate", pt(7, 1, 0))));
		assertThat(stage(result, Stage.DELIVERED).count()).isEqualTo(1);
		assertThat(result.throughput().stream().mapToInt(PmOverviewService.Throughput::delivered).sum()).isEqualTo(1);
	}
}
