package com.ie.evalos.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.integration.MailTransport;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.service.MailTemplates.CaseUpdate;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Unit 64c (D58): which event mails whom, with which link — and when nothing goes out. */
class CaseMailListenerTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final UUID CONTACT_ID = UUID.randomUUID();
	private static final UUID EXPERT_ID = UUID.randomUUID();

	private final CaseRepository cases = mock(CaseRepository.class);
	private final ClientAccountRepository accounts = mock(ClientAccountRepository.class);
	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final DocumentChecklistItemRepository checklist = mock(DocumentChecklistItemRepository.class);
	private final com.ie.evalos.repository.ExpertCaseOfferRepository offers =
			mock(com.ie.evalos.repository.ExpertCaseOfferRepository.class);
	private final com.ie.evalos.repository.BrandRepository brands = mock(com.ie.evalos.repository.BrandRepository.class);
	private final ClientMailer mailer = mock(ClientMailer.class);
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
	private final Case subject = mock(Case.class);

	private final CaseMailListener listener;

	CaseMailListenerTest() {
		given(transactions.getTransaction(any())).willReturn(new SimpleTransactionStatus());
		listener = new CaseMailListener(cases, accounts, experts, checklist, offers, brands, mailer, transactions, BRAND,
				"https://client.test/", "https://expert.test");
		given(subject.getId()).willReturn(CASE_ID);
		given(subject.getBrandId()).willReturn(BRAND);
		given(subject.getCaseCode()).willReturn("IE-0001");
		given(subject.getServiceType()).willReturn(ServiceType.CREDENTIAL_EVALUATION);
		given(subject.getContactId()).willReturn(CONTACT_ID);
		given(cases.findById(CASE_ID)).willReturn(Optional.of(subject));
		ClientAccount client = new ClientAccount(BRAND, "ana@example.com", "CASE");
		client.setFirstName("Ana");
		given(accounts.findByBrandIdAndContactId(BRAND, CONTACT_ID)).willReturn(Optional.of(client));
		given(mailer.canReach(any())).willReturn(true);
		given(mailer.sendCaseUpdate(any(), any(), any(), any(), any(), anyInt(), any(), any())).willReturn(true);
	}

	private void fire(CaseEvents.Type type, UUID brand) {
		listener.on(new CaseEvents.CaseEvent(type, brand, CASE_ID, CONTACT_ID, null, null));
	}

	private static DocumentChecklistItem item(boolean sent, ChecklistItemStatus status) {
		DocumentChecklistItem item = mock(DocumentChecklistItem.class);
		given(item.isSent()).willReturn(sent);
		given(item.getStatus()).willReturn(status);
		return item;
	}

	@Test
	void theClientMailsDeepLinkToTheirCase() {
		for (var expected : java.util.Map.of(CaseEvents.Type.DRAFT_READY_FOR_CLIENT, CaseUpdate.DRAFT_READY,
				CaseEvents.Type.CASE_DELIVERED, CaseUpdate.DELIVERED).entrySet()) {
			fire(expected.getKey(), BRAND);
			verify(mailer).sendCaseUpdate(eq(new MailTransport.Recipient(BRAND, "ana@example.com")),
					eq(expected.getValue()), eq("Ana"), eq("Credential evaluation"), eq("IE-0001"), eq(0),
					eq(null), eq("https://client.test/cases/" + CASE_ID));
		}
	}

	/** The count is what is still owed: sent and not complete. Unsent items are not the client's yet. */
	@Test
	void theChecklistMailCountsWhatIsStillOwed() {
		List<DocumentChecklistItem> items = List.of(item(true, ChecklistItemStatus.REQUIRED),
				item(true, ChecklistItemStatus.INCORRECT), item(true, ChecklistItemStatus.APPROVED),
				item(false, ChecklistItemStatus.REQUIRED));
		given(checklist.findByCaseId(CASE_ID)).willReturn(items);

		fire(CaseEvents.Type.CHECKLIST_REQUESTED, BRAND);

		verify(mailer).sendCaseUpdate(any(), eq(CaseUpdate.CHECKLIST), any(), any(), any(), eq(2), any(), any());
	}

	@Test
	void nothingOwedMeansNoChecklistMail() {
		List<DocumentChecklistItem> items = List.of(item(true, ChecklistItemStatus.UPLOADED));
		given(checklist.findByCaseId(CASE_ID)).willReturn(items);

		fire(CaseEvents.Type.CHECKLIST_REQUESTED, BRAND);

		verify(mailer, never()).sendCaseUpdate(any(), any(), any(), any(), any(), anyInt(), any(), any());
	}

	@Test
	void theSigningMailGoesToTheCasesExpert() {
		given(subject.getExpertId()).willReturn(EXPERT_ID);
		Expert expert = mock(Expert.class);
		given(expert.getEmail()).willReturn("dr.okafor@example.com");
		given(expert.getFullName()).willReturn("Chidi Okafor");
		given(experts.findByIdAndBrandId(EXPERT_ID, BRAND)).willReturn(Optional.of(expert));

		fire(CaseEvents.Type.EXPERT_SENT_FOR_SIGNING, BRAND);

		verify(mailer).sendCaseUpdate(eq(new MailTransport.Recipient(BRAND, "dr.okafor@example.com")),
				eq(CaseUpdate.SIGNING), eq("Chidi Okafor"), any(), eq("IE-0001"), eq(0), eq(null),
				eq("https://expert.test/case?caseId=" + CASE_ID));
	}

	/** D67: the offer mail names the open offer's fee in the brand's currency; a stray offer is not it. */
	@Test
	void theOfferMailNamesTheOpenOffersFee() {
		given(subject.getExpertId()).willReturn(EXPERT_ID);
		Expert expert = mock(Expert.class);
		given(expert.getEmail()).willReturn("dr.okafor@example.com");
		given(experts.findByIdAndBrandId(EXPERT_ID, BRAND)).willReturn(Optional.of(expert));
		com.ie.evalos.domain.Brand brand = mock(com.ie.evalos.domain.Brand.class);
		given(brand.getCurrency()).willReturn("USD");
		given(brands.findById(BRAND)).willReturn(Optional.of(brand));
		com.ie.evalos.domain.ExpertCaseOffer stray = mock(com.ie.evalos.domain.ExpertCaseOffer.class);
		given(stray.getExpertId()).willReturn(UUID.randomUUID());
		given(stray.getFee()).willReturn(new java.math.BigDecimal("999"));
		com.ie.evalos.domain.ExpertCaseOffer open = mock(com.ie.evalos.domain.ExpertCaseOffer.class);
		given(open.getExpertId()).willReturn(EXPERT_ID);
		given(open.getFee()).willReturn(new java.math.BigDecimal("250"));
		List<com.ie.evalos.domain.ExpertCaseOffer> both = List.of(stray, open);
		given(offers.findByCaseIdAndOutcome(CASE_ID, com.ie.evalos.domain.OfferOutcome.OFFERED)).willReturn(both);

		fire(CaseEvents.Type.EXPERT_ASSIGNED, BRAND);

		verify(mailer).sendCaseUpdate(eq(new MailTransport.Recipient(BRAND, "dr.okafor@example.com")),
				eq(CaseUpdate.OFFER), any(), any(), eq("IE-0001"), eq(0), eq("USD 250.00"),
				eq("https://expert.test/case?caseId=" + CASE_ID));
	}

	@Test
	void anotherBrandAnUnmailedEventOrNoAccountSendsNothing() {
		fire(CaseEvents.Type.CASE_DELIVERED, UUID.randomUUID());
		fire(CaseEvents.Type.CASE_CREATED, BRAND);
		given(accounts.findByBrandIdAndContactId(BRAND, CONTACT_ID)).willReturn(Optional.empty());
		fire(CaseEvents.Type.CASE_DELIVERED, BRAND);

		verify(mailer, never()).sendCaseUpdate(any(), any(), any(), any(), any(), anyInt(), any(), any());
	}

	/** A mail failure never reaches the transition that published the event. */
	@Test
	void aFailingSendDoesNotThrow() {
		given(mailer.sendCaseUpdate(any(), any(), any(), any(), any(), anyInt(), any(), any()))
				.willThrow(new IllegalStateException("smtp down"));

		fire(CaseEvents.Type.CASE_DELIVERED, BRAND);
	}
}
