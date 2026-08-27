package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.PayoutPaymentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The rules that decide what an expert is owed and when it may be recorded as sent.
 *
 * <p>Plain unit tests over mocked repositories: none of this needs a database, and the
 * two things that genuinely do — the partial unique index and two concurrent
 * settlements — are proved in {@code LocalPostgresIntegrationTest} instead.
 */
class PayoutServiceTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID BRAND_XP = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private static final UUID EXPERT_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

	private static final UUID CASE_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000001");

	private PayoutLedgerRepository payouts;

	private PayoutPaymentRepository payments;

	private ExpertRepository experts;

	private BrandRepository brands;

	private AuditService audit;

	private PayoutService service;

	@BeforeEach
	void setUp() {
		payouts = mock(PayoutLedgerRepository.class);
		payments = mock(PayoutPaymentRepository.class);
		experts = mock(ExpertRepository.class);
		brands = mock(BrandRepository.class);
		audit = mock(AuditService.class);
		service = new PayoutService(payouts, payments, experts, brands, audit);

		given(payouts.save(any(PayoutLedger.class))).willAnswer(call -> call.getArgument(0));
	}

	@Test
	void deliveryOpensOnePendingRowPrefilledFromTheStandardFee() {
		givenBrand("USD", 7);
		givenExpert(new BigDecimal("350.00"));
		Case delivered = deliveredCase(EXPERT_ID);

		service.openForDelivery(delivered);

		ArgumentCaptor<PayoutLedger> saved = ArgumentCaptor.forClass(PayoutLedger.class);
		verify(payouts).save(saved.capture());
		assertThat(saved.getValue().getStatus()).isEqualTo(PayoutStatus.PENDING);
		assertThat(saved.getValue().getAmount()).isEqualByComparingTo("350.00");
		assertThat(saved.getValue().getCurrency()).isEqualTo("USD");
		assertThat(saved.getValue().getExpertId()).isEqualTo(EXPERT_ID);
		assertThat(saved.getValue().getCaseId()).isEqualTo(CASE_ID);
		// recorded_by is null: nobody has recorded anything yet.
		assertThat(saved.getValue().getRecordedBy()).isNull();

		// The audit snapshot is a Map, never the entity — AuditService's contract — and
		// pins the object type/action a source read would otherwise be the only check on.
		ArgumentCaptor<Object> snapshot = ArgumentCaptor.forClass(Object.class);
		verify(audit).recordEvent(eq("PAYOUT"), any(), eq(AuditAction.CREATED), any(), any(), snapshot.capture());
		assertThat(snapshot.getValue()).isInstanceOf(java.util.Map.class).isNotInstanceOf(PayoutLedger.class);
	}

	@Test
	void anExpertWithNoStandardFeeGetsARowWithNoAmount() {
		givenBrand("USD", 7);
		givenExpert(null);

		service.openForDelivery(deliveredCase(EXPERT_ID));

		ArgumentCaptor<PayoutLedger> saved = ArgumentCaptor.forClass(PayoutLedger.class);
		verify(payouts).save(saved.capture());
		// Null, never zero: a prefill of 0 is a number somebody could settle without noticing.
		assertThat(saved.getValue().getAmount()).isNull();
	}

	@Test
	void theDueDateIsDeliveryPlusTheBrandsPayoutTerm() {
		givenBrand("USD", 14);
		givenExpert(new BigDecimal("350.00"));
		Case delivered = deliveredCase(EXPERT_ID);

		service.openForDelivery(delivered);

		ArgumentCaptor<PayoutLedger> saved = ArgumentCaptor.forClass(PayoutLedger.class);
		verify(payouts).save(saved.capture());
		assertThat(saved.getValue().getDueDate())
				.isEqualTo(delivered.getDeliveryDate().plus(14, ChronoUnit.DAYS));
	}

	@Test
	void aCaseDeliveredWithNoExpertOpensNoRow() {
		givenBrand("USD", 7);

		Optional<PayoutLedger> opened = service.openForDelivery(deliveredCase(null));

		assertThat(opened).isEmpty();
		verify(payouts, never()).save(any());
	}

	@Test
	void aBrandWithNoConfiguredCurrencyRefusesTheDelivery() {
		givenBrand(null, 7);
		givenExpert(new BigDecimal("350.00"));

		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> service.openForDelivery(deliveredCase(EXPERT_ID)));
		verify(payouts, never()).save(any());
	}

	/**
	 * Proves the scoping actually bites, not merely that the mock was configured: stubs
	 * the key the pre-fix, unscoped {@code findById(expertId)} would have used, with an
	 * expert whose fee would leak if that call ever came back. Reverting the production
	 * fix to {@code experts.findById(expertId)} must fail this on all three assertions.
	 */
	@Test
	void anExpertBelongingToAnotherBrandIsTreatedAsHavingNoStandardFee() {
		givenBrand("USD", 7);
		Expert fromAnotherBrand = new Expert(BRAND_XP, "Dr. Wrong-Brand");
		fromAnotherBrand.setStandardFee(new BigDecimal("999.00"));
		// The unscoped lookup the old code used would find this expert and prefill 999.00.
		// The scoped lookup finds nothing, because this expert is not in the case's brand.
		given(experts.findById(EXPERT_ID)).willReturn(Optional.of(fromAnotherBrand));

		service.openForDelivery(deliveredCase(EXPERT_ID));

		ArgumentCaptor<PayoutLedger> saved = ArgumentCaptor.forClass(PayoutLedger.class);
		verify(payouts).save(saved.capture());
		assertThat(saved.getValue().getAmount())
				.as("another brand's fee must never leak onto this brand's payout row")
				.isNull();
		verify(experts).findByIdAndBrandId(EXPERT_ID, BRAND_IE);
		verify(experts, never()).findById(any());
	}

	private void givenBrand(String currency, int termDays) {
		// Brand has no public constructor and no setters (only JPA can build one), so a
		// mock is the only way to hand it a currency and a term — see ruling 3.
		Brand brand = mock(Brand.class);
		given(brand.getCurrency()).willReturn(currency);
		given(brand.getPayoutTermDays()).willReturn(termDays);
		given(brands.findById(BRAND_IE)).willReturn(Optional.of(brand));
	}

	private void givenExpert(BigDecimal standardFee) {
		// A real instance: Expert(UUID, String) is public and setStandardFee exists, so
		// there is no value here a mock could give that this cannot (ruling 3).
		Expert expert = new Expert(BRAND_IE, "Standing Expert");
		expert.setStandardFee(standardFee);
		given(experts.findByIdAndBrandId(EXPERT_ID, BRAND_IE)).willReturn(Optional.of(expert));
	}

	private static Case deliveredCase(UUID expertId) {
		// Mocked deliberately: getId() is null on an unpersisted Case, and this test needs
		// a fixed CASE_ID no real, unsaved instance can supply (ruling 3's own example).
		Case subject = mock(Case.class);
		given(subject.getId()).willReturn(CASE_ID);
		given(subject.getBrandId()).willReturn(BRAND_IE);
		given(subject.getExpertId()).willReturn(expertId);
		given(subject.getDeliveryDate()).willReturn(Instant.parse("2026-08-26T18:00:00Z"));
		return subject;
	}
}
