package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.GhlReference;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.GhlCustomFieldRepository;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 2026-10-30: a case's service type follows its deal's Service Requested; touched documents always stay. */
class CaseServiceTypeServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final String FIELD = "f-svc";

	private final CaseRepository cases = mock(CaseRepository.class);
	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final DocumentChecklistItemRepository checklist = mock(DocumentChecklistItemRepository.class);
	private final GhlCustomFieldRepository fields = mock(GhlCustomFieldRepository.class);
	private final OpportunityMirrorService mirror = mock(OpportunityMirrorService.class);
	private final AuditService audit = mock(AuditService.class);
	private final com.ie.evalos.repository.ContactSnapshotRepository contacts =
			mock(com.ie.evalos.repository.ContactSnapshotRepository.class);
	private final com.ie.evalos.repository.OpportunityRepository deals =
			mock(com.ie.evalos.repository.OpportunityRepository.class);
	private final CaseServiceTypeService service =
			new CaseServiceTypeService(cases, lifecycle, checklist, fields, mirror, audit, contacts, deals);

	private Case subject;

	@BeforeEach
	void setUp() {
		subject = new Case(BRAND, "IE-1", Stage.DOC_COLLECTION);
		ReflectionTestUtils.setField(subject, "id", CASE_ID);
		subject.setServiceType(ServiceType.CREDENTIAL_EVALUATION);
		subject.setGhlOpportunityId("opp-1");
		GhlReference.CustomField serviceField = mock(GhlReference.CustomField.class);
		given(serviceField.getFieldKey()).willReturn(OpportunityBoardService.SERVICE_FIELD);
		given(serviceField.getGhlId()).willReturn(FIELD);
		given(fields.findByBrandIdAndModelOrderByNameAsc(BRAND, ReferenceMirrorService.OPPORTUNITY_MODEL))
				.willReturn(List.of(serviceField));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	private void dealSays(String serviceRequested) {
		Opportunity deal = mock(Opportunity.class);
		given(deal.getBrandId()).willReturn(BRAND);
		given(deal.getCustomFields()).willReturn(Map.of(FIELD, serviceRequested));
		given(mirror.byGhlId("opp-1")).willReturn(Optional.of(deal));
	}

	private static DocumentChecklistItem item(String label) {
		return new DocumentChecklistItem(BRAND, CASE_ID, label, ChecklistItemStatus.REQUIRED);
	}

	@Test
	void ghlsLabelsMapCaseAndSpaceInsensitively() {
		assertThat(CaseServiceTypeService.fromLabel(" TN Visa Expert Opinion Letter ")).contains(ServiceType.TN_VISA);
		assertThat(CaseServiceTypeService.fromLabel("Immigration Business Plans")).isEmpty();
		assertThat(CaseServiceTypeService.fromLabel(null)).isEmpty();
	}

	@Test
	void anUntouchedCaseFollowsItsDealAndGetsTheNewChecklist() {
		dealSays("TN Visa Expert Opinion Letter");
		DocumentChecklistItem old = item("Passport or government photo ID");
		given(checklist.findByCaseId(CASE_ID)).willReturn(List.of(old));

		assertThat(service.reconcile(subject)).isTrue();

		assertThat(subject.getServiceType()).isEqualTo(ServiceType.TN_VISA);
		boolean oldInNew = ChecklistTemplates.forService(ServiceType.TN_VISA).contains(old.getLabel());
		verify(checklist, times(oldInNew ? 0 : 1)).delete(old);
		ArgumentCaptor<DocumentChecklistItem> added = ArgumentCaptor.forClass(DocumentChecklistItem.class);
		verify(checklist, times(ChecklistTemplates.forService(ServiceType.TN_VISA).size() - (oldInNew ? 1 : 0)))
				.save(added.capture());
		assertThat(added.getAllValues()).allMatch((i) -> i.getStatus() == ChecklistItemStatus.REQUIRED);
	}

	@Test
	void aCaseWhoseChecklistWasTouchedIsLeftForThePm() {
		dealSays("TN Visa Expert Opinion Letter");
		DocumentChecklistItem sent = item("Passport or government photo ID");
		sent.markSent(UUID.randomUUID(), Instant.now());
		given(checklist.findByCaseId(CASE_ID)).willReturn(List.of(sent));

		assertThat(service.reconcile(subject)).isFalse();
		assertThat(subject.getServiceType()).isEqualTo(ServiceType.CREDENTIAL_EVALUATION);
	}

	@Test
	void aCasePastDocumentCollectionIsNeverChangedBySweep() {
		dealSays("TN Visa Expert Opinion Letter");
		ReflectionTestUtils.setField(subject, "currentStage", Stage.DRAFT_IN_PROGRESS);

		assertThat(service.reconcile(subject)).isFalse();
		verify(checklist, never()).findByCaseId(any());
	}

	/**
	 * 2026-10-30, production: the webhook opened the case with no deal id, so the sweep found nothing. It now
	 * finds the deal through the case contact — the won one before a newer open one.
	 */
	@Test
	void aCaseWithNoDealIdFollowsItsContactsWonDeal() {
		subject.setGhlOpportunityId(null);
		UUID contactId = UUID.randomUUID();
		ReflectionTestUtils.setField(subject, "contactId", contactId);
		given(contacts.findById(contactId)).willReturn(Optional.of(new com.ie.evalos.domain.ContactSnapshot(BRAND, "c-ghl")));
		Opportunity won = mock(Opportunity.class);
		given(won.getStatus()).willReturn("won");
		given(won.getCustomFields()).willReturn(Map.of(FIELD, "TN Visa Expert Opinion Letter"));
		Opportunity newerOpen = mock(Opportunity.class);
		given(newerOpen.getStatus()).willReturn("open");
		given(newerOpen.getGhlCreatedAt()).willReturn(Instant.now());
		given(newerOpen.getCustomFields()).willReturn(Map.of(FIELD, "RFE Response"));
		given(deals.findByBrandIdAndGhlContactId(BRAND, "c-ghl")).willReturn(List.of(newerOpen, won));
		given(checklist.findByCaseId(CASE_ID)).willReturn(List.of(item("Passport or government photo ID")));

		assertThat(service.reconcile(subject)).isTrue();
		assertThat(subject.getServiceType()).isEqualTo(ServiceType.TN_VISA);
	}

	/** A type a PM chose is never overridden by the sweep, whatever the deal says. */
	@Test
	void aTypeAPersonChoseIsNotOverridden() {
		dealSays("TN Visa Expert Opinion Letter");
		subject.setServiceType(ServiceType.TRANSLATION);

		assertThat(service.reconcile(subject)).isFalse();
		assertThat(subject.getServiceType()).isEqualTo(ServiceType.TRANSLATION);
	}

	@Test
	void anUnmappedServiceLeavesTheCaseAlone() {
		dealSays("Immigration Business Plans");

		assertThat(service.reconcile(subject)).isFalse();
		assertThat(subject.getServiceType()).isEqualTo(ServiceType.CREDENTIAL_EVALUATION);
	}

	/** The PM's change on a case already under way: what was sent or uploaded stays, untouched extras go. */
	@Test
	void thePmsChangeKeepsTouchedDocumentsAndDropsUntouchedExtras() {
		StaffPrincipal pm = new StaffPrincipal(UUID.randomUUID(), "pm@ie.test", "PM", Role.PROJECT_MANAGER, BRAND,
				null, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(pm, null, pm.getAuthorities()));
		given(lifecycle.load(CASE_ID)).willReturn(subject);
		DocumentChecklistItem uploaded = item("Something only the old type needed");
		uploaded.markStatus(ChecklistItemStatus.UPLOADED);
		DocumentChecklistItem untouched = item("Another thing only the old type needed");
		given(checklist.findByCaseId(CASE_ID)).willReturn(List.of(uploaded, untouched));

		CaseServiceTypeService.Change change = service.change(CASE_ID, ServiceType.TN_VISA);

		assertThat(change.from()).isEqualTo(ServiceType.CREDENTIAL_EVALUATION);
		assertThat(subject.getServiceType()).isEqualTo(ServiceType.TN_VISA);
		verify(checklist, never()).delete(uploaded);
		verify(checklist).delete(untouched);
	}
}
