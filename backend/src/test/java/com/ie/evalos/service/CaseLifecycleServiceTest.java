package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.PoolStatus;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.ExpertRepository;
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
import static org.mockito.Mockito.clearInvocations;
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
	private static final UUID OTHER_CM_ID = UUID.randomUUID();
	private static final UUID COORDINATOR_ID = UUID.randomUUID();
	private static final UUID EXPERT_ID = UUID.randomUUID();
	private static final UUID OTHER_EXPERT_ID = UUID.randomUUID();
	private static final BigDecimal PAID = new BigDecimal("1450.00");
	private static final String DRAFT_LINK = "https://docs.google.com/document/d/draft-one/edit";

	private final CaseRepository cases = mock(CaseRepository.class);
	private final DocumentChecklistItemRepository checklistItems = mock(DocumentChecklistItemRepository.class);
	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final ExpertCaseOfferRepository offers = mock(ExpertCaseOfferRepository.class);
	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
	private final PayoutLedgerRepository payouts = mock(PayoutLedgerRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final PayoutService payoutService = mock(PayoutService.class);

	private final SlaCalculator sla = new SlaCalculator(new BusinessCalendar());
	private final CaseLifecycleService lifecycle = new CaseLifecycleService(
			cases, checklistItems, experts, offers, teamMembers, audit, sla, events, payoutService);
	private final RefundService refunds = new RefundService(lifecycle, payouts);

	private Case subject;

	@BeforeEach
	void freshCaseInDocCollection() {
		subject = new Case(BRAND, "IE-2026-0001", Stage.DOC_COLLECTION);
		subject.setPoolStatus(PoolStatus.IN_POOL);
		subject.setStageEnteredAt(Instant.now());
		// Born paid, as every case is under Case Creation v2.0: Handoff A fires on the won
		// opportunity, which GHL only marks after collecting. There is no transition here
		// that could set this, which is the point.
		subject.setPaid(true);
		subject.setPaidAt(Instant.now());
		subject.setDealValue(PAID);

		// Build every collaborator before stubbing anything: a mock created inside a
		// willReturn(...) argument leaves the outer stubbing unfinished.
		TeamMember pm = member(PM_ID, Role.PROJECT_MANAGER, TEAM);
		TeamMember cm = member(CM_ID, Role.CASE_MANAGER, TEAM);
		TeamMember otherCm = member(OTHER_CM_ID, Role.CASE_MANAGER, TEAM);
		TeamMember coordinator = member(COORDINATOR_ID, Role.PROJECT_COORDINATOR, TEAM);
		Expert assigned = expert(EXPERT_ID, Availability.AVAILABLE);
		Expert replacement = expert(OTHER_EXPERT_ID, Availability.AVAILABLE);
		List<DocumentChecklistItem> completeChecklist = List.of(
				checklistItem(ChecklistItemStatus.APPROVED), checklistItem(ChecklistItemStatus.UPLOADED));

		given(cases.findScoped(any(TenantContext.class), any(UUID.class))).willReturn(Optional.of(subject));
		given(cases.save(any(Case.class))).willAnswer(invocation -> invocation.getArgument(0));
		// The delivery walk has an expert on the case throughout, so by default a payout row
		// opens and notifyNoExpertOnDelivery never fires — see aDeliveryWithNoExpertReportsIt
		// below for the one test that overrides this to prove the opposite path.
		given(payoutService.openForDelivery(any(Case.class))).willReturn(Optional.of(mock(PayoutLedger.class)));
		given(teamMembers.findByIdAndBrandIdAndRoleAndActiveTrue(PM_ID, BRAND, Role.PROJECT_MANAGER)).willReturn(Optional.of(pm));
		given(teamMembers.findByIdAndBrandIdAndRoleAndActiveTrue(CM_ID, BRAND, Role.CASE_MANAGER)).willReturn(Optional.of(cm));
		given(teamMembers.findByIdAndBrandIdAndRoleAndActiveTrue(OTHER_CM_ID, BRAND, Role.CASE_MANAGER))
				.willReturn(Optional.of(otherCm));
		given(teamMembers.findByIdAndBrandIdAndRoleAndActiveTrue(COORDINATOR_ID, BRAND, Role.PROJECT_COORDINATOR))
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
		lifecycle.submitDraft(CASE_ID, DRAFT_LINK);
		assertEquals(1, subject.getDraftVersionCount());
		assertEquals(DRAFT_LINK, subject.getDraftLink(), "the draft arrives with the link the client will read");
		assertNull(subject.getDriveLink(), "and never by way of the client's document folder");

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

		// Exactly one audit entry and one event per hop, and in the declared order. Eleven,
		// not twelve: the walk no longer opens with a payment, because the case is born paid.
		verify(audit, times(11)).recordEvent(any(), any(), any(), any(), any(), any());
		assertEquals(List.of(
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
				CaseEvents.Type.CASE_CLOSED), publishedEventTypes(11));
	}

	/**
	 * The one path {@code PayoutService} reports rather than swallows: a case that
	 * somehow reached delivery with no expert assigned. Should be unreachable —
	 * {@code FINAL_DELIVERY} only follows {@code EXPERT_SIGNING} — which is exactly why
	 * {@code notifyNoExpertOnDelivery} has to be a real alert rather than a stub.
	 */
	@Test
	void aDeliveryWithNoExpertReportsItRatherThanStayingSilent() {
		subject.setCurrentStage(Stage.FINAL_DELIVERY);
		subject.setPaid(true);
		given(payoutService.openForDelivery(any(Case.class))).willReturn(Optional.empty());

		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.deliverToClient(CASE_ID);

		assertEquals(List.of(CaseEvents.Type.CASE_DELIVERED, CaseEvents.Type.CASE_DELIVERED_NO_EXPERT),
				publishedEventTypes(2));
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

	/**
	 * Somebody who has left is not "available for this case". The check is in the shared
	 * member lookup, so this covers assign-pm and assign-cm as well: a dialog left open
	 * across a deactivation, or a direct POST with a remembered id, cannot staff a departed
	 * member onto live work.
	 */
	@Test
	void aDeactivatedMemberCannotBeAssigned() {
		actAs(Role.PROJECT_MANAGER);
		UUID departed = UUID.randomUUID();
		// The active-filtered finder is what the service calls, so an inactive row is simply
		// absent — the same way a wrong brand or wrong role is.
		given(teamMembers.findByIdAndBrandIdAndRoleAndActiveTrue(departed, BRAND, Role.PROJECT_COORDINATOR))
				.willReturn(Optional.empty());

		assertThrows(IllegalTransitionException.class, () -> lifecycle.assignCoordinator(CASE_ID, departed));
		assertNull(subject.getAssignedCoordinator());
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
	 * The unpaid guard, kept as a backstop rather than a live path. Under Case Creation
	 * v2.0 every case is born paid and nothing sets {@code paid} false, so this state
	 * should be unreachable — which is exactly why the guard stays and stays covered. It is
	 * one line in one place, and the stage it protects is the one that engages an expert:
	 * every later stage is only reachable through it.
	 */
	@Test
	void anUnpaidCaseGetsNoFurtherThanDocCollection() {
		subject.setPaid(false);
		subject.setPaidAt(null);

		actAs(Role.BRAND_MANAGER);
		lifecycle.assignPm(CASE_ID, PM_ID);

		actAs(Role.PROJECT_COORDINATOR);
		assertEquals("the case has not been paid",
				assertThrows(IllegalTransitionException.class, () -> lifecycle.markDocsComplete(CASE_ID))
						.getMessage());
		assertEquals(Stage.DOC_COLLECTION, subject.getCurrentStage());
		assertFalse(RefundService.isRevenueRecognized(subject), "and it is not revenue either");

		// The case as intake actually creates it clears the guard, and the next precondition
		// takes over in order.
		subject.setPaid(true);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.markDocsComplete(CASE_ID);
		assertEquals(Stage.EXPERT_ASSIGNMENT, subject.getCurrentStage());
	}

	/**
	 * There is no payment transition to reach, by design: GHL owns the fact and
	 * {@code CaseIntakeService} is its only writer. Asserted on the action table because
	 * that is where a re-added transition would have to declare itself.
	 */
	@Test
	void noTransitionRecordsAPayment() {
		assertFalse(Arrays.stream(CaseTransitions.Action.values())
				.anyMatch(action -> action.name().contains("PAID")),
				"a case cannot exist before the money, so nothing records it arriving");
	}

	@Test
	void aCaseInAnExceptionStateAcceptsOnlyItsWayOut() {
		walkToDraftGeneration();

		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.putOnHold(CASE_ID, "waiting on the client's transcript");
		assertEquals(ExceptionState.ON_HOLD_AWAITING_CLIENT, subject.getExceptionState());
		assertNull(subject.getSlaStatus(), "a case on hold runs no clock");

		actAs(Role.CASE_MANAGER);
		assertThrows(IllegalTransitionException.class, () -> lifecycle.submitDraft(CASE_ID, DRAFT_LINK));

		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.resumeFromHold(CASE_ID);
		assertEquals(ExceptionState.NONE, subject.getExceptionState());
		assertEquals(Stage.DRAFT_GENERATION, subject.getCurrentStage(), "resume returns the stage it never left");
	}

	/**
	 * Unit 14's portal-safe entry: the client's own approval.
	 *
	 * <p>Two things at once. It is the <strong>same transition</strong> — same guard, same stage,
	 * same event — reached with an already-authorized case instead of an id, because the token names
	 * the case and there is no {@code TenantContext} to scope one with. And the audit row goes
	 * through {@code recordPortalEvent}, so the trail names the client instead of leaving the null
	 * actor that reads as "the system" on the one entry that commits a letter to an expert's
	 * signature.
	 *
	 * <p>The security context is cleared first, deliberately: a client has no staff principal, so
	 * anything on this path still reaching for one has to fail here rather than in front of a
	 * client — or worse, quietly attribute the approval to whoever was last in the context.
	 */
	@Test
	void theClientsOwnApprovalIsTheSameTransitionButAuditedAsTheirs() {
		walkToDraftGeneration();
		actAs(Role.CASE_MANAGER);
		lifecycle.submitDraft(CASE_ID, DRAFT_LINK);
		actAs(Role.PROJECT_MANAGER);
		lifecycle.pmApproveDraft(CASE_ID);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.sendDraftToClient(CASE_ID);
		clearInvocations(audit, events);

		SecurityContextHolder.clearContext();
		lifecycle.clientApproveDraftFromPortal(subject);

		assertEquals(Stage.EXPERT_SIGNING, subject.getCurrentStage());
		assertEquals(ClientApprovalStatus.APPROVED, subject.getClientApprovalStatus());
		assertEquals(ExpertSignStatus.PENDING, subject.getExpertSignStatus());
		assertEquals(List.of(CaseEvents.Type.DRAFT_CLIENT_APPROVED), publishedEventTypes(1));

		// The brand comes off the case, never a request. One row, and not a staff one.
		verify(audit).recordPortalEvent(eq(BRAND), eq(PortalAudience.CLIENT), eq("CASE"), any(), any(), any(), any());
		verify(audit, never()).recordEvent(any(), any(), any(), any(), any(), any());
	}

	/**
	 * The 409 a client gets is Unit 04's guard, not a portal-specific copy of it — which is the
	 * whole reason the portal takes this entry rather than re-implementing the rule. Approving
	 * twice is the case that would otherwise send a second letter for signature.
	 */
	@Test
	void aClientCannotApproveADraftThatIsNotWithThem() {
		walkToDraftGeneration();
		clearInvocations(audit, events);
		SecurityContextHolder.clearContext();

		assertThrows(IllegalTransitionException.class, () -> lifecycle.clientApproveDraftFromPortal(subject));
		assertThrows(IllegalTransitionException.class,
				() -> lifecycle.clientRequestRevisionsFromPortal(subject, "please soften the conclusion"));
		verifyNoInteractions(events);
	}

	@Test
	void aDeclinedExpertSendsTheCaseBackToAssignmentWithANewOne() {
		walkToDraftGeneration();
		actAs(Role.CASE_MANAGER);
		lifecycle.submitDraft(CASE_ID, DRAFT_LINK);
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

	/**
	 * The 24h prompt's answer, and the property that matters is which door it opens.
	 *
	 * <p>A timeout that left the case in {@code NONE} would leave the PM's board flagging a red
	 * row with no legal action on it — {@code REASSIGN_EXPERT} is declared only from
	 * {@code EXPERT_DECLINED_REMATCHING}, which is asserted here rather than assumed.
	 */
	@Test
	void aTimedOutExpertOpensTheSameRematchADeclineDoes() {
		walkToDraftGeneration();
		actAs(Role.CASE_MANAGER);
		lifecycle.submitDraft(CASE_ID, DRAFT_LINK);
		actAs(Role.PROJECT_MANAGER);
		lifecycle.pmApproveDraft(CASE_ID);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.sendDraftToClient(CASE_ID);
		lifecycle.clientApproveDraft(CASE_ID);

		ExpertCaseOffer open = new ExpertCaseOffer(BRAND, CASE_ID, EXPERT_ID);
		given(offers.findByCaseIdAndOutcome(any(), eq(OfferOutcome.OFFERED))).willReturn(List.of(open));

		actAs(Role.PROJECT_MANAGER);
		lifecycle.expertTimedOut(CASE_ID);

		assertEquals(Stage.EXPERT_SIGNING, subject.getCurrentStage(), "a timeout does not move the case");
		assertEquals(ExceptionState.EXPERT_DECLINED_REMATCHING, subject.getExceptionState());
		assertEquals(OfferOutcome.TIMED_OUT, open.getOutcome(), "not DECLINED — the expert never answered");
		assertNull(open.getDeclineReason(), "the absence of an answer is the reason");

		lifecycle.reassignExpert(CASE_ID, OTHER_EXPERT_ID);
		assertEquals(Stage.EXPERT_ASSIGNMENT, subject.getCurrentStage());
		assertEquals(OTHER_EXPERT_ID, subject.getExpertId());
	}

	@Test
	void anUnavailableExpertCannotBePutOnACase() {
		actAs(Role.BRAND_MANAGER);
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

	// --- Unit 12: the offer rows the acceptance-rate factor is aggregated from -----

	/**
	 * Both offer-opening transitions write a row, and the rematch closes the one it replaces —
	 * so a rematched case leaves no {@code OFFERED} row that no transition can ever reach.
	 */
	@Test
	void anAssignmentOpensAnOfferAndARematchSupersedesItBeforeOpeningTheNext() {
		walkToDraftGeneration();

		ExpertCaseOffer first = savedOffers().getLast();
		assertEquals(EXPERT_ID, first.getExpertId());
		assertEquals(OfferOutcome.OFFERED, first.getOutcome());
		assertEquals(BRAND, first.getBrandId(), "the offer takes the case's brand, never the caller's");
		assertNull(first.getOutcomeAt(), "an open offer has no outcome timestamp");

		// The rematch path: decline, then replace. The first offer is the open one throughout.
		// Matched on the outcome only: `subject` is an unpersisted Case, so its generated id is
		// still null here, while CASE_ID is only the key the scoped read is stubbed against.
		given(offers.findByCaseIdAndOutcome(any(), eq(OfferOutcome.OFFERED))).willReturn(List.of(first));
		subject.setCurrentStage(Stage.EXPERT_SIGNING);

		actAs(Role.PROJECT_MANAGER);
		lifecycle.expertDeclined(CASE_ID, "outside my field");
		assertEquals(OfferOutcome.DECLINED, first.getOutcome());
		assertEquals("outside my field", first.getDeclineReason(),
				"the reason is the point of the transition, and the rate is aggregated from this row");

		// Now genuinely open again, so the supersede has something to close.
		ExpertCaseOffer stillOpen = new ExpertCaseOffer(BRAND, CASE_ID, EXPERT_ID);
		// Matched on the outcome only: `subject` is an unpersisted Case, so its generated id is
		// still null here, while CASE_ID is only the key the scoped read is stubbed against.
		given(offers.findByCaseIdAndOutcome(any(), eq(OfferOutcome.OFFERED))).willReturn(List.of(stillOpen));

		lifecycle.reassignExpert(CASE_ID, OTHER_EXPERT_ID);
		assertEquals(OfferOutcome.SUPERSEDED, stillOpen.getOutcome());
		assertNull(stillOpen.getDeclineReason(), "nobody declined — the offer was withdrawn");
		assertFalse(stillOpen.getOutcome().countsTowardAcceptanceRate(),
				"an expert never given the chance to answer is not penalised for it");

		ExpertCaseOffer replacement = savedOffers().getLast();
		assertEquals(OTHER_EXPERT_ID, replacement.getExpertId());
		assertEquals(OfferOutcome.OFFERED, replacement.getOutcome());
	}

	/**
	 * First write wins. Unit 15 has two acts that both mean accepted — the expert pressing
	 * Accept, then Dropbox Sign's {@code signed} callback — and on the happy path both fire, so
	 * the second has to be a no-op rather than an error.
	 */
	@Test
	void anOfferResolvesOnceAndTheSecondActChangesNothing() {
		ExpertCaseOffer offer = new ExpertCaseOffer(BRAND, CASE_ID, EXPERT_ID);
		// Matched on the outcome only: `subject` is an unpersisted Case, so its generated id is
		// still null here, while CASE_ID is only the key the scoped read is stubbed against.
		given(offers.findByCaseIdAndOutcome(any(), eq(OfferOutcome.OFFERED))).willReturn(List.of(offer));
		subject.setCurrentStage(Stage.EXPERT_SIGNING);
		subject.setExpertId(EXPERT_ID);

		actAs(Role.PROJECT_MANAGER);
		lifecycle.expertSigned(CASE_ID);
		Instant resolvedAt = offer.getOutcomeAt();
		assertEquals(OfferOutcome.ACCEPTED, offer.getOutcome());

		// The same offer offered again to the repository, as an out-of-order callback would.
		lifecycle.expertSigned(CASE_ID);
		assertEquals(OfferOutcome.ACCEPTED, offer.getOutcome());
		assertEquals(resolvedAt, offer.getOutcomeAt(), "the moment it was answered does not move");
		verify(offers, times(1)).save(offer);
	}

	/**
	 * The acceptance an uninvolved expert must not be handed. V19's open-offer index is not
	 * unique and {@code Case} has no {@code @Version}, so two concurrent assignments can leave two
	 * {@code OFFERED} rows; only the one belonging to the case's own expert is the offer this
	 * signature answers. The other is withdrawn, not credited — the acceptance rate is aggregated
	 * from exactly these rows.
	 */
	@Test
	void anAcceptanceCreditsOnlyTheCasesOwnExpertAndWithdrawsAnyStrayOffer() {
		ExpertCaseOffer mine = new ExpertCaseOffer(BRAND, CASE_ID, EXPERT_ID);
		ExpertCaseOffer stray = new ExpertCaseOffer(BRAND, CASE_ID, OTHER_EXPERT_ID);
		// Matched on the outcome only: `subject` is an unpersisted Case, so its generated id is
		// still null here, while CASE_ID is only the key the scoped read is stubbed against.
		given(offers.findByCaseIdAndOutcome(any(), eq(OfferOutcome.OFFERED))).willReturn(List.of(mine, stray));
		subject.setCurrentStage(Stage.EXPERT_SIGNING);
		subject.setExpertId(EXPERT_ID);

		actAs(Role.PROJECT_MANAGER);
		lifecycle.expertSigned(CASE_ID);

		assertEquals(OfferOutcome.ACCEPTED, mine.getOutcome());
		assertEquals(OfferOutcome.SUPERSEDED, stray.getOutcome(),
				"an expert who was never shown this case did not accept it");
		assertFalse(stray.getOutcome().countsTowardAcceptanceRate(),
				"and the stray must not move their rate in either direction");
	}

	/**
	 * A decline is the same rule seen from the other side: the stray is withdrawn rather than
	 * recorded as a refusal, and it does not inherit the reason the real expert gave.
	 */
	@Test
	void aDeclineIsRecordedAgainstNobodyButTheCasesOwnExpert() {
		ExpertCaseOffer mine = new ExpertCaseOffer(BRAND, CASE_ID, EXPERT_ID);
		ExpertCaseOffer stray = new ExpertCaseOffer(BRAND, CASE_ID, OTHER_EXPERT_ID);
		// Matched on the outcome only: `subject` is an unpersisted Case, so its generated id is
		// still null here, while CASE_ID is only the key the scoped read is stubbed against.
		given(offers.findByCaseIdAndOutcome(any(), eq(OfferOutcome.OFFERED))).willReturn(List.of(mine, stray));
		subject.setCurrentStage(Stage.EXPERT_SIGNING);
		subject.setExpertId(EXPERT_ID);

		actAs(Role.PROJECT_MANAGER);
		lifecycle.expertDeclined(CASE_ID, "outside my field");

		assertEquals(OfferOutcome.DECLINED, mine.getOutcome());
		assertEquals("outside my field", mine.getDeclineReason());
		assertEquals(OfferOutcome.SUPERSEDED, stray.getOutcome());
		assertNull(stray.getDeclineReason(), "the reason belongs to the expert who gave it");
	}

	/**
	 * {@code OFFERED} is not a resolution: taking it would date an outcome that is still open,
	 * which V19's {@code expert_case_offer_outcome_dated} forbids — a 500 at flush rolling back an
	 * otherwise valid transition, rather than a refusal at the call.
	 */
	@Test
	void anOfferCannotBeResolvedToStillOffered() {
		ExpertCaseOffer offer = new ExpertCaseOffer(BRAND, CASE_ID, EXPERT_ID);

		assertThrows(IllegalStateException.class, () -> offer.resolve(OfferOutcome.OFFERED, null));
		assertNull(offer.getOutcomeAt(), "and it is still open, with nothing dated");
	}

	/**
	 * <strong>The property "assist mode" means.</strong> The scorer would never propose an expert
	 * carrying no taxonomy at all — they score zero on the 40-point field factor and are dropped
	 * from the shortlist — and the assignment takes them anyway. The engine cannot become a
	 * precondition; see also {@code DomainInvariantsTest}, which holds it structurally.
	 */
	@Test
	void anExpertNoShortlistWouldProposeCanStillBeAssigned() {
		Expert untagged = expert(OTHER_EXPERT_ID, Availability.AVAILABLE);
		given(untagged.getPrimaryFields()).willReturn(List.of());
		given(untagged.getSecondaryFields()).willReturn(List.of());
		given(experts.findScoped(any(TenantContext.class), eq(OTHER_EXPERT_ID))).willReturn(Optional.of(untagged));

		actAs(Role.BRAND_MANAGER);
		lifecycle.assignPm(CASE_ID, PM_ID);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.markDocsComplete(CASE_ID);

		actAs(Role.PROJECT_MANAGER);
		lifecycle.assignCaseManager(CASE_ID, CM_ID, OTHER_EXPERT_ID);

		assertEquals(Stage.DRAFT_GENERATION, subject.getCurrentStage());
		assertEquals(OTHER_EXPERT_ID, subject.getExpertId());
		assertEquals(OTHER_EXPERT_ID, savedOffers().getLast().getExpertId(),
				"and the offer is recorded, so an off-list assignment still feeds the rate");
	}

	private List<ExpertCaseOffer> savedOffers() {
		ArgumentCaptor<ExpertCaseOffer> saved = ArgumentCaptor.forClass(ExpertCaseOffer.class);
		verify(offers, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
		return saved.getAllValues();
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
		lifecycle.submitDraft(CASE_ID, DRAFT_LINK);

		assertTrue(!subject.getStageEnteredAt().isBefore(afterAssignment),
				"the PM review round starts its own 12 hours");
		verify(audit, times(4)).recordEvent(anyString(), any(), any(), any(), any(), any());
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

	// --- reassignment and deadlines (Unit 22, slice 1) -----------------------

	/**
	 * The whole reason this is its own method rather than {@code assign-cm} widened to
	 * {@code DRAFT_GENERATION}.
	 *
	 * <p>{@code assignCaseManager} also picks the expert and writes an {@link ExpertCaseOffer}.
	 * Reusing it to move a case between Case Managers would mint an offer against an expert
	 * nobody contacted, and Unit 12's scorer reads those rows as real approaches — so the
	 * expert's acceptance rate would decay because a PM moved somebody's workload around.
	 */
	@Test
	void reassigningTheCaseManagerDoesNotMintAnExpertOffer() {
		walkToDraftGeneration();
		clearInvocations(offers);

		Case moved = lifecycle.reassignCaseManager(CASE_ID, OTHER_CM_ID);

		assertEquals(OTHER_CM_ID, moved.getAssignedCm());
		assertEquals(Stage.DRAFT_GENERATION, moved.getCurrentStage(),
				"reassignment moves the owner, never the case");
		verify(offers, never()).save(any());
	}

	@Test
	void reassigningToTheSameCaseManagerIsRefused() {
		walkToDraftGeneration();

		assertThrows(IllegalTransitionException.class,
				() -> lifecycle.reassignCaseManager(CASE_ID, CM_ID));
	}

	/**
	 * The deadline drives the risk tiles, and the change has to be answerable later: who moved
	 * the date, from what, to what.
	 */
	@Test
	void changingTheDeadlineRecordsBothSidesOnTheTrail() {
		actAs(Role.PROJECT_MANAGER);
		Instant moved = Instant.parse("2026-09-01T17:00:00Z");

		Case saved = lifecycle.changeDeadline(CASE_ID, moved);

		assertEquals(moved, saved.getDeadline());
		verify(audit).recordEvent(anyString(), any(), eq(AuditAction.UPDATED), any(),
				any(CaseLifecycleService.DeadlineSnapshot.class),
				eq(new CaseLifecycleService.DeadlineSnapshot(moved)));
	}

	/** Clearing a promise is a different act from changing one, and it hides the case. */
	@Test
	void aDeadlineCannotBeClearedThroughTheChangeRoute() {
		actAs(Role.PROJECT_MANAGER);

		assertThrows(IllegalTransitionException.class, () -> lifecycle.changeDeadline(CASE_ID, null));
	}

	/**
	 * The Case Manager's client-revision rate and feedback log are both counted from this action,
	 * so the mapping is asserted rather than assumed.
	 *
	 * <p>It shared {@code UPDATED} with strategy-note edits, deadline changes, draft submissions
	 * and most of the draft loop, which made "how often did a client ask for changes" unanswerable
	 * without parsing the snapshot. If it ever reverts, the metric silently reads zero — which
	 * looks like good news.
	 */
	@Test
	void aClientRevisionRequestGetsItsOwnAuditActionRatherThanUpdated() {
		walkToDraftGeneration();
		actAs(Role.CASE_MANAGER);
		lifecycle.submitDraft(CASE_ID, DRAFT_LINK);
		actAs(Role.PROJECT_MANAGER);
		lifecycle.pmApproveDraft(CASE_ID);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.sendDraftToClient(CASE_ID);
		clearInvocations(audit);

		lifecycle.clientRequestRevisions(CASE_ID, "please soften the conclusion");

		verify(audit).recordEvent(anyString(), any(), eq(AuditAction.CLIENT_REVISION_REQUESTED), any(),
				any(), any());
	}

	// --- notes (Unit 23) -----------------------------------------------------

	/**
	 * A note is one audit row carrying the text, and nothing else moves.
	 *
	 * <p>The snapshot is asserted identical either side because that is what makes it a note
	 * rather than a transition: if a stage or a pool status ever starts changing here, the
	 * timeline would begin claiming the case moved every time somebody typed a sentence.
	 */
	@Test
	void aNoteIsOneAuditRowAndChangesNothingAboutTheCase() {
		actAs(Role.CASE_MANAGER);
		clearInvocations(audit);

		Case saved = lifecycle.addNote(CASE_ID, "transcript pages 3-4 unreadable, asked for a rescan");

		assertEquals(Stage.DOC_COLLECTION, saved.getCurrentStage());
		assertEquals(PoolStatus.IN_POOL, saved.getPoolStatus());

		ArgumentCaptor<CaseLifecycleService.CaseSnapshot> after =
				ArgumentCaptor.forClass(CaseLifecycleService.CaseSnapshot.class);
		verify(audit).recordEvent(anyString(), any(), eq(AuditAction.NOTE_ADDED), any(),
				any(CaseLifecycleService.CaseSnapshot.class), after.capture());
		assertEquals("transcript pages 3-4 unreadable, asked for a rescan", after.getValue().note());
		assertEquals(Stage.DOC_COLLECTION, after.getValue().stage());
	}

	/** Surrounding whitespace is not content; a note of only whitespace is not a note. */
	@Test
	void aBlankNoteIsRefusedAndWritesNothing() {
		actAs(Role.CASE_MANAGER);
		clearInvocations(audit);

		assertThrows(IllegalTransitionException.class, () -> lifecycle.addNote(CASE_ID, "   "));
		assertThrows(IllegalTransitionException.class, () -> lifecycle.addNote(CASE_ID, null));
		verifyNoInteractions(audit);
	}

	/**
	 * Every role that can read the case can write on it, including the two the transition table
	 * gives almost nothing to. This is the whole authorization model for notes stated as a test:
	 * the gate is the scoped load, so there is no role here that is admitted and no role that is
	 * refused — {@code CaseControllerTest} asserts the same thing at the endpoint.
	 */
	@Test
	void anyRoleThatCanLoadTheCaseCanWriteOnIt() {
		for (Role role : Role.values()) {
			actAs(role);
			clearInvocations(audit);

			lifecycle.addNote(CASE_ID, "note from " + role);

			verify(audit).recordEvent(anyString(), any(), eq(AuditAction.NOTE_ADDED), any(), any(), any());
		}
	}

	/** A finished record stops growing. Anything else makes "what was agreed" unanswerable. */
	@Test
	void aClosedCaseTakesNoMoreNotes() {
		subject.setCurrentStage(Stage.CLOSED);
		actAs(Role.PROJECT_MANAGER);
		clearInvocations(audit);

		assertThrows(IllegalTransitionException.class, () -> lifecycle.addNote(CASE_ID, "one last thing"));
		verifyNoInteractions(audit);
	}

	/**
	 * A case in an exception state still takes notes, unlike every declared transition.
	 *
	 * <p>Deliberate and worth pinning: the moment somebody most needs to say something is the
	 * moment the case is stuck. {@code addNote} does not consult {@code CaseTransitions} at all,
	 * and this is the test that fails if somebody later routes it through the table for tidiness.
	 */
	@Test
	void aCaseOnHoldStillTakesNotes() {
		actAs(Role.PROJECT_MANAGER);
		lifecycle.putOnHold(CASE_ID, "client is travelling");
		clearInvocations(audit);

		lifecycle.addNote(CASE_ID, "client said they are back on the 14th");

		verify(audit).recordEvent(anyString(), any(), eq(AuditAction.NOTE_ADDED), any(), any(), any());
	}
}
