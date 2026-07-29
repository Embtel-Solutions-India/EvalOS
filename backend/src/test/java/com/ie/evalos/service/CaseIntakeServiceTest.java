package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.ClientType;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Notification;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.PoolStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.SourceChannel;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.domain.VisaCategory;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.NotificationRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Handoff A below the transport: what one inbound GHL contact leaves behind. The
 * gateway's half — signatures, dedupe, archival — is asserted in
 * {@code InboundWebhookTest}.
 *
 * <p>The case that arrives here is a lead, not a paid deal. What makes it workable is
 * {@code markPaid}, asserted in {@code CaseLifecycleServiceTest}.
 */
class CaseIntakeServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID GM_ID = UUID.randomUUID();
	private static final UUID BRAND_MANAGER_ID = UUID.randomUUID();
	private static final UUID OTHER_EXPERT = UUID.randomUUID();
	private static final UUID CONTACT_ID = UUID.randomUUID();

	private final CaseRepository cases = mock(CaseRepository.class);
	private final ContactSnapshotRepository contacts = mock(ContactSnapshotRepository.class);
	private final DocumentChecklistItemRepository checklistItems = mock(DocumentChecklistItemRepository.class);
	private final NotificationRepository notifications = mock(NotificationRepository.class);
	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private final SlaCalculator sla = new SlaCalculator(new BusinessCalendar());
	// Real notifier over mocked repositories, so the recipient rule is exercised rather
	// than stubbed away — it is the thing worth asserting about a pool alert.
	private final PoolNotifier pool = new PoolNotifier(teamMembers, notifications);
	private final CaseIntakeService intake = new CaseIntakeService(
			cases, contacts, checklistItems, audit, sla, pool, events);

	private final Brand brand = mock(Brand.class);

	@BeforeEach
	void seededBrandAndPool() {
		given(brand.getId()).willReturn(BRAND);
		given(brand.getName()).willReturn("International Evaluations");
		given(brand.getSlug()).willReturn("international-evaluations");

		// Collaborators first: a mock created inside a willReturn(...) argument leaves
		// the outer stubbing unfinished.
		List<TeamMember> gms = List.of(member(GM_ID));
		List<TeamMember> brandManagers = List.of(member(BRAND_MANAGER_ID));

		given(cases.save(any(Case.class))).willAnswer(invocation -> invocation.getArgument(0));
		given(contacts.save(any(ContactSnapshot.class))).willAnswer(invocation -> invocation.getArgument(0));
		given(teamMembers.findByActiveTrueAndRole(Role.GM)).willReturn(gms);
		given(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.BRAND_MANAGER, BRAND)).willReturn(brandManagers);
	}

	private static TeamMember member(UUID id) {
		TeamMember value = mock(TeamMember.class);
		given(value.getId()).willReturn(id);
		return value;
	}

	private static CaseIntakeService.NewCase contact(String ghlContactId, String email) {
		return contact(ghlContactId, email, ServiceType.EXPERT_OPINION_LETTER, false);
	}

	private static CaseIntakeService.NewCase contact(String ghlContactId, String email, ServiceType serviceType,
			boolean paid) {
		return new CaseIntakeService.NewCase(
				new CaseIntakeService.ContactDetails(ghlContactId, "Anita Rao", email, "+1 555 0100",
						"Rao Immigration LLP", ClientType.ATTORNEY, SourceChannel.GOOGLE_ADS,
						"google", "cpc", "eb2-niw-q3"),
				serviceType, null, VisaCategory.EB2_NIW, OTHER_EXPERT,
				new BigDecimal("1450.00"), Instant.now().plusSeconds(86_400),
				"https://drive.google.com/folder/abc", "INV-99123", "eb2-niw-q3", paid);
	}

	@Test
	void anInboundContactCreatesOneUnpaidCase() {
		Case created = intake.intake(brand, contact("ghl-c-1", "anita@raolaw.example"));

		assertThat(created.getBrandId()).isEqualTo(BRAND);
		assertThat(created.getCurrentStage()).isEqualTo(Stage.DOC_COLLECTION);
		assertThat(created.getPoolStatus()).isEqualTo(PoolStatus.IN_POOL);
		assertThat(created.getExceptionState()).isEqualTo(ExceptionState.NONE);
		assertThat(created.getAssignedPm()).isNull();
		assertThat(created.getAssignedCm()).isNull();
		// A lead, not a paid deal: the webhook is no longer proof of payment.
		assertThat(created.isPaid()).isFalse();
		assertThat(created.getPaidAt()).isNull();
		assertThat(RefundService.isRevenueRecognized(created)).isFalse();
		// The amount on the payload is a quote until markPaid confirms it.
		assertThat(created.getDealValue()).isEqualByComparingTo("1450.00");
		assertThat(created.getServiceType()).isEqualTo(ServiceType.EXPERT_OPINION_LETTER);
		// The sale's pre-selected expert is carried, but nothing is assigned yet.
		assertThat(created.getExpertId()).isEqualTo(OTHER_EXPERT);
		// The clock starts on creation, so the board shows a RAG status immediately.
		assertThat(created.getStageEnteredAt()).isNotNull();
		assertThat(created.getSlaStatus()).isEqualTo(SlaStatus.ON_TRACK);
		// Brand initials, year, six hex characters.
		assertThat(created.getCaseCode()).matches("IE-\\d{4}-[0-9A-F]{6}");

		verify(cases).save(any(Case.class));
	}

	@Test
	void theChecklistOpensFromTheServiceTypeTemplateAsRequired() {
		intake.intake(brand, contact("ghl-c-1", "anita@raolaw.example"));

		ArgumentCaptor<DocumentChecklistItem> seeded = ArgumentCaptor.forClass(DocumentChecklistItem.class);
		verify(checklistItems, org.mockito.Mockito.atLeastOnce()).save(seeded.capture());

		assertThat(seeded.getAllValues())
				.hasSameSizeAs(ChecklistTemplates.forService(ServiceType.EXPERT_OPINION_LETTER))
				.allSatisfy(item -> {
					assertThat(item.getStatus()).isEqualTo(ChecklistItemStatus.REQUIRED);
					assertThat(item.getBrandId()).isEqualTo(BRAND);
				})
				.extracting(DocumentChecklistItem::getLabel)
				.contains("CV or résumé");
	}

	@Test
	void theGmAndTheBrandsManagerGetALeadAlertAndNobodyElse() {
		intake.intake(brand, contact("ghl-c-1", "anita@raolaw.example"));

		ArgumentCaptor<Notification> raised = ArgumentCaptor.forClass(Notification.class);
		verify(notifications, org.mockito.Mockito.times(2)).save(raised.capture());

		assertThat(raised.getAllValues())
				.allSatisfy(notification -> {
					// A lead, not a pool arrival — nothing needs assigning until it is paid.
					assertThat(notification.getType()).isEqualTo(NotificationType.NEW_LEAD);
					assertThat(notification.getBrandId()).isEqualTo(BRAND);
					assertThat(notification.getBody()).contains("International Evaluations", "Anita Rao");
				})
				.extracting(Notification::getRecipientId)
				.containsExactlyInAnyOrder(GM_ID, BRAND_MANAGER_ID);
	}

	@Test
	void theSameContactIsSyncedRatherThanDuplicated() {
		ContactSnapshot existing = new ContactSnapshot(BRAND, "ghl-c-1");
		given(contacts.findByBrandIdAndGhlContactId(BRAND, "ghl-c-1")).willReturn(Optional.of(existing));

		Case created = intake.intake(brand, contact("ghl-c-1", "anita@raolaw.example"));

		assertThat(existing.getFullName()).isEqualTo("Anita Rao");
		assertThat(existing.getSyncedAt()).isNotNull();
		assertThat(created.getContactId()).isEqualTo(existing.getId());
		// Only ever one snapshot per brand per person, however many orders they place.
		verify(contacts).save(existing);
		verify(contacts, never()).findByBrandIdAndEmailIgnoreCase(any(), any());
	}

	@Test
	void withNoGhlIdTheContactIsMatchedOnEmail() {
		intake.intake(brand, contact(null, "anita@raolaw.example"));

		verify(contacts).findByBrandIdAndEmailIgnoreCase(BRAND, "anita@raolaw.example");
		verify(contacts, never()).findByBrandIdAndGhlContactId(any(), any());
	}

	@Test
	void creationIsAuditedAgainstTheResolvedBrandAndPublishesBothEvents() {
		Case created = intake.intake(brand, contact("ghl-c-1", "anita@raolaw.example"));

		// The brand is passed explicitly because a webhook has no authenticated caller —
		// and it must be the one the endpoint token resolved to, never the payload's.
		verify(audit).recordSystemEvent(eq(BRAND), eq("CASE"), eq(created.getId()),
				eq(AuditAction.CREATED), isNull(), any());

		ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
		verify(events, org.mockito.Mockito.times(2)).publishEvent(published.capture());
		assertThat(published.getAllValues())
				.extracting(event -> ((CaseEvents.CaseEvent) event).type())
				.containsExactly(CaseEvents.Type.CASE_CREATED, CaseEvents.Type.CHECKLIST_REQUESTED);
		// Nothing role-restricted rides on an event that leaves the building.
		assertThat(published.getAllValues())
				.allSatisfy(event -> assertThat(((CaseEvents.CaseEvent) event).brandId()).isEqualTo(BRAND));
	}

	/**
	 * One open case per contact per service. A GHL workflow re-firing must refresh, not
	 * duplicate — and above all must not undo work already done on the case.
	 */
	@Test
	void aSecondDeliveryForTheSameContactAndServiceRefreshesRatherThanDuplicating() {
		Case inFlight = new Case(BRAND, "IE-2026-ABCDEF", Stage.EXPERT_SIGNING);
		inFlight.setContactId(CONTACT_ID);
		inFlight.setServiceType(ServiceType.EXPERT_OPINION_LETTER);
		inFlight.setAssignedPm(UUID.randomUUID());
		inFlight.setPaid(true);
		given(cases.findFirstByBrandIdAndContactIdAndServiceTypeAndCurrentStageNotOrderByCreatedAtDesc(
				eq(BRAND), any(), eq(ServiceType.EXPERT_OPINION_LETTER), eq(Stage.CLOSED)))
				.willReturn(Optional.of(inFlight));

		Case result = intake.intake(brand, contact("ghl-c-1", "anita@raolaw.example"));

		assertThat(result).isSameAs(inFlight);
		// Progress is untouched: no reset, no un-paying, no lost assignment.
		assertThat(result.getCurrentStage()).isEqualTo(Stage.EXPERT_SIGNING);
		assertThat(result.isPaid()).isTrue();
		assertThat(result.getAssignedPm()).isNotNull();
		// No second checklist, no second lead alert, and nothing in the lifecycle happened.
		verify(checklistItems, never()).save(any());
		verify(notifications, never()).save(any());
		verify(events, never()).publishEvent(any(Object.class));
		verify(audit).recordSystemEvent(eq(BRAND), eq("CASE"), any(), eq(AuditAction.UPDATED), any(), any());
	}

	@Test
	void aDifferentServiceForTheSameContactIsItsOwnCase() {
		// Nothing open for TRANSLATION, even though the contact has an EOL case.
		given(cases.findFirstByBrandIdAndContactIdAndServiceTypeAndCurrentStageNotOrderByCreatedAtDesc(
				eq(BRAND), any(), eq(ServiceType.TRANSLATION), eq(Stage.CLOSED)))
				.willReturn(Optional.empty());

		Case created = intake.intake(brand,
				contact("ghl-c-1", "anita@raolaw.example", ServiceType.TRANSLATION, false));

		assertThat(created.getServiceType()).isEqualTo(ServiceType.TRANSLATION);
		verify(cases).save(any(Case.class));
		// Its own checklist, from the TRANSLATION template.
		verify(checklistItems, org.mockito.Mockito.atLeastOnce()).save(any());
	}

	/** The lookup excludes CLOSED, so a client coming back later starts a fresh case. */
	@Test
	void theClosedStageIsExcludedFromTheOpenCaseLookup() {
		intake.intake(brand, contact("ghl-c-1", "anita@raolaw.example"));

		verify(cases).findFirstByBrandIdAndContactIdAndServiceTypeAndCurrentStageNotOrderByCreatedAtDesc(
				eq(BRAND), any(), eq(ServiceType.EXPERT_OPINION_LETTER), eq(Stage.CLOSED));
	}

	/** GHL sometimes already knows the contact paid; the case then skips the lead state. */
	@Test
	void aContactGhlAlreadyKnowsIsPaidArrivesPaidAndAlertsThePool() {
		Case created = intake.intake(brand,
				contact("ghl-c-1", "anita@raolaw.example", ServiceType.EXPERT_OPINION_LETTER, true));

		assertThat(created.isPaid()).isTrue();
		assertThat(created.getPaidAt()).isNotNull();

		ArgumentCaptor<Notification> raised = ArgumentCaptor.forClass(Notification.class);
		verify(notifications, org.mockito.Mockito.times(4)).save(raised.capture());
		assertThat(raised.getAllValues()).extracting(Notification::getType)
				.containsOnly(NotificationType.NEW_LEAD, NotificationType.NEW_CASE_IN_POOL);

		assertThat(publishedTypes()).contains(CaseEvents.Type.CASE_PAID);
	}

	private List<CaseEvents.Type> publishedTypes() {
		ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
		verify(events, org.mockito.Mockito.atLeastOnce()).publishEvent(published.capture());
		return published.getAllValues().stream()
				.map(CaseEvents.CaseEvent.class::cast)
				.map(CaseEvents.CaseEvent::type)
				.toList();
	}

	@Test
	void aSingleWordBrandSlugStillYieldsAPrefix() {
		given(brand.getSlug()).willReturn("xpertsportal");

		assertThat(intake.intake(brand, contact("ghl-c-2", "x@example.com")).getCaseCode())
				.matches("XP-\\d{4}-[0-9A-F]{6}");
	}
}
