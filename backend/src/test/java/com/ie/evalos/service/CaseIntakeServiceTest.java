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
 * Handoff A below the transport: what one won GHL opportunity leaves behind. The
 * gateway's half — signatures, dedupe, archival — is asserted in
 * {@code InboundWebhookTest}.
 *
 * <p>The case that arrives here is already paid, because GHL invoiced and collected
 * before marking the opportunity Won. There is no staff step that makes it workable.
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
	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private final SlaCalculator sla = new SlaCalculator(new BusinessCalendar());
	private final CaseIntakeService intake = new CaseIntakeService(
			cases, contacts, checklistItems, audit, sla, events);

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

	private static CaseIntakeService.NewCase wonDeal(String ghlContactId, String email) {
		return wonDeal(ghlContactId, email, ServiceType.EXPERT_OPINION_LETTER, new BigDecimal("1450.00"));
	}

	private static CaseIntakeService.NewCase wonDeal(String ghlContactId, String email, ServiceType serviceType,
			BigDecimal amount) {
		return wonDeal(ghlContactId, email, serviceType, amount, "opp-4711");
	}

	/** Both halves of the deal are nullable: GHL sends neither unless the workflow adds them. */
	private static CaseIntakeService.NewCase wonDeal(String ghlContactId, String email, ServiceType serviceType,
			BigDecimal amount, String opportunityId) {
		return new CaseIntakeService.NewCase(
				new CaseIntakeService.ContactDetails(ghlContactId, "Anita Rao", email, "+1 555 0100",
						"Rao Immigration LLP", ClientType.ATTORNEY, SourceChannel.GOOGLE_ADS,
						"google", "cpc", "eb2-niw-q3"),
				serviceType, null, VisaCategory.EB2_NIW, OTHER_EXPERT, opportunityId,
				amount, Instant.now().plusSeconds(86_400), "INV-99123", "eb2-niw-q3",
				"Client needs this by the visa filing date — transcripts already with them.");
	}

	@Test
	void aWonOpportunityCreatesOnePaidCase() {
		Case created = intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example"));

		assertThat(created.getBrandId()).isEqualTo(BRAND);
		assertThat(created.getCurrentStage()).isEqualTo(Stage.DOC_COLLECTION);
		assertThat(created.getPoolStatus()).isEqualTo(PoolStatus.IN_POOL);
		assertThat(created.getExceptionState()).isEqualTo(ExceptionState.NONE);
		assertThat(created.getAssignedPm()).isNull();
		assertThat(created.getAssignedCm()).isNull();
		// Won is paid: GHL collected before the opportunity was marked Won, so the webhook
		// is the proof and no staff act records it.
		assertThat(created.isPaid()).isTrue();
		assertThat(created.getPaidAt()).isNotNull();
		// Paid but not delivered is still not earned — invariant 5 needs both.
		assertThat(RefundService.isRevenueRecognized(created)).isFalse();
		// The amount collected, and the opportunity Unit 18 will close with it.
		assertThat(created.getDealValue()).isEqualByComparingTo("1450.00");
		assertThat(created.getGhlOpportunityId()).isEqualTo("opp-4711");
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
		intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example"));

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

	/**
	 * Intake announces; it does not decide who hears. What this asserts is the *event* —
	 * one arrival, `case.created`, and no separate paid announcement, because a case can
	 * no longer exist before the money. Who receives it is
	 * {@code NotificationListenersTest}'s job.
	 */
	@Test
	void aPaidArrivalIsAnnouncedOnceAndNotAsASeparatePayment() {
		intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example"));

		assertThat(publishedTypes())
				.containsExactly(CaseEvents.Type.CASE_CREATED, CaseEvents.Type.CHECKLIST_REQUESTED)
				.doesNotContain(CaseEvents.Type.CASE_PAID);
	}

	/**
	 * The sequential half of the duplicate-contact defect: a first delivery with no GHL id
	 * stores a snapshot without one, and a later delivery *with* the id must find that row
	 * by email rather than inserting a second. The lookups used to be exclusive returns,
	 * so the email branch was unreachable whenever an id was present.
	 */
	@Test
	void aDeliveryCarryingAnIdFindsTheRowThatWasStoredWithoutOne() {
		ContactSnapshot idless = new ContactSnapshot(BRAND, null);
		given(contacts.findByBrandIdAndGhlContactId(BRAND, "ghl-c-1")).willReturn(Optional.empty());
		given(contacts.findByBrandIdAndEmailIgnoreCase(BRAND, "anita@raolaw.example"))
				.willReturn(Optional.of(idless));

		intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example"));

		// The existing row is reused, and repaired with the id so it stops depending on
		// the email matching forever.
		verify(contacts).save(idless);
		assertThat(idless.getGhlContactId()).isEqualTo("ghl-c-1");
	}

	/** Write-once: a second GHL contact sharing an email cannot take over the first's row. */
	@Test
	void anExistingGhlIdIsNeverOverwritten() {
		ContactSnapshot owned = new ContactSnapshot(BRAND, "ghl-original");
		given(contacts.findByBrandIdAndGhlContactId(BRAND, "ghl-impostor")).willReturn(Optional.empty());
		given(contacts.findByBrandIdAndEmailIgnoreCase(BRAND, "anita@raolaw.example"))
				.willReturn(Optional.of(owned));

		intake.intake(brand, wonDeal("ghl-impostor", "anita@raolaw.example"));

		assertThat(owned.getGhlContactId()).isEqualTo("ghl-original");
	}

	@Test
	void theSameContactIsSyncedRatherThanDuplicated() {
		ContactSnapshot existing = new ContactSnapshot(BRAND, "ghl-c-1");
		given(contacts.findByBrandIdAndGhlContactId(BRAND, "ghl-c-1")).willReturn(Optional.of(existing));

		Case created = intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example"));

		assertThat(existing.getFullName()).isEqualTo("Anita Rao");
		assertThat(existing.getSyncedAt()).isNotNull();
		assertThat(created.getContactId()).isEqualTo(existing.getId());
		// Only ever one snapshot per brand per person, however many orders they place.
		verify(contacts).save(existing);
		verify(contacts, never()).findByBrandIdAndEmailIgnoreCase(any(), any());
	}

	@Test
	void withNoGhlIdTheContactIsMatchedOnEmail() {
		intake.intake(brand, wonDeal(null, "anita@raolaw.example"));

		verify(contacts).findByBrandIdAndEmailIgnoreCase(BRAND, "anita@raolaw.example");
		verify(contacts, never()).findByBrandIdAndGhlContactId(any(), any());
	}

	/**
	 * The repair path the email fall-through exists for, and the reason the guard below
	 * checks for a *conflict* rather than simply refusing every email match: a first
	 * delivery with no GHL id leaves a snapshot with none, and the next delivery — which
	 * does carry one — has to find that row by email and backfill the id onto it. Without
	 * this the id never lands and every later delivery re-matches by email, which works
	 * right up until the email changes and then it is a second contact again.
	 */
	@Test
	void anIdlessRowFoundByEmailIsAdoptedAndBackfilled() {
		ContactSnapshot idless = new ContactSnapshot(BRAND, null);
		given(contacts.findByBrandIdAndGhlContactId(BRAND, "ghl-c-1")).willReturn(Optional.empty());
		given(contacts.findByBrandIdAndEmailIgnoreCase(BRAND, "anita@raolaw.example"))
				.willReturn(Optional.of(idless));

		Case created = intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example"));

		assertThat(idless.getGhlContactId()).isEqualTo("ghl-c-1");
		assertThat(created.getContactId()).isEqualTo(idless.getId());
		verify(contacts).save(idless);
	}

	/**
	 * <strong>Email never outranks a GHL id.</strong> Two distinct GHL contacts can share
	 * an inbox — a firm's office address is the obvious case — and the row found by email
	 * already names a different client. Adopting it would attach this paid case to
	 * <em>them</em>, and overwrite their name and phone with this client's on the way past;
	 * `linkGhlContact` is write-once, so the row would keep the wrong id and every later
	 * delivery would land on it again. A wrong merge is worse than a duplicate: the
	 * duplicate is visible, the merge looks like an ordinary case.
	 *
	 * <p>`V27` is the other half — it narrows the email uniqueness index to rows without a
	 * GHL id, so the insert this refusal forces is actually allowed to land.
	 */
	@Test
	void anEmailMatchNamingADifferentGhlContactIsRefused() {
		ContactSnapshot sharedInbox = new ContactSnapshot(BRAND, "ghl-c-OTHER");
		sharedInbox.syncFromGhl("Marcus Vale", "office@raolaw.example", "+1 555 0199", "Rao Immigration LLP",
				ClientType.ATTORNEY, SourceChannel.REFERRAL, null, null, null);
		given(contacts.findByBrandIdAndGhlContactId(BRAND, "ghl-c-1")).willReturn(Optional.empty());
		given(contacts.findByBrandIdAndEmailIgnoreCase(BRAND, "office@raolaw.example"))
				.willReturn(Optional.of(sharedInbox));

		Case created = intake.intake(brand, wonDeal("ghl-c-1", "office@raolaw.example"));

		// The other client's row is untouched — not adopted, not overwritten, not re-linked.
		assertThat(sharedInbox.getGhlContactId()).isEqualTo("ghl-c-OTHER");
		assertThat(sharedInbox.getFullName()).isEqualTo("Marcus Vale");

		ArgumentCaptor<ContactSnapshot> saved = ArgumentCaptor.forClass(ContactSnapshot.class);
		verify(contacts).save(saved.capture());
		assertThat(saved.getValue()).isNotSameAs(sharedInbox);
		assertThat(saved.getValue().getGhlContactId()).isEqualTo("ghl-c-1");
		assertThat(saved.getValue().getFullName()).isEqualTo("Anita Rao");
		assertThat(created.getContactId()).isEqualTo(saved.getValue().getId());
	}

	/**
	 * The mirror of the refusal: a delivery carrying no GHL id has no identity to assert,
	 * so it must still match on email even when the row it finds holds one. Refusing here
	 * would insert a second snapshot for a client EvalOS can already name.
	 */
	@Test
	void aDeliveryWithNoGhlIdStillMatchesARowThatHasOne() {
		ContactSnapshot known = new ContactSnapshot(BRAND, "ghl-c-1");
		given(contacts.findByBrandIdAndEmailIgnoreCase(BRAND, "anita@raolaw.example"))
				.willReturn(Optional.of(known));

		Case created = intake.intake(brand, wonDeal(null, "anita@raolaw.example"));

		assertThat(created.getContactId()).isEqualTo(known.getId());
		assertThat(known.getGhlContactId()).isEqualTo("ghl-c-1");
		verify(contacts).save(known);
	}

	@Test
	void creationIsAuditedAgainstTheResolvedBrandAndPublishesBothEvents() {
		Case created = intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example"));

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

		Case result = intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example"));

		assertThat(result).isSameAs(inFlight);
		// Progress is untouched: no reset, no un-paying, no lost assignment.
		assertThat(result.getCurrentStage()).isEqualTo(Stage.EXPERT_SIGNING);
		assertThat(result.isPaid()).isTrue();
		assertThat(result.getAssignedPm()).isNotNull();
		// No second checklist, and nothing in the lifecycle happened — so no event, which
		// is also what stops Unit 06 raising a second alert.
		verify(checklistItems, never()).save(any());
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
				wonDeal("ghl-c-1", "anita@raolaw.example", ServiceType.TRANSLATION, new BigDecimal("300.00")));

		assertThat(created.getServiceType()).isEqualTo(ServiceType.TRANSLATION);
		verify(cases).save(any(Case.class));
		// Its own checklist, from the TRANSLATION template.
		verify(checklistItems, org.mockito.Mockito.atLeastOnce()).save(any());
	}

	/** The lookup excludes CLOSED, so a client coming back later starts a fresh case. */
	@Test
	void theClosedStageIsExcludedFromTheOpenCaseLookup() {
		intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example"));

		verify(cases).findFirstByBrandIdAndContactIdAndServiceTypeAndCurrentStageNotOrderByCreatedAtDesc(
				eq(BRAND), any(), eq(ServiceType.EXPERT_OPINION_LETTER), eq(Stage.CLOSED));
	}

	/**
	 * The one deliberate exception to refresh's fill-only rule, and the reason it exists:
	 * deleting {@code markPaid} removed the only other writer of {@code deal_value}, so
	 * without this a case whose amount changed in GHL would keep the first figure forever
	 * with nothing anywhere able to fix it — and that figure feeds revenue recognition.
	 * GHL owns the amount, so the latest won-opportunity figure wins.
	 */
	@Test
	void aChangedAmountOverwritesOnRefreshBecauseNothingElseCanCorrectIt() {
		Case inFlight = openCaseWorthNineHundred();

		Case result = intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example",
				ServiceType.EXPERT_OPINION_LETTER, new BigDecimal("1725.50")));

		assertThat(result.getDealValue()).isEqualByComparingTo("1725.50");
		// Only the figure. `paid_at` is write-once — the moment the money landed does not
		// change, and re-stamping it would lose it. One value, never a running total, so a
		// correction cannot double-count.
		assertThat(result.getPaidAt()).isEqualTo(inFlight.getPaidAt());
	}

	/**
	 * The amount and the opportunity it came from are two halves of one fact and must move
	 * together. Writing only the amount let a case carry opp-B's money under opp-A's id — and
	 * since Unit 18 closes whichever opportunity that column names, the wrong deal gets closed
	 * in GHL while the paid one stays open. `V24`'s index cannot catch it: no second case is
	 * created, so the refresh path never reaches the constraint.
	 */
	@Test
	void theAmountAndTheOpportunityItCameFromMoveTogether() {
		Case inFlight = openCaseWorthNineHundred();
		inFlight.setGhlOpportunityId("opp-first");

		Case result = intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example",
				ServiceType.EXPERT_OPINION_LETTER, new BigDecimal("1725.50")));

		assertThat(result.getDealValue()).isEqualByComparingTo("1725.50");
		assertThat(result.getGhlOpportunityId())
				.as("the id follows the money it arrived with, never lags a delivery behind")
				.isEqualTo("opp-4711");
	}

	/**
	 * The other side of "they move together": a delivery that names neither leaves both
	 * alone. GHL's Custom Webhook contributes no money field of its own, so a workflow that
	 * has not been told about the deal sends nulls — and overwriting with those would blank
	 * an amount that feeds revenue recognition and the opportunity id Unit 18 closes on,
	 * from a delivery that never claimed anything about either.
	 */
	@Test
	void aDeliveryCarryingNoDealLeavesTheOneAlreadyOnTheCase() {
		Case inFlight = openCaseWorthNineHundred();
		inFlight.setGhlOpportunityId("opp-first");

		Case result = intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example",
				ServiceType.EXPERT_OPINION_LETTER, null, null));

		assertThat(result.getDealValue()).isEqualByComparingTo("900.00");
		assertThat(result.getGhlOpportunityId()).isEqualTo("opp-first");
	}

	/**
	 * A money change that reads as a no-op edit is worse than a noisy one. The snapshot either
	 * side of a refresh omits `deal_value` on purpose — it is role-restricted and
	 * `CaseTimelineService` shows the note to every role that may read the case — so without
	 * this the trail records an UPDATED row with identical before and after. The note says
	 * *that* the figure moved and never what to; the figures are in the `webhook_event` archive.
	 */
	@Test
	void aCorrectedAmountIsVisibleInTheTrailWithoutPuttingTheFigureInIt() {
		openCaseWorthNineHundred();

		intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example",
				ServiceType.EXPERT_OPINION_LETTER, new BigDecimal("1725.50")));

		ArgumentCaptor<CaseLifecycleService.CaseSnapshot> after =
				ArgumentCaptor.forClass(CaseLifecycleService.CaseSnapshot.class);
		verify(audit).recordSystemEvent(eq(BRAND), eq("CASE"), any(), eq(AuditAction.UPDATED),
				any(), after.capture());

		assertThat(after.getValue().note())
				.contains("deal value corrected")
				.doesNotContain("1725.50", "900.00");
	}

	/** An unchanged amount is not a correction, so the trail does not claim one. */
	@Test
	void anUnchangedAmountIsNotReportedAsACorrection() {
		openCaseWorthNineHundred();

		intake.intake(brand, wonDeal("ghl-c-1", "anita@raolaw.example",
				ServiceType.EXPERT_OPINION_LETTER, new BigDecimal("900.00")));

		ArgumentCaptor<CaseLifecycleService.CaseSnapshot> after =
				ArgumentCaptor.forClass(CaseLifecycleService.CaseSnapshot.class);
		verify(audit).recordSystemEvent(eq(BRAND), eq("CASE"), any(), eq(AuditAction.UPDATED),
				any(), after.capture());

		assertThat(after.getValue().note()).doesNotContain("corrected");
	}

	/** An open, paid case for the standard contact and service, sold for 900. */
	private Case openCaseWorthNineHundred() {
		Case inFlight = new Case(BRAND, "IE-2026-ABCDEF", Stage.DRAFT_IN_PROGRESS);
		inFlight.setContactId(CONTACT_ID);
		inFlight.setServiceType(ServiceType.EXPERT_OPINION_LETTER);
		inFlight.setDealValue(new BigDecimal("900.00"));
		inFlight.setPaid(true);
		inFlight.setPaidAt(Instant.now().minusSeconds(259_200));
		given(cases.findFirstByBrandIdAndContactIdAndServiceTypeAndCurrentStageNotOrderByCreatedAtDesc(
				eq(BRAND), any(), eq(ServiceType.EXPERT_OPINION_LETTER), eq(Stage.CLOSED)))
				.willReturn(Optional.of(inFlight));
		return inFlight;
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

		assertThat(intake.intake(brand, wonDeal("ghl-c-2", "x@example.com")).getCaseCode())
				.matches("XP-\\d{4}-[0-9A-F]{6}");
	}
}
