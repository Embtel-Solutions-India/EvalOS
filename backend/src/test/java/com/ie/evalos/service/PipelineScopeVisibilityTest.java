package com.ie.evalos.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Reading a deal widens with the role; writing to one does not.
 *
 * <p><strong>The bug this pins.</strong> {@code requireMine} reads the pipelines off the
 * principal, and D19e says a GM holds none <em>and must not</em>. So the GM's board listed every
 * mirrored deal and then refused the contact, the notes and the meetings on every one of them —
 * recorded as P0 in {@code 00d} row 73. A Brand Manager was in the same position.
 *
 * <p><strong>The risk this pins is the opposite one.</strong> The obvious fix — teach
 * {@code mine()} to answer "everything" for a GM — would have widened reading and writing
 * together, because every edit, close, booking and note goes through {@code requireMine}. So the
 * two halves are asserted separately, and the write half asserting a <em>refusal</em> is the one
 * that matters: it is what stops a later "simplification" collapsing the two methods back into
 * one.
 */
class PipelineScopeVisibilityTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID OTHER_BRAND = UUID.randomUUID();
	private static final UUID MEMBER = UUID.randomUUID();
	private static final String DEAL = "opp_1";
	private static final String MY_PIPELINE = "pipe_mine";

	private final OpportunityMirrorService deals = mock(OpportunityMirrorService.class);
	private final PipelineScope scope = new PipelineScope(deals);

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void theGmReadsADealOnAPipelineTheyHoldNoAssignmentFor() {
		givenDeal(BRAND);
		authenticate(Role.GM, null, List.of());

		assertThat(scope.requireVisible(DEAL).getBrandId()).isEqualTo(BRAND);
	}

	/**
	 * <strong>And still cannot write to it.</strong> Seeing every deal is the decision; editing
	 * every deal is not, and a GM carries no pipeline to write through.
	 */
	@Test
	void theGmStillCannotWriteToIt() {
		givenDeal(BRAND);
		authenticate(Role.GM, null, List.of());

		assertThatExceptionOfType(ForbiddenException.class)
				.isThrownBy(() -> scope.requireMine(DEAL));
	}

	@Test
	void aBrandManagerReadsItsOwnBrandsDeal() {
		givenDeal(BRAND);
		authenticate(Role.BRAND_MANAGER, BRAND, List.of());

		assertThat(scope.requireVisible(DEAL).getBrandId()).isEqualTo(BRAND);
	}

	@Test
	void aBrandManagerCannotReadAnotherBrandsDeal() {
		givenDeal(OTHER_BRAND);
		authenticate(Role.BRAND_MANAGER, BRAND, List.of());

		assertThatExceptionOfType(ForbiddenException.class)
				.isThrownBy(() -> scope.requireVisible(DEAL));
	}

	@Test
	void aDeskReadsADealOnItsOwnPipeline() {
		givenDeal(BRAND);
		given(deals.isOnPipeline(DEAL, MY_PIPELINE)).willReturn(true);
		authenticate(Role.SALES, BRAND, List.of(MY_PIPELINE));

		assertThat(scope.requireVisible(DEAL).getBrandId()).isEqualTo(BRAND);
	}

	/** A desk's reading and writing scope are the same set — this is the half that must not widen. */
	@Test
	void aDeskCannotReadADealOnSomebodyElsesPipeline() {
		givenDeal(BRAND);
		given(deals.isOnPipeline(anyString(), anyString())).willReturn(false);
		authenticate(Role.SALES, BRAND, List.of(MY_PIPELINE));

		assertThatExceptionOfType(ForbiddenException.class)
				.isThrownBy(() -> scope.requireVisible(DEAL));
	}

	/**
	 * A deal that does not exist refuses <strong>identically</strong> to one that is not yours.
	 *
	 * <p>Otherwise the response is an oracle for which ids the location holds — the rule
	 * {@code requireMine} already states and this inherits.
	 */
	@Test
	void anUnknownDealIsRefusedTheSameWayAsSomebodyElsesAndNotAsMissing() {
		given(deals.byGhlId(anyString())).willReturn(Optional.empty());
		authenticate(Role.GM, null, List.of());

		assertThatExceptionOfType(ForbiddenException.class)
				.isThrownBy(() -> scope.requireVisible("opp_does_not_exist"))
				.withMessageContaining("not on a pipeline you can read");
	}

	/** Production roles work cases, not the CRM; a deal's notes and meetings are not theirs. */
	@Test
	void aCaseManagerReadsNoDeal() {
		givenDeal(BRAND);
		authenticate(Role.CASE_MANAGER, BRAND, List.of());

		assertThatExceptionOfType(ForbiddenException.class)
				.isThrownBy(() -> scope.requireVisible(DEAL));
	}

	private void givenDeal(UUID brandId) {
		Opportunity deal = new Opportunity(brandId, DEAL, UUID.randomUUID());
		ReflectionTestUtils.setField(deal, "id", UUID.randomUUID());
		given(deals.byGhlId(any())).willReturn(Optional.of(deal));
	}

	private static void authenticate(Role role, UUID brandId, List<String> pipelines) {
		StaffPrincipal principal = new StaffPrincipal(MEMBER, role + "@evalos.local", "Desk", role,
				brandId, null, pipelines, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}
}
