package com.ie.evalos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.common.NotFoundException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.security.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Unit 65 rules 2–3: who may change an open offer's fee, and that an answered one is final. */
class OfferFeeServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();

	private final CaseRepository cases = mock(CaseRepository.class);
	private final ExpertCaseOfferRepository offers = mock(ExpertCaseOfferRepository.class);
	private final PayoutLedgerRepository payouts = mock(PayoutLedgerRepository.class);
	private final BrandRepository brands = mock(BrandRepository.class);
	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final OfferLog log = mock(OfferLog.class);
	private final OfferFeeService service = new OfferFeeService(cases, offers, payouts, brands, members, audit, log);

	private ExpertCaseOffer open;

	@BeforeEach
	void aCaseWithAnOpenOffer() {
		Case subject = new Case(BRAND, "IE-2026-0001", Stage.CLIENT_APPROVAL);
		given(cases.findScoped(any(TenantContext.class), eq(CASE_ID))).willReturn(Optional.of(subject));
		open = new ExpertCaseOffer(BRAND, CASE_ID, UUID.randomUUID());
		open.setFee(new BigDecimal("350.00"), null);
		// any(): the case has no id outside JPA, so the service asks for null.
		given(offers.findByCaseIdOrderByOfferedAtDesc(any())).willReturn(List.of(open));
		Brand brand = mock(Brand.class);
		given(brand.getCurrency()).willReturn("USD");
		given(brands.findById(BRAND)).willReturn(Optional.of(brand));
		given(log.forOffer(any(), any())).willReturn(List.of());
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void theFeeCanBeEditedWhileTheOfferIsOpenAndIsAudited() {
		actAs(Role.PROJECT_COORDINATOR);

		OfferFeeService.OfferView view = service.editFee(CASE_ID, new BigDecimal("400.00"));

		assertThat(view.fee()).isEqualByComparingTo("400.00");
		verify(offers).save(open);
		verify(audit).recordEvent(eq("OFFER"), any(), eq(AuditAction.UPDATED), any(),
				eq(Map.of("fee", new BigDecimal("350.00"))), eq(Map.of("fee", new BigDecimal("400.00"))));
	}

	@Test
	void theFeeCannotBeEditedOnceAnswered() {
		actAs(Role.PROJECT_MANAGER);
		for (OfferOutcome outcome : new OfferOutcome[] { OfferOutcome.ACCEPTED, OfferOutcome.DECLINED,
				OfferOutcome.TIMED_OUT, OfferOutcome.SUPERSEDED }) {
			ExpertCaseOffer answered = new ExpertCaseOffer(BRAND, CASE_ID, UUID.randomUUID());
			answered.resolve(outcome, null);
			given(offers.findByCaseIdOrderByOfferedAtDesc(any())).willReturn(List.of(answered));
			assertThatThrownBy(() -> service.editFee(CASE_ID, BigDecimal.TEN)).as(outcome.name())
					.isInstanceOf(IllegalTransitionException.class);
		}
		verify(audit, never()).recordEvent(any(), any(), any(), any(), any(), any());
	}

	@Test
	void aCaseManagerOrBrandManagerCannotEditTheFee() {
		for (Role role : new Role[] { Role.CASE_MANAGER, Role.BRAND_MANAGER, Role.SALES }) {
			actAs(role);
			assertThatThrownBy(() -> service.editFee(CASE_ID, BigDecimal.TEN)).as(role.name())
					.isInstanceOf(ForbiddenException.class);
		}
	}

	@Test
	void aCaseOutsideTheCallersScopeIsNotFound() {
		// Review focus 2: a PC's scope is SELF, so a case not assigned to them is absent.
		given(cases.findScoped(any(TenantContext.class), eq(CASE_ID))).willReturn(Optional.empty());
		actAs(Role.PROJECT_COORDINATOR);
		assertThatThrownBy(() -> service.editFee(CASE_ID, BigDecimal.TEN)).isInstanceOf(NotFoundException.class);
		verify(offers, never()).save(any());
	}

	@Test
	void aNegativeFeeIsRefused() {
		actAs(Role.PROJECT_MANAGER);
		assertThatThrownBy(() -> service.editFee(CASE_ID, new BigDecimal("-1")))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void theCurrentOfferIsTheLatestWithTheBrandsCurrency() {
		actAs(Role.CASE_MANAGER);
		OfferFeeService.OfferView view = service.current(CASE_ID).orElseThrow();
		assertThat(view.fee()).isEqualByComparingTo("350.00");
		assertThat(view.currency()).isEqualTo("USD");
		assertThat(view.outcome()).isEqualTo(OfferOutcome.OFFERED);
	}

	private void actAs(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), "s@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND, UUID.randomUUID(), null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}
}
