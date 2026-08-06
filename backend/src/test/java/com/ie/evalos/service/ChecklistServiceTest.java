package com.ie.evalos.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.security.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit 10's acceptance criteria below the endpoints: the board is the caller's
 * {@code DOC_COLLECTION} cases with the right completeness and the right order, every write
 * leaves exactly one audit row, a chase emits {@code checklist.reminder} and nothing else,
 * and an item on somebody else's case is not reachable.
 *
 * <p>Repositories are mocked, as in {@code CaseLifecycleServiceTest} — the scope predicates
 * themselves are proved in {@code ScopePredicateTest} and against real SQL in
 * {@code LocalPostgresIntegrationTest}.
 */
class ChecklistServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID TEAM = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final UUID ITEM_ID = UUID.randomUUID();

	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final CaseBoardService board = mock(CaseBoardService.class);
	private final DocumentChecklistItemRepository checklistItems = mock(DocumentChecklistItemRepository.class);
	private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private final ChecklistService checklists = new ChecklistService(
			lifecycle, board, checklistItems, auditEvents, audit, events);

	private Case subject;

	@BeforeEach
	void aCaseCollectingDocuments() {
		actAs(Role.PROJECT_COORDINATOR);
		subject = aCase(Stage.DOC_COLLECTION, Instant.now().minus(6, ChronoUnit.HOURS));
		given(lifecycle.read(any())).willReturn(subject);
		given(checklistItems.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(auditEvents.findCaseActionScoped(any(), any(AuditAction.class), anyList(), anyList()))
				.willReturn(List.of());
	}

	@AfterEach
	void clearCaller() {
		SecurityContextHolder.clearContext();
	}

	private void actAs(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), "staff@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND, TEAM, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	private static Case aCase(Stage stage, Instant enteredAt) {
		Case value = mock(Case.class);
		given(value.getId()).willReturn(UUID.randomUUID());
		given(value.getBrandId()).willReturn(BRAND);
		given(value.getCurrentStage()).willReturn(stage);
		given(value.getStageEnteredAt()).willReturn(enteredAt);
		return value;
	}

	private static DocumentChecklistItem item(UUID caseId, ChecklistItemStatus status) {
		DocumentChecklistItem value = mock(DocumentChecklistItem.class);
		given(value.getCaseId()).willReturn(caseId);
		given(value.getStatus()).willReturn(status);
		return value;
	}

	private static AuditEvent chaseRow(UUID caseId, Instant at) {
		AuditEvent row = mock(AuditEvent.class);
		given(row.getObjectId()).willReturn(caseId);
		given(row.getCreatedAt()).willReturn(at);
		return row;
	}

	/** The note the audit row carried, which is where a checklist change is described. */
	private String recordedNote(AuditAction action) {
		ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);
		verify(audit).recordEvent(eq("CASE"), any(), eq(action), any(), any(), after.capture());
		return ((CaseLifecycleService.CaseSnapshot) after.getValue()).note();
	}

	// --- the board -----------------------------------------------------------

	@Test
	void theBoardIsOnlyTheCasesStillCollectingDocuments() {
		// Every mock is built before a single stubbing starts: one created inside a
		// willReturn(...) argument leaves the outer stubbing unfinished, which is the trap
		// CaseLifecycleServiceTest records.
		Case waiting = aCase(Stage.DOC_COLLECTION, Instant.now().minus(2, ChronoUnit.HOURS));
		Case drafting = aCase(Stage.DRAFT_GENERATION, Instant.now());
		List<DocumentChecklistItem> items = List.of(
				item(waiting.getId(), ChecklistItemStatus.APPROVED),
				item(waiting.getId(), ChecklistItemStatus.REQUIRED),
				// A case that is not on the board must not contribute counts to one that is.
				item(drafting.getId(), ChecklistItemStatus.APPROVED));

		given(board.forCaller(null, null)).willReturn(List.of(
				new CaseBoardService.BoardRow(waiting, "Anita Rao"),
				new CaseBoardService.BoardRow(drafting, "Ben Cole")));
		given(checklistItems.findByBrandIdInAndCaseIdIn(anyList(), anyList())).willReturn(items);

		List<ChecklistService.BoardRow> rows = checklists.board(null);

		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().clientName()).isEqualTo("Anita Rao");
		assertThat(rows.getFirst().total()).isEqualTo(2);
		assertThat(rows.getFirst().complete()).isEqualTo(1);
		assertThat(rows.getFirst().satisfied()).isFalse();
	}

	/**
	 * Longest wait first. The whole column shares one SLA budget, so the deadline would
	 * mostly re-sort by service type — time in the stage is what the Coordinator triages on.
	 */
	@Test
	void theBoardPutsTheLongestWaitFirstAndTheUnstampedCaseLast() {
		Case fresh = aCase(Stage.DOC_COLLECTION, Instant.now().minus(1, ChronoUnit.HOURS));
		Case stale = aCase(Stage.DOC_COLLECTION, Instant.now().minus(3, ChronoUnit.DAYS));
		Case unstamped = aCase(Stage.DOC_COLLECTION, null);
		List<CaseBoardService.BoardRow> scoped = List.of(
				new CaseBoardService.BoardRow(fresh, "Fresh"),
				new CaseBoardService.BoardRow(unstamped, "Unstamped"),
				new CaseBoardService.BoardRow(stale, "Stale"));

		given(board.forCaller(null, null)).willReturn(scoped);
		given(checklistItems.findByBrandIdInAndCaseIdIn(anyList(), anyList())).willReturn(List.of());

		assertThat(checklists.board(null))
				.extracting(ChecklistService.BoardRow::clientName)
				.containsExactly("Stale", "Fresh", "Unstamped");
	}

	/**
	 * "Last chased" is derived from the append-only trail, not stored on the case. Several
	 * chases on one case collapse to the most recent, which is the only one the board shows.
	 */
	@Test
	void lastChasedComesFromTheAuditTrailAndIsTheMostRecentOne() {
		Case waiting = aCase(Stage.DOC_COLLECTION, Instant.now().minus(2, ChronoUnit.DAYS));
		Instant older = Instant.now().minus(2, ChronoUnit.DAYS);
		Instant newest = Instant.now().minus(4, ChronoUnit.HOURS);
		List<CaseBoardService.BoardRow> scoped = List.of(new CaseBoardService.BoardRow(waiting, "Anita Rao"));
		List<AuditEvent> chases = List.of(chaseRow(waiting.getId(), older), chaseRow(waiting.getId(), newest));

		given(board.forCaller(null, null)).willReturn(scoped);
		given(checklistItems.findByBrandIdInAndCaseIdIn(anyList(), anyList())).willReturn(List.of());
		given(auditEvents.findCaseActionScoped(eq("CASE"), eq(AuditAction.CHASED), anyList(), anyList()))
				.willReturn(chases);

		assertThat(checklists.board(null).getFirst().lastChasedAt()).isEqualTo(newest);
	}

	/** The GM's brand switcher rides on {@code CaseBoardService}, which applies it after scope. */
	@Test
	void theBrandFilterIsPassedStraightThroughToTheScopedRead() {
		UUID otherBrand = UUID.randomUUID();
		given(board.forCaller(null, otherBrand)).willReturn(List.of());

		assertThat(checklists.board(otherBrand)).isEmpty();
		verify(board).forCaller(null, otherBrand);
	}

	// --- one case's items ----------------------------------------------------

	@Test
	void settingAnItemStatusPersistsItAndDescribesTheChangeInTheTrail() {
		DocumentChecklistItem target = item(subject.getId(), ChecklistItemStatus.REQUIRED);
		given(target.getLabel()).willReturn("Passport or government photo ID");
		given(checklistItems.findScoped(any(TenantContext.class), eq(ITEM_ID))).willReturn(Optional.of(target));

		checklists.setStatus(CASE_ID, ITEM_ID, ChecklistItemStatus.UPLOADED);

		verify(target).markStatus(ChecklistItemStatus.UPLOADED);
		verify(checklistItems).save(target);
		assertThat(recordedNote(AuditAction.UPDATED))
				.isEqualTo("Passport or government photo ID: REQUIRED → UPLOADED");
	}

	/**
	 * Two guards, one answer. An item id that resolves in the caller's brand but hangs off a
	 * different case is refused exactly as another brand's item is — otherwise a Coordinator
	 * could edit any case in their brand by pasting an item id.
	 */
	@Test
	void anItemOnAnotherCaseIsNotReachable() {
		DocumentChecklistItem elsewhere = item(UUID.randomUUID(), ChecklistItemStatus.REQUIRED);
		given(checklistItems.findScoped(any(TenantContext.class), eq(ITEM_ID))).willReturn(Optional.of(elsewhere));

		assertThrows(ForbiddenException.class,
				() -> checklists.setStatus(CASE_ID, ITEM_ID, ChecklistItemStatus.APPROVED));
		verify(checklistItems, never()).save(any());
	}

	@Test
	void anItemInAnotherBrandIsNotReachable() {
		given(checklistItems.findScoped(any(TenantContext.class), eq(ITEM_ID))).willReturn(Optional.empty());

		assertThrows(ForbiddenException.class,
				() -> checklists.setStatus(CASE_ID, ITEM_ID, ChecklistItemStatus.APPROVED));
	}

	/**
	 * Acceptance criterion 2: adding a required item makes the case incomplete again. No rule
	 * enforces that — the new row opens as REQUIRED and {@code markDocsComplete} already
	 * refuses any item that is not uploaded or approved.
	 */
	@Test
	void anAddedItemOpensAsRequiredAndUnsatisfiesTheChecklist() {
		ArgumentCaptor<DocumentChecklistItem> saved = ArgumentCaptor.forClass(DocumentChecklistItem.class);

		checklists.addItem(CASE_ID, "Marriage certificate");

		verify(checklistItems).save(saved.capture());
		assertThat(saved.getValue().getStatus()).isEqualTo(ChecklistItemStatus.REQUIRED);
		assertThat(saved.getValue().getLabel()).isEqualTo("Marriage certificate");
		assertThat(saved.getValue().getCaseId()).isEqualTo(subject.getId());
		assertThat(recordedNote(AuditAction.CREATED)).isEqualTo("Required document added: Marriage certificate");

		// And the checklist it lands in is no longer satisfied, even though the rest is in.
		List<DocumentChecklistItem> reopened = List.of(
				item(subject.getId(), ChecklistItemStatus.APPROVED),
				item(subject.getId(), ChecklistItemStatus.REQUIRED));
		given(checklistItems.findByCaseId(subject.getId())).willReturn(reopened);
		assertThat(checklists.forCase(CASE_ID).satisfied()).isFalse();
	}

	/** The same answer {@code markDocsComplete} gives: no items is not "nothing outstanding". */
	@Test
	void anEmptyChecklistIsNotSatisfied() {
		given(checklistItems.findByCaseId(subject.getId())).willReturn(List.of());

		assertThat(checklists.forCase(CASE_ID).satisfied()).isFalse();
		assertThat(checklists.forCase(CASE_ID).complete()).isZero();
	}

	// --- the chase -----------------------------------------------------------

	/**
	 * Acceptance criterion 3. The chase is an event for GHL to deliver and an audit row —
	 * EvalOS sends no mail (invariant 14), so there is nothing else to assert happened.
	 */
	@Test
	void aChaseEmitsTheReminderEventAndRecordsIt() {
		checklists.chase(CASE_ID);

		ArgumentCaptor<CaseEvents.CaseEvent> published = ArgumentCaptor.forClass(CaseEvents.CaseEvent.class);
		verify(events).publishEvent(published.capture());
		assertThat(published.getValue().type()).isEqualTo(CaseEvents.Type.CHECKLIST_REMINDER);
		assertThat(published.getValue().type().wireName()).isEqualTo("checklist.reminder");
		assertThat(recordedNote(AuditAction.CHASED)).isEqualTo("Document chase sent to the client");
	}

	/**
	 * A chase reaches a real client through GHL, so "please send your documents" to somebody
	 * whose case is already with the expert is a mistake made outwardly, not internally.
	 */
	@Test
	void aCaseThatHasLeftDocumentCollectionCannotBeChased() {
		Case signing = aCase(Stage.EXPERT_SIGNING, Instant.now());
		given(lifecycle.read(any())).willReturn(signing);

		assertThrows(IllegalTransitionException.class, () -> checklists.chase(CASE_ID));
		verify(events, never()).publishEvent(any(CaseEvents.CaseEvent.class));
		verify(audit, never()).recordEvent(any(), any(), any(), any(), any(), any());
	}

	/** Nothing in this service moves a case — {@code docs-complete} is Unit 04's transition. */
	@Test
	void noChecklistWriteMovesTheCase() {
		DocumentChecklistItem target = item(subject.getId(), ChecklistItemStatus.REQUIRED);
		given(target.getLabel()).willReturn("Official transcripts / mark sheets");
		given(checklistItems.findScoped(any(TenantContext.class), eq(ITEM_ID))).willReturn(Optional.of(target));

		checklists.setStatus(CASE_ID, ITEM_ID, ChecklistItemStatus.APPROVED);
		checklists.addItem(CASE_ID, "Employment verification letters");
		checklists.chase(CASE_ID);

		verify(subject, never()).setCurrentStage(any());
		verify(subject, never()).setStageEnteredAt(any());
	}
}
