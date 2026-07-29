package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.PoolStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.NotificationRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.security.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The acceptance criteria of Unit 04 that live below the endpoints: the declared
 * path walks, an undeclared one is refused and changes nothing, every hop writes
 * exactly one audit entry and publishes exactly one event, and a GM-approved refund
 * reverses recognition and voids the pending payout.
 *
 * <p>The repositories are mocked because this machine has no Postgres — the scope
 * predicates they apply are asserted in {@code ScopePredicateTest}, and the role
 * gates at the endpoints in {@code CaseControllerTest}.
 */
class CaseLifecycleServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID TEAM = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final UUID PM_ID = UUID.randomUUID();
	private static final UUID CM_ID = UUID.randomUUID();
	private static final UUID COORDINATOR_ID = UUID.randomUUID();
	private static final UUID EXPERT_ID = UUID.randomUUID();
	private static final UUID OTHER_EXPERT_ID = UUID.randomUUID();
	private static final BigDecimal PAID = new BigDecimal("1450.00");

	private final CaseRepository cases = mock(CaseRepository.class);
	private final DocumentChecklistItemRepository checklistItems = mock(DocumentChecklistItemRepository.class);
	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
	private final PayoutLedgerRepository payouts = mock(PayoutLedgerRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private final SlaCalculator sla = new SlaCalculator(new BusinessCalendar());
	private final CaseLifecycleService lifecycle = new CaseLifecycleService(
			cases, checklistItems, experts, teamMembers, audit, sla, events);
	private final RefundService refunds = new RefundService(lifecycle, payouts);

	private Case subject;

	@BeforeEach
	void freshCaseInDocCollection() {
		subject = new Case(BRAND, "IE-2026-0001", Stage.DOC_COLLECTION);
		subject.setPoolStatus(PoolStatus.IN_POOL);
		subject.setStageEnteredAt(Instant.now());

		// Build every collaborator before stubbing anything: a mock created inside a
		// willReturn(...) argument leaves the outer stubbing unfinished.
		TeamMember pm = member(PM_ID, Role.PROJECT_MANAGER, TEAM);
		TeamMember cm = member(CM_ID, Role.CASE_MANAGER, TEAM);
		TeamMember coordinator = member(COORDINATOR_ID, Role.PROJECT_COORDINATOR, TEAM);
		Expert assigned = expert(EXPERT_ID, Availability.AVAILABLE);
		Expert replacement = expert(OTHER_EXPERT_ID, Availability.AVAILABLE);
		List<DocumentChecklistItem> completeChecklist = List.of(
				checklistItem(ChecklistItemStatus.APPROVED), checklistItem(ChecklistItemStatus.UPLOADED));

		given(cases.findScoped(any(TenantContext.class), any(UUID.class))).willReturn(Optional.of(subject));
		given(cases.save(any(Case.class))).willAnswer(invocation -> invocation.getArgument(0));
		given(teamMembers.findByIdAndBrandIdAndRole(PM_ID, BRAND, Role.PROJECT_MANAGER)).willReturn(Optional.of(pm));
		given(teamMembers.findByIdAndBrandIdAndRole(CM_ID, BRAND, Role.CASE_MANAGER)).willReturn(Optional.of(cm));
		given(teamMembers.findByIdAndBrandIdAndRole(COORDINATOR_ID, BRAND, Role.PROJECT_COORDINATOR))
				.willReturn(Optional.of(coordinator));
		given(experts.findScoped(any(TenantContext.class), eq(EXPERT_ID))).willReturn(Optional.of(assigned));
		given(experts.findScoped(any(TenantContext.class), eq(OTHER_EXPERT_ID))).willReturn(Optional.of(replacement));
		given(checklistItems.findByCaseId(any())).willReturn(completeChecklist);
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

	private static TeamMember member(UUID id, Role role, UUID teamId) {
		TeamMember member = mock(TeamMember.class);
		given(member.getId()).willReturn(id);
		given(member.getRole()).willReturn(role);
		given(member.getBrandId()).willReturn(BRAND);
		given(member.getTeamId()).willReturn(teamId);
		return member;
	}

	private static Expert expert(UUID id, Availability availability) {
		Expert value = mock(Expert.class);
		given(value.getId()).willReturn(id);
		given(value.getAvailability()).willReturn(availability);
		return value;
	}

	private static DocumentChecklistItem checklistItem(ChecklistItemStatus status) {
		DocumentChecklistItem item = mock(DocumentChecklistItem.class);
		given(item.getStatus()).willReturn(status);
		return item;
	}

	/** Everything the walk needs before the draft loops start. */
	private void walkToDraftGeneration() {
		actAs(Role.BRAND_MANAGER);
		lifecycle.markPaid(CASE_ID, PAID, "INV-0001");
		lifecycle.assignPm(CASE_ID, PM_ID);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.markDocsComplete(CASE_ID);
		actAs(Role.PROJECT_MANAGER);
		lifecycle.assignCaseManager(CASE_ID, CM_ID, EXPERT_ID);
	}

	private List<CaseEvents.Type> publishedEventTypes(int expected) {
		ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
		verify(events, times(expected)).publishEvent(published.capture());
		return published.getAllValues().stream()
				.map(CaseEvents.CaseEvent.class::cast)
				.map(CaseEvents.CaseEvent::type)
				.toList();
	}

	@Test
	void theDeclaredPathWalksFromDocCollectionToClosed() {
		walkToDraftGeneration();
		assertEquals(Stage.DRAFT_GENERATION, subject.getCurrentStage());
		assertEquals(PoolStatus.ASSIGNED, subject.getPoolStatus());
		assertEquals(TEAM, subject.getTeamId(), "the PM's team is what opens the case to that team");

		actAs(Role.CASE_MANAGER);
		lifecycle.submitDraft(CASE_ID);
		assertEquals(1, subject.getDraftVersionCount());

		actAs(Role.PROJECT_MANAGER);
		lifecycle.pmApproveDraft(CASE_ID);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.sendDraftToClient(CASE_ID);
		lifecycle.clientApproveDraft(CASE_ID);
		assertEquals(Stage.EXPERT_SIGNING, subject.getCurrentStage());

		actAs(Role.PROJECT_MANAGER);
		lifecycle.expertSigned(CASE_ID);
		lifecycle.pmQcApprove(CASE_ID);
		assertEquals(Stage.FINAL_DELIVERY, subject.getCurrentStage());

		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.deliverToClient(CASE_ID);
		lifecycle.confirmReceiptAndClose(CASE_ID);

		assertEquals(Stage.CLOSED, subject.getCurrentStage());
		assertEquals(ExceptionState.NONE, subject.getExceptionState());
		assertTrue(RefundService.isRevenueRecognized(subject));
		assertNull(subject.getSlaStatus(), "a closed case runs no clock");

		// Exactly one audit entry and one event per hop, and in the declared order.
		verify(audit, times(12)).recordEvent(any(), any(), any(), any(), any(), any());
		assertEquals(List.of(
				CaseEvents.Type.CASE_PAID,
				CaseEvents.Type.PM_ASSIGNED,
				CaseEvents.Type.DOCUMENTS_COMPLETED,
				CaseEvents.Type.EXPERT_ASSIGNED,
				CaseEvents.Type.DRAFT_SUBMITTED,
				CaseEvents.Type.DRAFT_PM_APPROVED,
				CaseEvents.Type.DRAFT_READY_FOR_CLIENT,
				CaseEvents.Type.DRAFT_CLIENT_APPROVED,
				CaseEvents.Type.EXPERT_SIGNED,
				CaseEvents.Type.QC_APPROVED,
				CaseEvents.Type.CASE_DELIVERED,
				CaseEvents.Type.CASE_CLOSED), publishedEventTypes(12));
	}

	/**
	 * The Coordinator's assignment slot. Without it their tier (SELF) matched no case at
	 * all, so this is what makes their board and their four transitions reachable.
	 * Re-assignable on purpose, and at any active stage — coordination changes hands.
	 */
	@Test
	void aCoordinatorCanBeAssignedAndReassignedAtAnyActiveStage() {
		actAs(Role.PROJECT_MANAGER);

		lifecycle.assignCoordinator(CASE_ID, COORDINATOR_ID);
		assertEquals(COORDINATOR_ID, subject.getAssignedCoordinator());
		// Stage-preserving: staffing a case is not moving it.
		assertEquals(Stage.DOC_COLLECTION, subject.getCurrentStage());
		assertEquals(List.of(CaseEvents.Type.COORDINATOR_ASSIGNED), publishedEventTypes(1));

		// Later in the pipeline, and to somebody else, both still legal.
		walkToDraftGeneration();
		actAs(Role.PROJECT_MANAGER);
		lifecycle.assignCoordinator(CASE_ID, COORDINATOR_ID);
		assertEquals(COORDINATOR_ID, subject.getAssignedCoordinator());
		assertEquals(Stage.DRAFT_GENERATION, subject.getCurrentStage());
	}

	@Test
	void aCoordinatorFromAnotherBrandCannotBePutOnTheCase() {
		actAs(Role.PROJECT_MANAGER);
		UUID outsider = UUID.randomUUID();

		// Same indistinguishable message as every other member lookup: wrong brand, wrong
		// role and no such member must not be tellable apart from outside.
		assertThrows(IllegalTransitionException.class, () -> lifecycle.assignCoordinator(CASE_ID, outsider));
		assertNull(subject.getAssignedCoordinator());
	}

	@Test
	void anUndeclaredTransitionIsRefusedAndChangesNothing() {
		actAs(Role.PROJECT_MANAGER);

		assertThrows(IllegalTransitionException.class, () -> lifecycle.pmQcApprove(CASE_ID));

		assertEquals(Stage.DOC_COLLECTION, subject.getCurrentStage());
		verify(cases, never()).save(any());
		verifyNoInteractions(audit, events);
	}

	@Test
	void docsCompleteNeedsAProjectManagerAndAFinishedChecklist() {
		subject.setPaid(true);
		actAs(Role.PROJECT_COORDINATOR);
		assertThrows(IllegalTransitionException.class, () -> lifecycle.markDocsComplete(CASE_ID));

		actAs(Role.BRAND_MANAGER);
		lifecycle.assignPm(CASE_ID, PM_ID);
		List<DocumentChecklistItem> stillWaiting = List.of(
				checklistItem(ChecklistItemStatus.APPROVED), checklistItem(ChecklistItemStatus.REQUIRED));
		given(checklistItems.findByCaseId(any())).willReturn(stillWaiting);

		actAs(Role.PROJECT_COORDINATOR);
		assertThrows(IllegalTransitionException.class, () -> lifecycle.markDocsComplete(CASE_ID));
		assertEquals(Stage.DOC_COLLECTION, subject.getCurrentStage());
	}

	/**
	 * Handoff A now creates a case from a contact, so an unpaid case is a normal state.
	 * Documents may be gathered against one — that costs nothing — but the next hop
	 * engages an expert, and every later stage is only reachable through it.
	 */
	@Test
	void anUnpaidCaseGetsNoFurtherThanDocCollection() {
		actAs(Role.BRAND_MANAGER);
		lifecycle.assignPm(CASE_ID, PM_ID);

		actAs(Role.PROJECT_COORDINATOR);
		assertEquals("the case has not been paid",
				assertThrows(IllegalTransitionException.class, () -> lifecycle.markDocsComplete(CASE_ID))
						.getMessage());
		assertEquals(Stage.DOC_COLLECTION, subject.getCurrentStage());
		assertFalse(RefundService.isRevenueRecognized(subject), "and it is not revenue either");

		actAs(Role.BRAND_MANAGER);
		lifecycle.markPaid(CASE_ID, PAID, "INV-0001");
		assertTrue(subject.isPaid());
		assertEquals(PAID, subject.getDealValue());

		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.markDocsComplete(CASE_ID);
		assertEquals(Stage.EXPERT_ASSIGNMENT, subject.getCurrentStage());
	}

	/**
	 * The amount is correctable, the moment it arrived is not. A case GHL reported as
	 * already paid carries only the quote, because a quote is all the contact webhook
	 * knows — so if the figure actually collected differs, somebody has to be able to
	 * replace it. Re-stamping {@code paidAt} would lose when the money landed.
	 */
	@Test
	void theAmountCanBeCorrectedButThePaymentMomentIsWriteOnce() {
		subject.setPaid(true);
		Instant whenTheMoneyLanded = Instant.now().minus(Duration.ofDays(3));
		subject.setPaidAt(whenTheMoneyLanded);
		subject.setDealValue(new BigDecimal("1200.00"));

		actAs(Role.BRAND_MANAGER);
		lifecycle.markPaid(CASE_ID, PAID, "INV-0009");

		assertEquals(PAID, subject.getDealValue(), "the collected figure replaces the quote");
		assertEquals("INV-0009", subject.getInvoiceRef());
		assertEquals(whenTheMoneyLanded, subject.getPaidAt(), "and the original moment survives");
		// That a correction does not raise a second pool alert is now Unit 06's guard, in
		// NotificationListenersTest — this method no longer knows what a notification is.
	}

	/** The money path re-checks the role in the service, not only at the endpoint. */
	@Test
	void onlyTheGmOrABrandManagerMayRecordAPayment() {
		for (Role role : List.of(Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR, Role.CASE_MANAGER,
				Role.EXPERT_NETWORK_MANAGER)) {
			actAs(role);
			assertThrows(ForbiddenException.class, () -> lifecycle.markPaid(CASE_ID, PAID, "INV-0001"),
					role + " must not be able to record money");
		}
		assertFalse(subject.isPaid());
		verifyNoInteractions(audit, events);
	}

	@Test
	void aCaseInAnExceptionStateAcceptsOnlyItsWayOut() {
		walkToDraftGeneration();

		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.putOnHold(CASE_ID, "waiting on the client's transcript");
		assertEquals(ExceptionState.ON_HOLD_AWAITING_CLIENT, subject.getExceptionState());
		assertNull(subject.getSlaStatus(), "a case on hold runs no clock");

		actAs(Role.CASE_MANAGER);
		assertThrows(IllegalTransitionException.class, () -> lifecycle.submitDraft(CASE_ID));

		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.resumeFromHold(CASE_ID);
		assertEquals(ExceptionState.NONE, subject.getExceptionState());
		assertEquals(Stage.DRAFT_GENERATION, subject.getCurrentStage(), "resume returns the stage it never left");
	}

	@Test
	void aDeclinedExpertSendsTheCaseBackToAssignmentWithANewOne() {
		walkToDraftGeneration();
		actAs(Role.CASE_MANAGER);
		lifecycle.submitDraft(CASE_ID);
		actAs(Role.PROJECT_MANAGER);
		lifecycle.pmApproveDraft(CASE_ID);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.sendDraftToClient(CASE_ID);
		lifecycle.clientApproveDraft(CASE_ID);

		actAs(Role.PROJECT_MANAGER);
		lifecycle.expertDeclined(CASE_ID, "outside my field");
		assertEquals(ExceptionState.EXPERT_DECLINED_REMATCHING, subject.getExceptionState());

		assertThrows(IllegalTransitionException.class, () -> lifecycle.reassignExpert(CASE_ID, EXPERT_ID),
				"the expert who declined is not a rematch");

		lifecycle.reassignExpert(CASE_ID, OTHER_EXPERT_ID);
		assertEquals(Stage.EXPERT_ASSIGNMENT, subject.getCurrentStage());
		assertEquals(ExceptionState.NONE, subject.getExceptionState());
		assertEquals(OTHER_EXPERT_ID, subject.getExpertId());
		assertEquals(ExpertSignStatus.REASSIGNED, subject.getExpertSignStatus());
	}

	@Test
	void anUnavailableExpertCannotBePutOnACase() {
		actAs(Role.BRAND_MANAGER);
		lifecycle.markPaid(CASE_ID, PAID, null);
		lifecycle.assignPm(CASE_ID, PM_ID);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.markDocsComplete(CASE_ID);

		Expert busy = expert(EXPERT_ID, Availability.AT_CAPACITY);
		given(experts.findScoped(any(TenantContext.class), eq(EXPERT_ID))).willReturn(Optional.of(busy));

		actAs(Role.PROJECT_MANAGER);
		assertThrows(IllegalTransitionException.class,
				() -> lifecycle.assignCaseManager(CASE_ID, CM_ID, EXPERT_ID));
		assertEquals(Stage.EXPERT_ASSIGNMENT, subject.getCurrentStage());
	}

	@Test
	void onlyTheGmMayRuleOnARefund() {
		subject.setCurrentStage(Stage.FINAL_DELIVERY);
		subject.setDeliveryDate(Instant.now());
		subject.setPaid(true);

		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.requestRefund(CASE_ID, "client changed their mind");
		assertEquals(ExceptionState.REFUND_REQUESTED, subject.getExceptionState());
		assertTrue(RefundService.isRevenueRecognized(subject), "a request is not yet a reversal");

		assertThrows(ForbiddenException.class, () -> refunds.approveRefund(CASE_ID));
		assertThrows(ForbiddenException.class, () -> refunds.denyRefund(CASE_ID, "no"));
	}

	@Test
	void approvedRefundReversesRecognitionAndVoidsThePendingPayout() {
		subject.setCurrentStage(Stage.FINAL_DELIVERY);
		subject.setDeliveryDate(Instant.now());
		subject.setPaid(true);

		PayoutLedger pending = mock(PayoutLedger.class);
		List<PayoutLedger> owed = List.of(pending);
		given(payouts.findByCaseIdAndStatus(any(), any())).willReturn(owed);

		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.requestRefund(CASE_ID, "client changed their mind");

		actAs(Role.GM);
		refunds.approveRefund(CASE_ID);

		assertEquals(Stage.CLOSED, subject.getCurrentStage());
		assertEquals(ExceptionState.REFUND_REQUESTED, subject.getExceptionState(), "the refunded flag");
		assertTrue(RefundService.isRefunded(subject));
		assertFalse(RefundService.isRevenueRecognized(subject));
		verify(pending).setStatus(PayoutStatus.VOIDED);
		assertEquals(List.of(CaseEvents.Type.CASE_REFUND_REQUESTED, CaseEvents.Type.CASE_REFUNDED),
				publishedEventTypes(2));
	}

	@Test
	void aDeniedRefundPutsTheCaseBackWhereItWas() {
		subject.setCurrentStage(Stage.FINAL_DELIVERY);
		subject.setDeliveryDate(Instant.now());
		subject.setPaid(true);

		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.requestRefund(CASE_ID, "client changed their mind");
		actAs(Role.GM);
		refunds.denyRefund(CASE_ID, "work already delivered and signed");

		assertEquals(Stage.FINAL_DELIVERY, subject.getCurrentStage());
		assertEquals(ExceptionState.NONE, subject.getExceptionState());
		assertTrue(RefundService.isRevenueRecognized(subject));
	}

	@Test
	void aCaseOutsideTheCallersScopeIsNotFound() {
		given(cases.findScoped(any(TenantContext.class), any(UUID.class))).willReturn(Optional.empty());
		actAs(Role.BRAND_MANAGER);

		assertThrows(ForbiddenException.class, () -> lifecycle.read(CASE_ID));
		assertThrows(ForbiddenException.class, () -> lifecycle.assignPm(CASE_ID, PM_ID));
		verifyNoInteractions(audit, events);
	}

	@Test
	void everyTransitionRestampsTheClockSoEachRoundGetsItsOwnBudget() {
		walkToDraftGeneration();
		Instant afterAssignment = subject.getStageEnteredAt();

		actAs(Role.CASE_MANAGER);
		lifecycle.submitDraft(CASE_ID);

		assertTrue(!subject.getStageEnteredAt().isBefore(afterAssignment),
				"the PM review round starts its own 12 hours");
		verify(audit, times(5)).recordEvent(anyString(), any(), any(), any(), any(), any());
	}

	/** Doc collection budgets three business days; thirty calendar days is past it however you count. */
	private void sittingWellPastItsBudgetButStoredAsOnTrack() {
		subject.setStageEnteredAt(Instant.now().minus(Duration.ofDays(30)));
		subject.setSlaStatus(SlaStatus.ON_TRACK);
	}

	@Test
	void slaStatusIsRecomputedOnReadRatherThanReadBackFromTheRow() {
		sittingWellPastItsBudgetButStoredAsOnTrack();
		actAs(Role.BRAND_MANAGER);

		assertEquals(SlaStatus.OVERDUE, lifecycle.read(CASE_ID).getSlaStatus(),
				"a case left sitting past its budget is overdue even though nothing wrote to it");
	}

	@Test
	void theBoardSlaFilterMatchesOnTheRecomputedStatusNotTheStoredColumn() {
		sittingWellPastItsBudgetButStoredAsOnTrack();
		given(cases.findScoped(any(TenantContext.class), any(), any())).willReturn(List.of(subject));
		actAs(Role.BRAND_MANAGER);

		assertEquals(1, lifecycle.list(null, SlaStatus.OVERDUE, null).size(),
				"a board filtering for overdue has to find the case whose column still says on-track");
		assertTrue(lifecycle.list(null, SlaStatus.ON_TRACK, null).isEmpty(),
				"and must not still find it under the stale value");
	}

	@Test
	void aMemberOutsideTheBrandIsIndistinguishableFromOneThatDoesNotExist() {
		actAs(Role.BRAND_MANAGER);
		UUID anotherBrandsPm = UUID.randomUUID();
		UUID nobody = UUID.randomUUID();

		// Neither id is stubbed, because neither can come back: brand and role are in the
		// query, so there is no row in hand to tell the two failures apart.
		String forAnotherBrand = assertThrows(IllegalTransitionException.class,
				() -> lifecycle.assignPm(CASE_ID, anotherBrandsPm)).getMessage();
		String forNobody = assertThrows(IllegalTransitionException.class,
				() -> lifecycle.assignPm(CASE_ID, nobody)).getMessage();

		assertEquals(forNobody, forAnotherBrand, "the 409 body must not be an existence oracle");
		assertFalse(forAnotherBrand.contains(anotherBrandsPm.toString()),
				"nor echo an id the caller does not own");
		verify(teamMembers, never()).findById(any());
	}
}
