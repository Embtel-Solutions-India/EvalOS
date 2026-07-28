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
 * Handoff A below the transport: what one confirmed payment leaves behind. The
 * gateway's half — signatures, dedupe, archival — is asserted in
 * {@code InboundWebhookTest}.
 */
class CaseIntakeServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID GM_ID = UUID.randomUUID();
	private static final UUID BRAND_MANAGER_ID = UUID.randomUUID();
	private static final UUID OTHER_EXPERT = UUID.randomUUID();

	private final CaseRepository cases = mock(CaseRepository.class);
	private final ContactSnapshotRepository contacts = mock(ContactSnapshotRepository.class);
	private final DocumentChecklistItemRepository checklistItems = mock(DocumentChecklistItemRepository.class);
	private final NotificationRepository notifications = mock(NotificationRepository.class);
	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private final SlaCalculator sla = new SlaCalculator(new BusinessCalendar());
	private final CaseIntakeService intake = new CaseIntakeService(
			cases, contacts, checklistItems, notifications, teamMembers, audit, sla, events);

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

	private static CaseIntakeService.NewCase payment(String ghlContactId, String email) {
		return new CaseIntakeService.NewCase(
				new CaseIntakeService.ContactDetails(ghlContactId, "Anita Rao", email, "+1 555 0100",
						"Rao Immigration LLP", ClientType.ATTORNEY, SourceChannel.GOOGLE_ADS,
						"google", "cpc", "eb2-niw-q3"),
				ServiceType.EXPERT_OPINION_LETTER, null, VisaCategory.EB2_NIW, OTHER_EXPERT,
				new BigDecimal("1450.00"), Instant.now().plusSeconds(86_400),
				"https://drive.google.com/folder/abc", "INV-99123", "eb2-niw-q3");
	}

	@Test
	void aConfirmedPaymentCreatesOneCaseInThePool() {
		Case created = intake.intake(brand, payment("ghl-c-1", "anita@raolaw.example"));

		assertThat(created.getBrandId()).isEqualTo(BRAND);
		assertThat(created.getCurrentStage()).isEqualTo(Stage.DOC_COLLECTION);
		assertThat(created.getPoolStatus()).isEqualTo(PoolStatus.IN_POOL);
		assertThat(created.getExceptionState()).isEqualTo(ExceptionState.NONE);
		assertThat(created.getAssignedPm()).isNull();
		assertThat(created.getAssignedCm()).isNull();
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
		intake.intake(brand, payment("ghl-c-1", "anita@raolaw.example"));

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
	void theGmAndTheBrandsManagerAreNotifiedAndNobodyElse() {
		intake.intake(brand, payment("ghl-c-1", "anita@raolaw.example"));

		ArgumentCaptor<Notification> raised = ArgumentCaptor.forClass(Notification.class);
		verify(notifications, org.mockito.Mockito.times(2)).save(raised.capture());

		assertThat(raised.getAllValues())
				.allSatisfy(notification -> {
					assertThat(notification.getType()).isEqualTo(NotificationType.NEW_CASE_IN_POOL);
					assertThat(notification.getBrandId()).isEqualTo(BRAND);
					assertThat(notification.getBody()).contains("International Evaluations", "pool");
				})
				.extracting(Notification::getRecipientId)
				.containsExactlyInAnyOrder(GM_ID, BRAND_MANAGER_ID);
	}

	@Test
	void theSameContactIsSyncedRatherThanDuplicated() {
		ContactSnapshot existing = new ContactSnapshot(BRAND, "ghl-c-1");
		given(contacts.findByBrandIdAndGhlContactId(BRAND, "ghl-c-1")).willReturn(Optional.of(existing));

		Case created = intake.intake(brand, payment("ghl-c-1", "anita@raolaw.example"));

		assertThat(existing.getFullName()).isEqualTo("Anita Rao");
		assertThat(existing.getSyncedAt()).isNotNull();
		assertThat(created.getContactId()).isEqualTo(existing.getId());
		// Only ever one snapshot per brand per person, however many orders they place.
		verify(contacts).save(existing);
		verify(contacts, never()).findByBrandIdAndEmailIgnoreCase(any(), any());
	}

	@Test
	void withNoGhlIdTheContactIsMatchedOnEmail() {
		intake.intake(brand, payment(null, "anita@raolaw.example"));

		verify(contacts).findByBrandIdAndEmailIgnoreCase(BRAND, "anita@raolaw.example");
		verify(contacts, never()).findByBrandIdAndGhlContactId(any(), any());
	}

	@Test
	void creationIsAuditedAgainstTheResolvedBrandAndPublishesBothEvents() {
		Case created = intake.intake(brand, payment("ghl-c-1", "anita@raolaw.example"));

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

	@Test
	void aSingleWordBrandSlugStillYieldsAPrefix() {
		given(brand.getSlug()).willReturn("xpertsportal");

		assertThat(intake.intake(brand, payment("ghl-c-2", "x@example.com")).getCaseCode())
				.matches("XP-\\d{4}-[0-9A-F]{6}");
	}
}
