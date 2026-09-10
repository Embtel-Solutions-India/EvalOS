package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.common.AmbiguousCaseException;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutPayment;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.integration.DocumentStore;
import com.ie.evalos.repository.CaseDocumentRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.PayoutPaymentRepository;
import com.ie.evalos.security.PortalPrincipal;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit 35, D1 and D6: what a <strong>party</strong>-scoped token reaches, and what it must not.
 *
 * <p>The case-scoped token's behaviour is not re-asserted here — {@code PortalCaseServiceTest} and
 * {@code ExpertPortalServiceTest} already own it, and "nothing regresses" is a claim those two make
 * by continuing to pass. This file is only about the credential D1 added.
 *
 * <p><strong>The refusals are the point.</strong> A party token widens what one link opens, so
 * every test below that asserts an exception is asserting the boundary that widening created:
 * another contact's case, another brand's case, another expert's assignment, and the ambiguity that
 * would otherwise let somebody approve the wrong draft.
 */
class PartyScopedPortalAccessTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID OTHER_BRAND = UUID.randomUUID();
	private static final String CONTACT = "ghl-contact-abc";
	private static final UUID CONTACT_ROW = UUID.randomUUID();
	private static final UUID EXPERT = UUID.randomUUID();

	private final CaseRepository cases = mock(CaseRepository.class);
	private final ContactSnapshotRepository contacts = mock(ContactSnapshotRepository.class);
	private final DocumentChecklistItemRepository checklistItems = mock(DocumentChecklistItemRepository.class);
	private final CaseDocumentRepository documents = mock(CaseDocumentRepository.class);
	private final DocumentStore store = mock(DocumentStore.class);
	private final AuditService audit = mock(AuditService.class);
	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final SlaCalculator sla = mock(SlaCalculator.class);
	private final PayoutLedgerRepository payouts = mock(PayoutLedgerRepository.class);
	private final PayoutPaymentRepository payments = mock(PayoutPaymentRepository.class);
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final PortalCaseService clientPortal = new PortalCaseService(
			cases, contacts, lifecycle, checklistItems, documents, store, audit);

	private final ExpertPortalService expertPortal = new ExpertPortalService(
			cases, contacts, experts, payouts, payments, checklistItems, documents, lifecycle, sla, store, audit);

	// --- fixtures ------------------------------------------------------------

	private static Case caseAt(UUID brandId, String reference, Stage stage) {
		Case subject = new Case(brandId, reference, stage);
		ReflectionTestUtils.setField(subject, "id", UUID.randomUUID());
		subject.setServiceType(ServiceType.EXPERT_OPINION_LETTER);
		return subject;
	}

	private static PortalPrincipal clientParty(String ghlContactId) {
		return new PortalPrincipal(UUID.randomUUID(), BRAND, null, PortalAudience.CLIENT, null, ghlContactId);
	}

	private static PortalPrincipal expertParty(UUID expertId) {
		return new PortalPrincipal(UUID.randomUUID(), BRAND, null, PortalAudience.EXPERT, expertId, null);
	}

	private void contactResolves() {
		ContactSnapshot snapshot = new ContactSnapshot(BRAND, CONTACT);
		// The id is @GeneratedValue with no setter, and the case points at it — an unsaved
		// entity's null id would make the ownership check fail for the wrong reason.
		ReflectionTestUtils.setField(snapshot, "id", CONTACT_ROW);
		given(contacts.findByBrandIdAndGhlContactId(BRAND, CONTACT)).willReturn(Optional.of(snapshot));
		given(contacts.findById(CONTACT_ROW)).willReturn(Optional.of(snapshot));
	}

	// --- D1: the client's list ------------------------------------------------

	@Test
	void aClientPartyTokenListsEveryCaseThatContactHas() {
		contactResolves();
		Case first = caseAt(BRAND, "IE-2026-0001", Stage.DOC_COLLECTION);
		Case second = caseAt(BRAND, "IE-2026-0002", Stage.DELIVERED);
		given(cases.findByBrandIdAndContactIdOrderByCreatedAtDesc(BRAND, CONTACT_ROW))
				.willReturn(List.of(first, second));

		List<PortalCaseService.ClientCaseSummary> mine = clientPortal.clientCases(clientParty(CONTACT));

		// Both, and a delivered one among them: the closed case is the one they came back for, and
		// a list that hid it would look like lost work.
		assertThat(mine).extracting(PortalCaseService.ClientCaseSummary::caseReference)
				.containsExactly("IE-2026-0001", "IE-2026-0002");
		// D5's words, served by EvalOS, with the flag as a field rather than a suffix to parse.
		assertThat(mine.get(0).step()).isEqualTo("Upload");
		assertThat(mine.get(0).actionRequired()).isTrue();
		assertThat(mine.get(1).step()).isEqualTo("Ready to download");
		assertThat(mine.get(1).actionRequired()).isFalse();
	}

	@Test
	void anotherContactInTheSameBrandIsNotOnTheList() {
		// The scope is the contact, not the brand. This is the failure that would turn one client's
		// link into a directory of the brand's work.
		given(contacts.findByBrandIdAndGhlContactId(BRAND, "ghl-somebody-else")).willReturn(Optional.empty());

		assertThat(clientPortal.clientCases(clientParty("ghl-somebody-else"))).isEmpty();
	}

	@Test
	void theSameContactInAnotherBrandResolvesSeparately() {
		// A contact may legitimately be a client of two brands (V16). The token carries one brand,
		// and the read must not reach across — invariant 1, on a credential that has no
		// TenantContext behind it to enforce it.
		contactResolves();
		given(contacts.findByBrandIdAndGhlContactId(OTHER_BRAND, CONTACT)).willReturn(Optional.empty());

		PortalPrincipal otherBrandToken = new PortalPrincipal(
				UUID.randomUUID(), OTHER_BRAND, null, PortalAudience.CLIENT, null, CONTACT);

		assertThat(clientPortal.clientCases(otherBrandToken)).isEmpty();
	}

	@Test
	void aCaseScopedTokenIsRefusedTheList() {
		PortalPrincipal caseToken = new PortalPrincipal(
				UUID.randomUUID(), BRAND, UUID.randomUUID(), PortalAudience.CLIENT, null);

		assertThatThrownBy(() -> clientPortal.clientCases(caseToken))
				.isInstanceOf(ForbiddenException.class);
	}

	// --- D1: naming a case, and the 409 ---------------------------------------

	@Test
	void aPartyTokenReachesItsOwnCaseById() {
		contactResolves();
		Case mine = caseAt(BRAND, "IE-2026-0001", Stage.CLIENT_REVIEW);
		mine.setContactId(CONTACT_ROW);
		given(cases.findById(mine.getId())).willReturn(Optional.of(mine));

		assertThat(clientPortal.clientView(clientParty(CONTACT), mine.getId()).caseReference())
				.isEqualTo("IE-2026-0001");
	}

	@Test
	void aPartyTokenIsRefusedAnotherContactsCaseWithForbiddenNotNotFound() {
		contactResolves();
		Case theirs = caseAt(BRAND, "IE-2026-0009", Stage.CLIENT_REVIEW);
		theirs.setContactId(UUID.randomUUID());
		given(cases.findById(theirs.getId())).willReturn(Optional.of(theirs));
		given(contacts.findById(theirs.getContactId())).willReturn(Optional.empty());

		// 403 rather than 404: a 404 here would confirm the difference between "no such case" and
		// "not yours", which is how one link becomes a way to count the brand's cases.
		assertThatThrownBy(() -> clientPortal.clientView(clientParty(CONTACT), theirs.getId()))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void aPartyTokenOnASingleCaseRouteResolvesWhenThereIsOnlyOneCase() {
		contactResolves();
		Case only = caseAt(BRAND, "IE-2026-0001", Stage.CLIENT_REVIEW);
		only.setContactId(CONTACT_ROW);
		given(cases.findByBrandIdAndContactIdOrderByCreatedAtDesc(BRAND, CONTACT_ROW)).willReturn(List.of(only));
		given(cases.findById(only.getId())).willReturn(Optional.of(only));

		assertThat(clientPortal.clientView(clientParty(CONTACT)).caseReference()).isEqualTo("IE-2026-0001");
	}

	@Test
	void aPartyTokenOnASingleCaseRouteRefusesToPickBetweenSeveral() {
		contactResolves();
		given(cases.findByBrandIdAndContactIdOrderByCreatedAtDesc(BRAND, CONTACT_ROW))
				.willReturn(List.of(caseAt(BRAND, "IE-2026-0001", Stage.CLIENT_REVIEW),
						caseAt(BRAND, "IE-2026-0002", Stage.CLIENT_REVIEW)));

		// The single-case routes include approve. Guessing here approves a draft the client was not
		// looking at, and the letter goes on toward delivery with no undo that reaches them.
		assertThatThrownBy(() -> clientPortal.clientView(clientParty(CONTACT)))
				.isInstanceOf(AmbiguousCaseException.class)
				.hasMessageContaining("say which one");
	}

	// --- D1: the expert's list -------------------------------------------------

	@Test
	void anExpertPartyTokenListsOnlyItsOwnAssignments() {
		Case mine = caseAt(BRAND, "IE-2026-0004", Stage.EXPERT_SIGNING);
		mine.setExpertId(EXPERT);
		given(cases.findByBrandIdAndExpertIdOrderByCreatedAtDesc(BRAND, EXPERT)).willReturn(List.of(mine));

		List<ExpertPortalService.ExpertCaseSummary> assignments = expertPortal.expertCases(expertParty(EXPERT));

		assertThat(assignments).extracting(ExpertPortalService.ExpertCaseSummary::caseReference)
				.containsExactly("IE-2026-0004");
		assertThat(assignments.get(0).step()).isEqualTo("Sign");
		assertThat(assignments.get(0).actionRequired()).isTrue();
	}

	@Test
	void anExpertIsRefusedAnotherExpertsCaseById() {
		Case theirs = caseAt(BRAND, "IE-2026-0007", Stage.EXPERT_SIGNING);
		theirs.setExpertId(UUID.randomUUID());
		given(cases.findById(theirs.getId())).willReturn(Optional.of(theirs));

		// V37's bind, inherited: the token names an expert, and a case whose expert is somebody
		// else is refused whether or not anybody remembered to revoke the link.
		assertThatThrownBy(() -> expertPortal.view(expertParty(EXPERT), theirs.getId()))
				.isInstanceOf(ForbiddenException.class);
	}

	// --- D6: the payout rows ----------------------------------------------------

	@Test
	void anExpertReadsTheirOwnPayoutRowsWithTheSettlementDateWhenThereIsOne() {
		Case subject = caseAt(BRAND, "IE-2026-0004", Stage.DELIVERED);
		UUID paymentId = UUID.randomUUID();

		Instant paidOn = Instant.parse("2026-09-01T00:00:00Z");

		PayoutLedger settled = new PayoutLedger(BRAND, subject.getId(), EXPERT,
				new BigDecimal("350.00"), "USD", paidOn);
		// Status and the payment link are set by the settlement flow, which is Unit 16b's and not
		// this test's subject — reflection rather than driving that whole path to reach one field.
		ReflectionTestUtils.setField(settled, "status", PayoutStatus.PAID);
		ReflectionTestUtils.setField(settled, "paymentId", paymentId);

		PayoutPayment payment = new PayoutPayment(BRAND, EXPERT, new BigDecimal("350.00"), "USD",
				"bank transfer", "REF-1", paidOn, null, UUID.randomUUID());

		given(payouts.findByBrandIdAndExpertIdOrderByCreatedAtDesc(BRAND, EXPERT)).willReturn(List.of(settled));
		given(cases.findById(subject.getId())).willReturn(Optional.of(subject));
		given(payments.findById(paymentId)).willReturn(Optional.of(payment));

		List<ExpertPortalService.ExpertPayoutRow> rows = expertPortal.payoutRows(expertParty(EXPERT));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).caseReference()).isEqualTo("IE-2026-0004");
		assertThat(rows.get(0).amount()).isEqualByComparingTo("350.00");
		assertThat(rows.get(0).currency()).isEqualTo("USD");
		assertThat(rows.get(0).status()).isEqualTo(PayoutStatus.PAID);
		assertThat(rows.get(0).settledOn()).isEqualTo(paidOn);
	}

	@Test
	void anUnsettledRowCarriesNoSettlementDateAndCostsNoSecondRead() {
		Case subject = caseAt(BRAND, "IE-2026-0005", Stage.EXPERT_SIGNING);
		// PENDING is the constructor's own default, so nothing is forced here.
		PayoutLedger owed = new PayoutLedger(BRAND, subject.getId(), EXPERT,
				new BigDecimal("350.00"), "USD", Instant.parse("2026-09-30T00:00:00Z"));

		given(payouts.findByBrandIdAndExpertIdOrderByCreatedAtDesc(BRAND, EXPERT)).willReturn(List.of(owed));
		given(cases.findById(subject.getId())).willReturn(Optional.of(subject));

		assertThat(expertPortal.payoutRows(expertParty(EXPERT)).get(0).settledOn()).isNull();
		// The payment table is not touched for a row that has no payment — the common case does not
		// pay for the rare one.
		org.mockito.Mockito.verifyNoInteractions(payments);
	}

	@Test
	void thePayoutRowCarriesNoPaymentDetailInAnyForm() throws Exception {
		Case subject = caseAt(BRAND, "IE-2026-0004", Stage.DELIVERED);
		PayoutLedger row = new PayoutLedger(BRAND, subject.getId(), EXPERT,
				new BigDecimal("350.00"), "USD", Instant.parse("2026-09-30T00:00:00Z"));

		given(payouts.findByBrandIdAndExpertIdOrderByCreatedAtDesc(BRAND, EXPERT)).willReturn(List.of(row));
		given(cases.findById(subject.getId())).willReturn(Optional.of(subject));

		String json = objectMapper.writeValueAsString(expertPortal.payoutRows(expertParty(EXPERT)));

		// Invariant 4, asserted on the serialized form rather than the field list: a nested DTO that
		// carried the secret would pass a field-name check and fail this one. Unit 14's and Unit
		// 15's whitelists are proved the same way.
		assertThat(json).doesNotContain("paymentDetail", "payment_detail", "paymentdetail");
		assertThat(json).contains("IE-2026-0004", "350.00", "USD");
	}

	@Test
	void aTokenThatNamesNoExpertReadsNoPayouts() {
		// V37's fail-closed rule reaches D6 too: a pre-column token is refused, not widened into
		// somebody's payment history.
		PortalPrincipal nameless = new PortalPrincipal(
				UUID.randomUUID(), BRAND, null, PortalAudience.EXPERT, null, null);

		assertThatThrownBy(() -> expertPortal.payoutRows(nameless))
				.isInstanceOf(ForbiddenException.class);
	}
}
