package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.service.CoordinatorWorkService.CoordinatorWork;
import com.ie.evalos.service.PmOverviewService.StageCount;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** What the Coordinator still owes and holds, at the places a plausible number would be the wrong one. */
class CoordinatorWorkServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID SARAH = UUID.randomUUID();

	private final CoordinatorMetricsService coordinator = mock(CoordinatorMetricsService.class);
	private final DocumentChecklistItemRepository items = mock(DocumentChecklistItemRepository.class);
	private final AuditEventRepository audit = mock(AuditEventRepository.class);
	private final TeamMemberQueryService members = mock(TeamMemberQueryService.class);
	private final BusinessCalendar calendar = new BusinessCalendar();
	private final CoordinatorWorkService service = new CoordinatorWorkService(coordinator, items, audit, members,
			new DeadlineRiskCalculator(calendar), calendar);

	/** A fresh Case has no id until persisted, and everything here is keyed by it. */
	private static Case caseAt(Stage stage) {
		Case subject = new Case(BRAND, "C-" + UUID.randomUUID().toString().substring(0, 6), stage);
		ReflectionTestUtils.setField(subject, "id", UUID.randomUUID());
		return subject;
	}

	private static Case set(Case subject, String field, Object value) {
		ReflectionTestUtils.setField(subject, field, value);
		return subject;
	}

	private static DocumentChecklistItem item(Case of, ChecklistItemStatus status) {
		DocumentChecklistItem row = mock(DocumentChecklistItem.class);
		given(row.getCaseId()).willReturn(of.getId());
		given(row.getStatus()).willReturn(status);
		return row;
	}

	private static AuditEvent chase(Case of, Instant at) {
		AuditEvent event = mock(AuditEvent.class);
		given(event.getObjectId()).willReturn(of.getId());
		given(event.getCreatedAt()).willReturn(at);
		return event;
	}

	private CoordinatorWork run(List<Case> scoped, List<DocumentChecklistItem> itemRows, Set<UUID> unsent,
			List<AuditEvent> chases) {
		given(coordinator.scoped(any())).willReturn(scoped);
		given(items.findByBrandIdInAndCaseIdIn(anyCollection(), anyCollection())).willReturn(itemRows);
		given(items.caseIdsWithUnsent(anyCollection())).willReturn(unsent);
		given(audit.findCaseActionScoped(any(String.class), any(AuditAction.class), anyCollection(), anyCollection()))
				.willReturn(chases);
		given(members.assignable(any(Role.class))).willReturn(List.<TeamMember>of());
		return service.forCaller(null);
	}

	private static StageCount stage(CoordinatorWork work, Stage which) {
		return work.stages().stream().filter(row -> row.stage() == which).findFirst().orElseThrow();
	}

	@Test
	void noOpenCasesIsZeroFilledAndTouchesNoRepository() {
		given(coordinator.scoped(any())).willReturn(List.of(caseAt(Stage.DELIVERED)));
		CoordinatorWork work = service.forCaller(null);
		assertThat(work.stages()).hasSize(4).allMatch(row -> row.count() == 0);
		assertThat(work.documents().owed()).isEmpty();
		assertThat(work.documents().blockerItems()).isZero();
		verify(items, never()).findByBrandIdInAndCaseIdIn(any(), any());
		verify(audit, never()).findCaseActionScoped(any(String.class), any(AuditAction.class), any(), any());
	}

	@Test
	void stagesAreTheFourCoordinatorStagesAndAgeIsNullWithoutAStageEntry() {
		CoordinatorWork work = run(List.of(caseAt(Stage.DOC_COLLECTION), caseAt(Stage.CLIENT_REVIEW),
				caseAt(Stage.DRAFT_REVIEW)), List.of(), Set.of(), List.of());
		assertThat(work.stages()).extracting(StageCount::stage).containsExactly(Stage.DOC_COLLECTION,
				Stage.READY_TO_SEND, Stage.CLIENT_REVIEW, Stage.READY_TO_DELIVER);
		assertThat(stage(work, Stage.DOC_COLLECTION).count()).isEqualTo(1);
		assertThat(stage(work, Stage.DOC_COLLECTION).medianAgeBusinessHours()).isNull();
	}

	@Test
	void itemTotalsCountItemsNotCasesAndFullyApprovedCasesAreNotOwed() {
		Case owing = caseAt(Stage.DOC_COLLECTION);
		Case done = caseAt(Stage.DOC_COLLECTION);
		CoordinatorWork work = run(List.of(owing, done), List.of(
				item(owing, ChecklistItemStatus.UPLOADED), item(owing, ChecklistItemStatus.MISSING),
				item(owing, ChecklistItemStatus.INCORRECT), item(owing, ChecklistItemStatus.APPROVED),
				item(done, ChecklistItemStatus.APPROVED)), Set.of(), List.of());
		assertThat(work.documents().awaitingVerification()).isEqualTo(1);
		assertThat(work.documents().blockerItems()).isEqualTo(2);
		assertThat(work.documents().owed()).hasSize(1);
		var row = work.documents().owed().get(0);
		assertThat(row.caseId()).isEqualTo(owing.getId());
		assertThat(row.total()).isEqualTo(4);
		assertThat(row.unsent()).isFalse();
	}

	@Test
	void anUnsentChecklistIsOwedEvenWhenEveryItemIsApprovedAndIsCountedOnceAsACase() {
		Case unsentCase = caseAt(Stage.DOC_COLLECTION);
		CoordinatorWork work = run(List.of(unsentCase), List.of(item(unsentCase, ChecklistItemStatus.APPROVED)),
				Set.of(unsentCase.getId(), UUID.randomUUID()), List.of());
		assertThat(work.documents().unsentCases()).isEqualTo(1);
		assertThat(work.documents().owed()).hasSize(1);
		assertThat(work.documents().owed().get(0).unsent()).isTrue();
	}

	@Test
	void worstCaseFirstAndLastChasedIsTheNewestChase() {
		Case clean = caseAt(Stage.DOC_COLLECTION);
		Case bad = caseAt(Stage.DOC_COLLECTION);
		Instant older = Instant.now().minusSeconds(7200);
		Instant newer = Instant.now().minusSeconds(60);
		CoordinatorWork work = run(List.of(clean, bad), List.of(
				item(clean, ChecklistItemStatus.REQUIRED),
				item(bad, ChecklistItemStatus.MISSING), item(bad, ChecklistItemStatus.INCORRECT)),
				Set.of(), List.of(chase(bad, older), chase(bad, newer)));
		assertThat(work.documents().owed().get(0).caseId()).isEqualTo(bad.getId());
		assertThat(work.documents().owed().get(0).lastChasedAt()).isEqualTo(newer);
		assertThat(work.documents().owed().get(1).lastChasedAt()).isNull();
	}

	@Test
	void blockedCountsOpenCasesWithAnExceptionState() {
		CoordinatorWork work = run(List.of(
				set(caseAt(Stage.DOC_COLLECTION), "exceptionState", ExceptionState.ON_HOLD_AWAITING_CLIENT),
				caseAt(Stage.DOC_COLLECTION)), List.of(), Set.of(), List.of());
		assertThat(work.blocked()).isEqualTo(1);
	}

	@Test
	void listsAreLongestWaitFirstAndNameAFormerCaseManager() {
		Case older = set(set(caseAt(Stage.CLIENT_REVIEW), "stageEnteredAt", Instant.now().minusSeconds(86400 * 3)),
				"assignedCm", SARAH);
		Case newer = set(caseAt(Stage.CLIENT_REVIEW), "stageEnteredAt", Instant.now().minusSeconds(3600));
		Case ready = caseAt(Stage.READY_TO_DELIVER);
		CoordinatorWork work = run(List.of(newer, older, ready), List.of(), Set.of(), List.of());
		assertThat(work.clientReview()).hasSize(2);
		assertThat(work.clientReview().get(0).caseId()).isEqualTo(older.getId());
		// SARAH is not on the (empty) roster: the row stays, named for what it is.
		assertThat(work.clientReview().get(0).ownerName()).isEqualTo("Former team member");
		assertThat(work.readyToDeliver()).hasSize(1);
	}

	@Test
	void onlyOpenCaseIdsAreRequested() {
		Case open = caseAt(Stage.DOC_COLLECTION);
		Case delivered = caseAt(Stage.DELIVERED);
		run(List.of(open, delivered), List.of(), Set.of(), List.of());
		verify(items).findByBrandIdInAndCaseIdIn(Set.of(BRAND), Set.of(open.getId()));
	}
}
