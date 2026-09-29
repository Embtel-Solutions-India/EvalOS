package com.ie.evalos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.PayoutPaymentRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.PayoutRegisterService.RegisterStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Unit 65: the Payouts module's reads — one row per priced offer or payout, totals, overview. */
class PayoutRegisterServiceTest {

	private static final UUID IE = UUID.randomUUID();
	private static final UUID XP = UUID.randomUUID();
	private static final UUID EXPERT = UUID.randomUUID();

	private final ExpertCaseOfferRepository offers = mock(ExpertCaseOfferRepository.class);
	private final PayoutLedgerRepository payouts = mock(PayoutLedgerRepository.class);
	private final PayoutPaymentRepository payments = mock(PayoutPaymentRepository.class);
	private final CaseRepository cases = mock(CaseRepository.class);
	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final BrandRepository brands = mock(BrandRepository.class);
	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final OfferLog log = mock(OfferLog.class);
	private final PayoutRegisterService service =
			new PayoutRegisterService(offers, payouts, payments, cases, experts, brands, members, log);

	private final List<ExpertCaseOffer> offerRows = new ArrayList<>();
	private final List<PayoutLedger> payoutRows = new ArrayList<>();

	@BeforeEach
	void setUp() {
		given(offers.findScoped(any(TenantContext.class))).willReturn(offerRows);
		given(payouts.findScoped(any(TenantContext.class))).willReturn(payoutRows);
		given(payments.findAllById(anyIterable())).willReturn(List.of());
		given(cases.findAllById(anyIterable())).willReturn(List.of());
		given(experts.findAllById(anyIterable())).willReturn(List.of());
		given(members.findAllById(anyIterable())).willReturn(List.of());
		Brand ie = mock(Brand.class);
		given(ie.getId()).willReturn(IE);
		given(ie.getCurrency()).willReturn("USD");
		Brand xp = mock(Brand.class);
		given(xp.getId()).willReturn(XP);
		given(xp.getCurrency()).willReturn("INR");
		given(brands.findAllById(anyIterable())).willReturn(List.of(ie, xp));
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), "enm@evalos.local", "ENM",
				Role.EXPERT_NETWORK_MANAGER, IE, null, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void theRegisterDerivesOneStatusPerOffer() {
		assertThat(PayoutRegisterService.status(OfferOutcome.OFFERED, null)).isEqualTo(RegisterStatus.OFFERED);
		assertThat(PayoutRegisterService.status(OfferOutcome.ACCEPTED, null)).isEqualTo(RegisterStatus.ACCEPTED);
		assertThat(PayoutRegisterService.status(OfferOutcome.ACCEPTED, PayoutStatus.PENDING))
				.isEqualTo(RegisterStatus.PENDING);
		assertThat(PayoutRegisterService.status(OfferOutcome.ACCEPTED, PayoutStatus.PAID))
				.isEqualTo(RegisterStatus.PROCESSING);
		assertThat(PayoutRegisterService.status(OfferOutcome.ACCEPTED, PayoutStatus.CONFIRMED))
				.isEqualTo(RegisterStatus.PAID);
		assertThat(PayoutRegisterService.status(OfferOutcome.DECLINED, null)).isEqualTo(RegisterStatus.DECLINED);
		assertThat(PayoutRegisterService.status(OfferOutcome.TIMED_OUT, null)).isEqualTo(RegisterStatus.TIMED_OUT);
		assertThat(PayoutRegisterService.status(null, PayoutStatus.PENDING)).isEqualTo(RegisterStatus.PENDING);
	}

	@Test
	void anAcceptedOfferPairsWithItsPayoutAndShowsThePayoutsAmount() {
		UUID caseId = UUID.randomUUID();
		offerRows.add(accepted(IE, caseId, "350.00"));
		payoutRows.add(new PayoutLedger(IE, caseId, EXPERT, new BigDecimal("360.00"), "USD", Instant.now()));

		List<PayoutRegisterService.RegisterRow> rows = service.rows(PayoutRegisterService.Filter.none());

		assertThat(rows).singleElement().satisfies(r -> {
			assertThat(r.status()).isEqualTo(RegisterStatus.PENDING);
			assertThat(r.amount()).isEqualByComparingTo("360.00");
			assertThat(r.done()).isFalse();
		});
	}

	@Test
	void aPayoutWithNoAcceptedOfferStillAppears() {
		// Review focus 4: pre-V79 or staff-signed cases.
		payoutRows.add(new PayoutLedger(IE, UUID.randomUUID(), EXPERT, new BigDecimal("300.00"), "USD", Instant.now()));

		assertThat(service.rows(PayoutRegisterService.Filter.none())).singleElement().satisfies(r -> {
			assertThat(r.offerId()).isNull();
			assertThat(r.amount()).isEqualByComparingTo("300.00");
			assertThat(r.currency()).isEqualTo("USD");
		});
	}

	@Test
	void aVoidedPayoutIsLeftOut() {
		PayoutLedger voided = new PayoutLedger(IE, UUID.randomUUID(), EXPERT, BigDecimal.TEN, "USD", Instant.now());
		voided.setStatus(PayoutStatus.VOIDED);
		payoutRows.add(voided);

		assertThat(service.rows(PayoutRegisterService.Filter.none())).isEmpty();
	}

	@Test
	void anUnpricedOfferWithNoPayoutIsLeftOut() {
		offerRows.add(new ExpertCaseOffer(IE, UUID.randomUUID(), EXPERT)); // the pre-V79 shape: no fee
		assertThat(service.rows(PayoutRegisterService.Filter.none())).isEmpty();
	}

	@Test
	void theFilterNarrowsByStatusAndSearch() {
		offerRows.add(accepted(IE, UUID.randomUUID(), "100.00"));
		ExpertCaseOffer open = new ExpertCaseOffer(IE, UUID.randomUUID(), EXPERT);
		open.setFee(new BigDecimal("200.00"), null);
		offerRows.add(open);
		Expert named = mock(Expert.class);
		given(named.getId()).willReturn(EXPERT);
		given(named.getFullName()).willReturn("Dr Ada Lovelace");
		given(experts.findAllById(anyIterable())).willReturn(List.of(named));

		assertThat(service.rows(new PayoutRegisterService.Filter(RegisterStatus.OFFERED, null, null, null, null, null)))
				.extracting(PayoutRegisterService.RegisterRow::status).containsExactly(RegisterStatus.OFFERED);
		assertThat(service.rows(new PayoutRegisterService.Filter(null, null, null, null, "lovelace", null))).hasSize(2);
		assertThat(service.rows(new PayoutRegisterService.Filter(null, null, null, null, "nobody", null))).isEmpty();
	}

	@Test
	void expertTotalsAddUpByStatus() {
		offerRows.add(accepted(IE, UUID.randomUUID(), "100.00")); // committed: accepted, not delivered
		UUID pendingCase = UUID.randomUUID();
		offerRows.add(accepted(IE, pendingCase, "200.00"));
		payoutRows.add(new PayoutLedger(IE, pendingCase, EXPERT, new BigDecimal("200.00"), "USD", Instant.now()));

		PayoutRegisterService.ExpertTotals totals = service.experts(null).getFirst();

		assertThat(totals.committed()).isEqualByComparingTo("100.00");
		assertThat(totals.pending()).isEqualByComparingTo("200.00");
		assertThat(totals.processing()).isEqualByComparingTo("0");
		assertThat(totals.paid()).isEqualByComparingTo("0");
		assertThat(totals.oldestPendingDue()).isNotNull();
	}

	@Test
	void theOverviewFlagsOverdueAndIsOnePerCurrency() {
		// Review focus 3: a GM across two brands never adds USD to INR.
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		offerRows.add(accepted(IE, a, "100.00"));
		offerRows.add(accepted(XP, b, "5000.00"));
		payoutRows.add(new PayoutLedger(IE, a, EXPERT, new BigDecimal("100.00"), "USD",
				Instant.now().minus(3, ChronoUnit.DAYS))); // due in the past → overdue

		List<PayoutRegisterService.Overview> overviews = service.overview(null, null, null);

		assertThat(overviews).extracting(PayoutRegisterService.Overview::currency)
				.containsExactlyInAnyOrder("USD", "INR");
		PayoutRegisterService.Overview usd = overviews.stream().filter(o -> o.currency().equals("USD"))
				.findFirst().orElseThrow();
		assertThat(usd.pending().amount()).isEqualByComparingTo("100.00");
		assertThat(usd.attention()).hasSize(1);
		PayoutRegisterService.Overview inr = overviews.stream().filter(o -> o.currency().equals("INR"))
				.findFirst().orElseThrow();
		assertThat(inr.committed().amount()).isEqualByComparingTo("5000.00");
		assertThat(inr.attention()).isEmpty();
	}

	@Test
	void theShellsBrandSwitcherNarrowsEveryRead() {
		// The GM's "All brands" switcher: brandId narrows within the scope, never widens it.
		offerRows.add(accepted(IE, UUID.randomUUID(), "100.00"));
		offerRows.add(accepted(XP, UUID.randomUUID(), "5000.00"));

		assertThat(service.rows(new PayoutRegisterService.Filter(null, null, null, null, null, XP)))
				.extracting(PayoutRegisterService.RegisterRow::currency).containsExactly("INR");
		assertThat(service.experts(XP)).extracting(PayoutRegisterService.ExpertTotals::currency)
				.containsExactly("INR");
		assertThat(service.overview(null, null, IE)).extracting(PayoutRegisterService.Overview::currency)
				.containsExactly("USD");
		assertThat(service.rows(PayoutRegisterService.Filter.none())).hasSize(2);
	}

	@Test
	void theCsvNeutralisesAFormulaInAnExpertsName() {
		// Review focus 5.
		offerRows.add(accepted(IE, UUID.randomUUID(), "100.00"));
		Expert evil = mock(Expert.class);
		given(evil.getId()).willReturn(EXPERT);
		given(evil.getFullName()).willReturn("=HYPERLINK(\"x\")");
		given(experts.findAllById(anyIterable())).willReturn(List.of(evil));

		String csv = service.exportCsv(PayoutRegisterService.Filter.none());

		assertThat(csv).startsWith("case,expert,amount");
		assertThat(csv).contains("'=HYPERLINK");
	}

	// --- review fixes: an accepted offer is committed money only while it is the live one -----------

	private final List<com.ie.evalos.domain.Case> caseRows = new ArrayList<>();

	private UUID caseOn(UUID expertId) {
		UUID id = UUID.randomUUID();
		com.ie.evalos.domain.Case c = new com.ie.evalos.domain.Case(IE, "IE-" + id.toString().substring(0, 4),
				com.ie.evalos.domain.Stage.EXPERT_SIGNING);
		org.springframework.test.util.ReflectionTestUtils.setField(c, "id", id);
		c.setExpertId(expertId);
		caseRows.add(c);
		given(cases.findAllById(anyIterable())).willReturn(caseRows);
		return id;
	}

	@Test
	void aRefundedCasesAcceptedOfferIsNotCommitted() {
		// (a) The payout was voided by a refund: the offer's money is not owed any more.
		UUID caseId = caseOn(EXPERT);
		offerRows.add(accepted(IE, caseId, "400.00"));
		PayoutLedger voided = new PayoutLedger(IE, caseId, EXPERT, new BigDecimal("400.00"), "USD", Instant.now());
		voided.setStatus(PayoutStatus.VOIDED);
		payoutRows.add(voided);

		assertThat(service.experts(null)).allSatisfy(t -> assertThat(t.committed()).isEqualByComparingTo("0"));
		assertThat(service.rows(PayoutRegisterService.Filter.none()))
				.noneMatch(r -> r.status() == RegisterStatus.ACCEPTED);
	}

	@Test
	void anAcceptedOfferWhoseExpertWasReplacedIsSuperseded() {
		// (b) A accepted, then the case went to B: A's acceptance is not committed money.
		UUID other = UUID.randomUUID();
		UUID caseId = caseOn(other);
		offerRows.add(accepted(IE, caseId, "400.00"));

		assertThat(service.rows(PayoutRegisterService.Filter.none())).singleElement()
				.satisfies(r -> assertThat(r.status()).isEqualTo(RegisterStatus.SUPERSEDED));
		assertThat(service.overview(null, null, null).getFirst().committed().amount()).isEqualByComparingTo("0");
	}

	@Test
	void aRetakenCaseCountsOnlyTheLatestAcceptance() {
		// (c) Two acceptances by the same expert on one case: only the latest is the live one.
		UUID caseId = caseOn(EXPERT);
		ExpertCaseOffer first = accepted(IE, caseId, "400.00");
		org.springframework.test.util.ReflectionTestUtils.setField(first, "offeredAt",
				Instant.now().minus(10, ChronoUnit.DAYS));
		ExpertCaseOffer latest = accepted(IE, caseId, "450.00");
		offerRows.add(latest);
		offerRows.add(first); // order the repository returns them in must not matter
		payoutRows.add(new PayoutLedger(IE, caseId, EXPERT, new BigDecimal("450.00"), "USD", Instant.now()));

		List<PayoutRegisterService.RegisterRow> rows = service.rows(PayoutRegisterService.Filter.none());
		assertThat(rows).filteredOn(r -> r.status() == RegisterStatus.PENDING).singleElement()
				.satisfies(r -> assertThat(r.amount()).isEqualByComparingTo("450.00"));
		assertThat(rows).noneMatch(r -> r.status() == RegisterStatus.ACCEPTED);
	}

	@Test
	void needsAttentionIgnoresThePeriodFilter() {
		// I3: an overdue payout on a case offered months ago is still overdue today.
		UUID caseId = caseOn(EXPERT);
		ExpertCaseOffer old = accepted(IE, caseId, "300.00");
		org.springframework.test.util.ReflectionTestUtils.setField(old, "offeredAt",
				Instant.now().minus(70, ChronoUnit.DAYS));
		offerRows.add(old);
		payoutRows.add(new PayoutLedger(IE, caseId, EXPERT, new BigDecimal("300.00"), "USD",
				Instant.now().minus(5, ChronoUnit.DAYS)));
		java.time.LocalDate today = java.time.LocalDate.now(BusinessCalendar.ZONE);

		List<PayoutRegisterService.Overview> thisMonth = service.overview(today.withDayOfMonth(1), today, null);

		assertThat(thisMonth).singleElement().satisfies(o -> {
			assertThat(o.pending().count()).isZero(); // the tiles keep the period
			assertThat(o.attention()).hasSize(1); // the attention list does not
		});
	}

	private static ExpertCaseOffer accepted(UUID brand, UUID caseId, String fee) {
		ExpertCaseOffer offer = new ExpertCaseOffer(brand, caseId, EXPERT);
		offer.setFee(new BigDecimal(fee), null);
		offer.resolve(OfferOutcome.ACCEPTED, null);
		return offer;
	}
}
