package com.ie.evalos.notification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Notification;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.NotificationRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit 06's central acceptance criterion: each mapped event reaches exactly the mapped
 * recipients, tagged with the *case's* brand, and staff in another brand receive
 * nothing.
 *
 * <p>The resolver is real over mocked repositories rather than stubbed, because the
 * recipient rule is the thing worth asserting — a stubbed resolver would test the
 * listener's plumbing and nothing anybody cares about.
 */
class NotificationListenersTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID OTHER_BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();

	private static final UUID GM = UUID.randomUUID();
	private static final UUID BRAND_MANAGER = UUID.randomUUID();
	private static final UUID OTHER_BRANDS_MANAGER = UUID.randomUUID();
	private static final UUID PM = UUID.randomUUID();
	private static final UUID CM = UUID.randomUUID();
	private static final UUID COORDINATOR = UUID.randomUUID();

	private final CaseRepository cases = mock(CaseRepository.class);
	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
	private final NotificationRepository notifications = mock(NotificationRepository.class);

	private final RecipientResolver resolver = new RecipientResolver(teamMembers);
	private final NotificationService service = new NotificationService(notifications);
	private final NotificationListeners listeners = new NotificationListeners(cases, resolver, service);

	private Case subject;

	@BeforeEach
	void aCaseWithAPmAndACm() {
		subject = new Case(BRAND, "IE-2026-0001", Stage.DRAFT_IN_PROGRESS);
		subject.setAssignedPm(PM);
		subject.setAssignedCm(CM);

		// Every collaborator before any stubbing: a mock created inside a willReturn(...)
		// argument leaves the outer stubbing unfinished.
		List<TeamMember> gms = List.of(member(GM));
		List<TeamMember> managers = List.of(member(BRAND_MANAGER));
		List<TeamMember> projectManagers = List.of(member(PM));
		List<TeamMember> coordinators = List.of(member(COORDINATOR));
		List<TeamMember> otherBrandsManagers = List.of(member(OTHER_BRANDS_MANAGER));

		given(cases.findById(CASE_ID)).willReturn(Optional.of(subject));
		given(teamMembers.findByActiveTrueAndRole(Role.GM)).willReturn(gms);
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.BRAND_MANAGER, BRAND)).willReturn(managers);
		// The pool lookup is by role and brand, not by the case's assignment — the same person
		// happens to be this case's PM, which is what makes the two readings comparable.
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.PROJECT_MANAGER, BRAND))
				.willReturn(projectManagers);
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.PROJECT_COORDINATOR, BRAND))
				.willReturn(coordinators);
		// The other brand's manager exists but must never be reachable from this case.
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.BRAND_MANAGER, OTHER_BRAND))
				.willReturn(otherBrandsManagers);
	}

	private static TeamMember member(UUID id) {
		TeamMember value = mock(TeamMember.class);
		given(value.getId()).willReturn(id);
		return value;
	}

	private void fire(CaseEvents.Type type) {
		listeners.on(new CaseEvents.CaseEvent(type, BRAND, CASE_ID, UUID.randomUUID(), null,
				subject.getCurrentStage()));
	}

	private List<Notification> raised() {
		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notifications, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
		return captor.getAllValues();
	}

	/**
	 * One event's recipients. Interactions are cleared first so a method asserting
	 * several events in a row sees each on its own rather than the running total.
	 */
	private List<UUID> recipientsOf(CaseEvents.Type type) {
		org.mockito.Mockito.clearInvocations(notifications);
		fire(type);
		return raised().stream().map(Notification::getRecipientId).toList();
	}

	@Test
	void theAssignedPmIsToldWhatNeedsTheirAttention() {
		assertThat(recipientsOf(CaseEvents.Type.DOCUMENTS_COMPLETED)).containsExactly(PM);
	}

	@Test
	void theAssignedCaseManagerIsToldTheyOwnItAndWhatTheClientSaid() {
		assertThat(recipientsOf(CaseEvents.Type.EXPERT_ASSIGNED)).containsExactly(CM);
	}

	@Test
	void theCoordinatorIsToldWhenADraftIsReadyToSend() {
		assertThat(recipientsOf(CaseEvents.Type.DRAFT_PM_APPROVED)).containsExactly(COORDINATOR);
	}

	/**
	 * A20, and the gap it closes: QC approval is the Coordinator's cue to deliver, and it
	 * was the one shipped transition with no route — so the alert never reached the person
	 * whose next action it is.
	 */
	@Test
	void theCoordinatorIsToldWhenQcPasses() {
		assertThat(recipientsOf(CaseEvents.Type.QC_APPROVED)).containsExactly(COORDINATOR);
	}

	@Test
	void onlyTheGmHearsAboutARefundRequest() {
		assertThat(recipientsOf(CaseEvents.Type.CASE_REFUND_REQUESTED)).containsExactly(GM);
	}

	/**
	 * Unit 16. Should be unreachable, so it goes past the case's own PM to that brand's
	 * Brand Managers too rather than staying an ordinary assignee-only alert.
	 */
	@Test
	void aDeliveryWithNoExpertAlertsTheCasesPmAndThatBrandsManagers() {
		assertThat(recipientsOf(CaseEvents.Type.CASE_DELIVERED_NO_EXPERT))
				.containsExactlyInAnyOrder(PM, BRAND_MANAGER);
	}

	/** The spec's whole table in one pass, so a mis-wired row cannot hide. */
	@Test
	void theWholeMapMatchesTheSpecTable() {
		assertThat(recipientsOf(CaseEvents.Type.CASE_CREATED))
				.as("a paid case lands in the PM/Coordinator pool, and nowhere else")
				.containsExactlyInAnyOrder(PM, COORDINATOR)
				.doesNotContain(GM, BRAND_MANAGER);
		assertThat(recipientsOf(CaseEvents.Type.DRAFT_SUBMITTED)).containsExactly(PM);
		assertThat(recipientsOf(CaseEvents.Type.EXPERT_SIGNED)).containsExactly(PM);
		assertThat(recipientsOf(CaseEvents.Type.DRAFT_RETURNED)).containsExactly(CM);
		assertThat(recipientsOf(CaseEvents.Type.DRAFT_CLIENT_APPROVED)).containsExactly(CM);
		assertThat(recipientsOf(CaseEvents.Type.DRAFT_REVISION_REQUESTED)).containsExactly(CM);
	}

	/**
	 * The pool arrival is the only notice that a **paid** case exists, so it is the one
	 * recipient set that escalates rather than falling silent. A brand staffed before its first
	 * PM or Coordinator is active would otherwise take the money and tell nobody.
	 */
	@Test
	void aPoolWithNobodyInItEscalatesRatherThanGoingQuiet() {
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.PROJECT_MANAGER, BRAND))
				.willReturn(List.of());
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.PROJECT_COORDINATOR, BRAND))
				.willReturn(List.of());

		assertThat(recipientsOf(CaseEvents.Type.CASE_CREATED))
				.as("the GM and that brand's managers can staff it; nobody else is widened to")
				.containsExactlyInAnyOrder(GM, BRAND_MANAGER)
				.doesNotContain(OTHER_BRANDS_MANAGER);
	}

	/**
	 * A fallback, not an addition. The GM was moved off this route so they do not hear about
	 * every case — only about one that would otherwise be unheard.
	 */
	@Test
	void aStaffedPoolDoesNotEscalateToTheGm() {
		assertThat(recipientsOf(CaseEvents.Type.CASE_CREATED)).doesNotContain(GM, BRAND_MANAGER);
	}

	/** Only the pool escalates. An assignee lookup with nobody in it still raises nothing. */
	@Test
	void anAssigneeLookupStillHasNoFallback() {
		subject.setAssignedPm(null);

		assertThat(resolver.assignedPm(subject)).isEmpty();
	}

	/**
	 * The other half of the arrival criterion, and the half a recipient assertion cannot reach:
	 * the alert must be a {@code NEW_CASE_IN_POOL} and **no event may raise a `NEW_LEAD`**. That
	 * constant is deliberately kept — notification rows already written persist it as text — and
	 * a kept constant is the easy one to re-adopt by accident, so the whole vocabulary is fired
	 * and the whole output checked rather than the table being trusted by eye.
	 */
	@Test
	void thePoolArrivalIsTheAlertAndNoEventRaisesARetiredLeadAlert() {
		for (CaseEvents.Type type : CaseEvents.Type.values()) {
			fire(type);
		}

		assertThat(raised())
				.extracting(Notification::getType)
				.contains(NotificationType.NEW_CASE_IN_POOL)
				.doesNotContain(NotificationType.NEW_LEAD);
	}

	@Test
	void everyNotificationCarriesTheCasesBrandAndNoOtherBrandsStaff() {
		fire(CaseEvents.Type.CASE_CREATED);

		assertThat(raised()).allSatisfy(notification -> {
			assertThat(notification.getBrandId()).isEqualTo(BRAND);
			assertThat(notification.getCaseId()).isEqualTo(CASE_ID);
		})
				.extracting(Notification::getRecipientId)
				.doesNotContain(OTHER_BRANDS_MANAGER);
	}

	@Test
	void theBodyNamesTheCaseSoTheBellIsReadableWithoutOpeningIt() {
		fire(CaseEvents.Type.CASE_CREATED);
		assertThat(raised()).first()
				.extracting(Notification::getBody).asString()
				.contains("IE-2026-0001");
	}

	/**
	 * Client-facing events exist so Unit 18 can hand them to GHL. They must leave no
	 * staff row behind — and EvalOS sends no mail, so there is nothing else to check.
	 */
	@Test
	void clientFacingEventsRaiseNoStaffNotification() {
		fire(CaseEvents.Type.CHECKLIST_REQUESTED);
		fire(CaseEvents.Type.DRAFT_READY_FOR_CLIENT);
		fire(CaseEvents.Type.CASE_DELIVERED);

		verify(notifications, never()).save(any());
	}

	/** Events with no row in the spec's table raise nothing rather than something generic. */
	@Test
	void anUnmappedEventRaisesNothing() {
		fire(CaseEvents.Type.CASE_ON_HOLD);
		fire(CaseEvents.Type.CASE_RESUMED);
		fire(CaseEvents.Type.CASE_CLOSED);

		verify(notifications, never()).save(any());
	}

	/**
	 * Belt and braces since v2.0 — intake publishes `case.created` only on the create path,
	 * so a second arrival alert should be unreachable. The guard stays because "needs a
	 * project manager" is not worth saying twice and it costs one lookup.
	 */
	@Test
	void aRepublishedPoolArrivalIsAnnouncedOnlyOnce() {
		given(notifications.existsByCaseIdAndType(CASE_ID, NotificationType.NEW_CASE_IN_POOL)).willReturn(true);

		fire(CaseEvents.Type.CASE_CREATED);

		verify(notifications, never()).save(any());
	}

	/** No PM assigned yet is a real state; it must not fall back to a broadcast. */
	@Test
	void anEventWithNoResolvedRecipientRaisesNothing() {
		subject.setAssignedPm(null);

		fire(CaseEvents.Type.DOCUMENTS_COMPLETED);

		verify(notifications, never()).save(any());
	}

	/** A case that vanished between publish and delivery is logged, not thrown. */
	@Test
	void aMissingCaseIsSurvivable() {
		given(cases.findById(CASE_ID)).willReturn(Optional.empty());

		fire(CaseEvents.Type.CASE_CREATED);

		verify(notifications, never()).save(any());
	}
}
