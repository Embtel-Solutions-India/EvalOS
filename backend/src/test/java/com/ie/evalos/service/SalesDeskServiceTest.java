package com.ie.evalos.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.integration.GhlWriteClient;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sales desk's actions, and the two things it must refuse to do.
 *
 * <p>{@link #winningDoesNotCreateACase} and {@link #thereIsNoWayToMoveADealToAnotherPipeline} are
 * the tests worth reading: one guards invariant 8, the other guards the handoff being GHL's.
 */
class SalesDeskServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final String MINE = "pipe_mine";
	private static final String OPPORTUNITY = "opp_1";

	private final GhlWriteClient ghl = mock(GhlWriteClient.class);
	private final OpportunityCache cache = mock(OpportunityCache.class);
	private final SalesDeskService desk = new SalesDeskService(ghl, new PipelineScope(cache));

	private void authenticateAsSales(String pipelineId) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), "sales@ie.test", "Desk",
				Role.SALES, BRAND, null, pipelineId, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private void givenItIsMine() {
		when(cache.isInPipeline(OPPORTUNITY, MINE)).thenReturn(true);
	}

	private static GhlWriteClient.UpsertedOpportunity answer(String status) {
		return new GhlWriteClient.UpsertedOpportunity(OPPORTUNITY, "c1", MINE, "s2", status, "Acme",
				new BigDecimal("1200"), false);
	}

	@Test
	void updatesTheNameAndValueOnTheCallersOwnPipeline() {
		authenticateAsSales(MINE);
		givenItIsMine();
		when(ghl.updateOpportunity(any(), any(), any(), any(), any())).thenReturn(answer("open"));

		SalesDeskService.Deal deal = desk.update(OPPORTUNITY, "Acme", new BigDecimal("1200"));

		verify(ghl).updateOpportunity(OPPORTUNITY, MINE, "Acme", new BigDecimal("1200"), null);
		assertThat(deal.monetaryValue()).isEqualByComparingTo("1200");
	}

	/** An update that changes nothing is a mistake worth naming, not a no-op write to GHL. */
	@Test
	void anUpdateWithNothingToChangeIsRefused() {
		authenticateAsSales(MINE);
		givenItIsMine();

		assertThatThrownBy(() -> desk.update(OPPORTUNITY, "  ", null))
				.isInstanceOf(InvalidRequestException.class);

		verify(ghl, never()).updateOpportunity(any(), any(), any(), any(), any());
	}

	@Test
	void movesADealToAnotherStageOfItsOwnPipeline() {
		authenticateAsSales(MINE);
		givenItIsMine();
		when(ghl.moveStage(any(), any(), any())).thenReturn(answer("open"));

		desk.moveToStage(OPPORTUNITY, "s2");

		verify(ghl).moveStage(OPPORTUNITY, MINE, "s2");
	}

	/**
	 * <strong>There is no parameter for a target pipeline, and that is the design.</strong>
	 *
	 * <p>Moving a deal between pipelines is the marketing-to-sales promotion, and GHL's own
	 * workflow runs it. A path from here would be a second promotion racing the automation the
	 * business already owns — so the pipeline handed to GHL is always the caller's own.
	 */
	@Test
	void thereIsNoWayToMoveADealToAnotherPipeline() {
		authenticateAsSales(MINE);
		givenItIsMine();
		when(ghl.moveStage(any(), any(), any())).thenReturn(answer("open"));

		desk.moveToStage(OPPORTUNITY, "s2");

		verify(ghl).moveStage(OPPORTUNITY, MINE, "s2");
		verify(ghl, never()).moveStage(any(), eq("pipe_theirs"), any());
	}

	@ParameterizedTest
	@ValueSource(strings = { "won", "lost", "abandoned" })
	void closesADealWithAnyRealOutcome(String status) {
		authenticateAsSales(MINE);
		givenItIsMine();
		when(ghl.setStatus(any(), any(), any())).thenReturn(answer(status));

		assertThat(desk.close(OPPORTUNITY, status).status()).isEqualTo(status);
	}

	/**
	 * <strong>Winning tells GHL and stops there — invariant 8.</strong>
	 *
	 * <p>The case is created by the {@code opportunity.won} webhook (Handoff A), which is the
	 * only door into custody. This asserts the desk writes to GHL and does nothing else; the
	 * structural half of the guarantee is {@code DomainInvariantsTest}, which refuses anything
	 * but {@code GhlOpportunityHandler} to depend on {@code CaseIntakeService}.
	 */
	@Test
	void winningDoesNotCreateACase() {
		authenticateAsSales(MINE);
		givenItIsMine();
		when(ghl.setStatus(any(), any(), any())).thenReturn(answer("won"));

		desk.close(OPPORTUNITY, "won");

		verify(ghl).setStatus(OPPORTUNITY, MINE, "won");
		// Nothing else happened: no second write, no local creation.
		verify(ghl, never()).upsertOpportunity(any(), any(), any(), any());
		verify(ghl, never()).upsertContact(any(), any(), any(), any());
	}

	/**
	 * {@code open} is not a closable status.
	 *
	 * <p>Re-opening a won deal would not un-create the case the webhook already made, so it is
	 * not a sales action — it is a correction with a case-side answer. Case matters too: GHL's
	 * enum is lowercase, and accepting {@code WON} here would send it something it refuses.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "open", "all", "closed", "WON" })
	void anythingButTheThreeOutcomesIsRefused(String status) {
		authenticateAsSales(MINE);
		givenItIsMine();

		assertThatThrownBy(() -> desk.close(OPPORTUNITY, status))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("won, lost, abandoned");

		verify(ghl, never()).setStatus(any(), any(), any());
	}

	@Test
	void setsAFollowUpAsAGhlTask() {
		authenticateAsSales(MINE);
		givenItIsMine();
		when(ghl.createFollowUp(any(), any(), any(), any(), any())).thenReturn("task_1");

		assertThat(desk.followUp(OPPORTUNITY, "c1", "Call back", "2026-09-18T09:00:00Z"))
				.isEqualTo("task_1");

		verify(ghl).createFollowUp("c1", OPPORTUNITY, MINE, "Call back", "2026-09-18T09:00:00Z");
	}

	/** A follow-up with no date is a note, and notes have their own route. */
	@Test
	void aFollowUpNeedsATitleAndADate() {
		authenticateAsSales(MINE);
		givenItIsMine();

		assertThatThrownBy(() -> desk.followUp(OPPORTUNITY, "c1", "  ", "2026-09-18T09:00:00Z"))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> desk.followUp(OPPORTUNITY, "c1", "Call back", null))
				.isInstanceOf(InvalidRequestException.class);

		verify(ghl, never()).createFollowUp(any(), any(), any(), any(), any());
	}

	/** Every action refuses an opportunity outside the caller's pipeline, with 403 not 404. */
	@Test
	void anotherDesksDealIsRefusedByEveryAction() {
		authenticateAsSales(MINE);
		when(cache.isInPipeline("opp_theirs", MINE)).thenReturn(false);

		assertThatThrownBy(() -> desk.update("opp_theirs", "x", null))
				.isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> desk.moveToStage("opp_theirs", "s2"))
				.isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> desk.close("opp_theirs", "won"))
				.isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> desk.followUp("opp_theirs", "c1", "Call", "2026-09-18T09:00:00Z"))
				.isInstanceOf(ForbiddenException.class);

		verify(ghl, never()).updateOpportunity(any(), any(), any(), any(), any());
		verify(ghl, never()).setStatus(any(), any(), any());
		verify(ghl, never()).createFollowUp(any(), any(), any(), any(), any());
	}

	@Test
	void aCallerWithNoPipelineIsRefused() {
		authenticateAsSales(null);

		assertThatThrownBy(() -> desk.close(OPPORTUNITY, "won")).isInstanceOf(ForbiddenException.class);
	}
}
