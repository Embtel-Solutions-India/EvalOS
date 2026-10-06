package com.ie.evalos.service;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** The deal behind a case is read through the case: its scope, its brand, and a role that reads case content. */
class CaseOpportunityServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();

	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final OpportunityMirrorService mirror = mock(OpportunityMirrorService.class);
	private final CaseOpportunityService service = new CaseOpportunityService(lifecycle, mirror);

	@AfterEach
	void clearCaller() {
		SecurityContextHolder.clearContext();
	}

	private void actAs(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), "s@evalos.local", "Staff", role, BRAND,
				UUID.randomUUID(), null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	private Case caseWithDeal(String ghlId) {
		Case subject = new Case(BRAND, "IE-1", Stage.PM_REVIEW);
		subject.setGhlOpportunityId(ghlId);
		given(lifecycle.load(CASE_ID)).willReturn(subject);
		return subject;
	}

	@Test
	void aCaseManagerReadsTheDealTheCaseWasWonFrom() {
		actAs(Role.CASE_MANAGER);
		caseWithDeal("opp-1");
		Opportunity deal = new Opportunity(BRAND, "opp-1", UUID.randomUUID());
		given(mirror.byGhlId("opp-1")).willReturn(Optional.of(deal));

		assertThat(service.of(CASE_ID)).containsSame(deal);
	}

	@Test
	void aDealInAnotherBrandIsNotReturned() {
		actAs(Role.PROJECT_MANAGER);
		caseWithDeal("opp-1");
		given(mirror.byGhlId("opp-1")).willReturn(Optional.of(new Opportunity(UUID.randomUUID(), "opp-1", UUID.randomUUID())));

		assertThat(service.of(CASE_ID)).isEmpty();
	}

	@Test
	void aCaseWithNoDealAnswersEmpty() {
		actAs(Role.PROJECT_COORDINATOR);
		caseWithDeal(null);

		assertThat(service.of(CASE_ID)).isEmpty();
	}

	@Test
	void theEnmDoesNotReadClientDetails() {
		actAs(Role.EXPERT_NETWORK_MANAGER);
		caseWithDeal("opp-1");

		assertThatThrownBy(() -> service.of(CASE_ID)).isInstanceOf(ForbiddenException.class);
	}
}
