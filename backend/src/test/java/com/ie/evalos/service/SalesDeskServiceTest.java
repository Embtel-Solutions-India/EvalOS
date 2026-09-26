package com.ie.evalos.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.ie.evalos.common.DuplicateDealException;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.SyncOutboxEntry;
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

	private static final UUID PIPELINE_ROW = UUID.randomUUID();

	private final GhlWriteClient ghl = mock(GhlWriteClient.class);
	private final OpportunityMirrorService deals = mock(OpportunityMirrorService.class);
	private final SyncOutboxService outbox = mock(SyncOutboxService.class);
	private final com.ie.evalos.repository.FollowUpRepository followUps =
			mock(com.ie.evalos.repository.FollowUpRepository.class);
	private final com.ie.evalos.repository.PipelineStageRepository stages =
			mock(com.ie.evalos.repository.PipelineStageRepository.class);
	private final SalesDeskService desk =
			new SalesDeskService(ghl, new PipelineScope(deals), deals, outbox, followUps, stages);

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
		when(deals.isOnPipeline(OPPORTUNITY, MINE)).thenReturn(true);
	}

	/**
	 * The mirror row the desk edits — <strong>a real entity, not a mock</strong>, so these tests
	 * exercise {@code editedLocally}'s "null means leave it alone" rather than a stub's idea of it.
	 */
	private com.ie.evalos.domain.Opportunity mirrored() {
		com.ie.evalos.domain.Opportunity row =
				new com.ie.evalos.domain.Opportunity(BRAND, OPPORTUNITY, PIPELINE_ROW);
		org.springframework.test.util.ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
		row.syncFromGhl("c1", PIPELINE_ROW, "s1", "Acme", new BigDecimal("1200"), "open", null, null,
				null, null, null, null);
		return row;
	}

	/** Unit 46: the desk edits the mirror and queues the push, so that is what the stub does. */
	/** A mirrored stage on {@code pipeline}, live or retired — what the Q12 guard reads. */
	private void givenStage(String ghlStageId, UUID pipeline, boolean live) {
		com.ie.evalos.domain.PipelineStage stage =
				new com.ie.evalos.domain.PipelineStage(BRAND, pipeline, ghlStageId, "Stage", 2);
		if (!live) {
			stage.markMissing(java.time.Instant.now());
		}
		when(stages.findByBrandIdAndGhlId(BRAND, ghlStageId)).thenReturn(java.util.Optional.of(stage));
	}

	private void givenTheMirrorHasIt() {
		when(deals.byGhlId(OPPORTUNITY)).thenAnswer((call) -> java.util.Optional.of(mirrored()));
		when(deals.editLocally(eq(OPPORTUNITY), any(), any(), any(), any())).thenAnswer((call) -> {
			com.ie.evalos.domain.Opportunity row = mirrored();
			row.editedLocally(call.getArgument(1), call.getArgument(2), call.getArgument(3),
					call.getArgument(4));
			return java.util.Optional.of(row);
		});
	}

	private static GhlWriteClient.UpsertedOpportunity answer(String status) {
		return new GhlWriteClient.UpsertedOpportunity(OPPORTUNITY, "c1", MINE, "s2", status, "Acme",
				new BigDecimal("1200"), false);
	}

	/**
	 * Opening a deal creates rather than upserts, and that is the whole point of the route.
	 *
	 * <p>Unit 39 §3a: upsert keys on (contact, pipeline), so using it for a genuine second deal
	 * would silently overwrite the first one's name and value instead of opening a second. This
	 * asserts the verb, because the two calls are one word apart and the wrong one loses a deal
	 * with no error anywhere.
	 */
	@Test
	void openingADealCreatesRatherThanUpserts() {
		authenticateAsSales(MINE);
		when(ghl.upsertContact(any(), any(), any(), any(), any()))
				.thenReturn(new GhlWriteClient.UpsertedContact("c1", "Acme", "a@b.test", null));
		when(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(answer("open"));

		desk.createDeal("A", "Client", "a@b.test", null, "Acme evaluation",
				new BigDecimal("1200"), null, null, null, false);

		verify(ghl).createOpportunity(eq(MINE), eq("c1"), eq("Acme evaluation"),
				eq(new BigDecimal("1200")), any(), any(), any());
		verify(ghl, never()).upsertOpportunity(any(), any(), any(), any());
	}

	/**
	 * The duplicate protection upsert used to give for free, replaced by asking.
	 *
	 * <p>A repeat client is legitimate, so this is a 409-with-a-question rather than a refusal —
	 * but it must fire, because the create path has nothing else stopping a salesperson opening a
	 * third copy of a deal they could not see.
	 */
	@Test
	void aSecondOpenDealForTheSameContactIsRefusedUntilItIsConfirmed() {
		authenticateAsSales(MINE);
		when(ghl.upsertContact(any(), any(), any(), any(), any()))
				.thenReturn(new GhlWriteClient.UpsertedContact("c1", "Acme", "a@b.test", null));
		com.ie.evalos.domain.Opportunity existing = mock(com.ie.evalos.domain.Opportunity.class);
		when(existing.getGhlContactId()).thenReturn("c1");
		when(existing.getStatus()).thenReturn("open");
		when(existing.getGhlId()).thenReturn("opp_existing");
		when(deals.onPipelines(any())).thenReturn(java.util.List.of(existing));

		assertThatThrownBy(() -> desk.createDeal("A", "Client", "a@b.test", null, "Second deal",
				null, null, null, null, false))
				.isInstanceOf(DuplicateDealException.class)
				.hasMessageContaining("already has an open deal");

		verify(ghl, never()).createOpportunity(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void theSameSecondDealGoesThroughOnceConfirmed() {
		authenticateAsSales(MINE);
		when(ghl.upsertContact(any(), any(), any(), any(), any()))
				.thenReturn(new GhlWriteClient.UpsertedContact("c1", "Acme", "a@b.test", null));
		when(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(answer("open"));

		desk.createDeal("A", "Client", "a@b.test", null, "Second deal", null, null, null, null, true);

		// The mirror is not even consulted once the caller has confirmed.
		verify(deals, never()).onPipelines(any());
		verify(ghl).createOpportunity(eq(MINE), eq("c1"), eq("Second deal"), any(), any(), any(), any());
	}

	/**
	 * Without an email or a phone GHL has nothing to match on, so every save mints another
	 * contact — the same rule {@code MarketingLeadService} enforces, for the same reason.
	 */
	@Test
	void aDealWithNoEmailAndNoPhoneIsRefusedBeforeAnythingIsWritten() {
		authenticateAsSales(MINE);

		assertThatThrownBy(() -> desk.createDeal("A", "Client", null, null, "Acme", null, null,
				null, null, false))
				.isInstanceOf(InvalidRequestException.class);

		verify(ghl, never()).upsertContact(any(), any(), any(), any(), any());
		verify(ghl, never()).createOpportunity(any(), any(), any(), any(), any(), any(), any());
	}

	/**
	 * <strong>Unit 46: the mirror is written, the push is queued, and GHL is not called.</strong>
	 * The salesperson sees the new value from local state; the drain sends it within two minutes.
	 */
	@Test
	void updatingWritesTheMirrorAndQueuesThePushWithoutCallingGhl() {
		authenticateAsSales(MINE);
		givenItIsMine();
		givenTheMirrorHasIt();

		SalesDeskService.Deal deal = desk.update(OPPORTUNITY, "Renamed", new BigDecimal("1500"));

		assertThat(deal.name()).isEqualTo("Renamed");
		assertThat(deal.monetaryValue()).isEqualByComparingTo("1500");
		verify(outbox).enqueue(eq(BRAND), any(), eq(SyncOutboxEntry.Intent.UPSERT));
		verify(ghl, never()).updateOpportunity(any(), any(), any(), any(), any());
	}

	/**
	 * A deal GHL knows and the mirror has not absorbed yet is refused with the fix named, rather
	 * than queueing a push for an entity id the drain could not resolve.
	 */
	@Test
	void aDealTheMirrorHasNotAbsorbedYetIsRefusedRatherThanQueued() {
		authenticateAsSales(MINE);
		givenItIsMine();
		when(deals.editLocally(any(), any(), any(), any(), any())).thenReturn(java.util.Optional.empty());

		assertThatThrownBy(() -> desk.update(OPPORTUNITY, "Renamed", null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not in the mirror yet");

		verify(outbox, never()).enqueue(any(), any(), any());
	}

	/** An update that changes nothing is a mistake worth naming, not a no-op write to GHL. */
	@Test
	void anUpdateWithNothingToChangeIsRefused() {
		authenticateAsSales(MINE);
		givenItIsMine();

		assertThatThrownBy(() -> desk.update(OPPORTUNITY, "  ", null))
				.isInstanceOf(InvalidRequestException.class);

		verify(outbox, never()).enqueue(any(), any(), any());
	}

	@Test
	void movesADealToAnotherStageOfItsOwnPipeline() {
		authenticateAsSales(MINE);
		givenItIsMine();
		givenTheMirrorHasIt();
		givenStage("s2", PIPELINE_ROW, true);

		assertThat(desk.moveToStage(OPPORTUNITY, "s2").stageId()).isEqualTo("s2");

		verify(outbox).enqueue(eq(BRAND), any(), eq(SyncOutboxEntry.Intent.UPSERT));
		verify(ghl, never()).moveStage(any(), any(), any());
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
		givenTheMirrorHasIt();
		givenStage("s2", PIPELINE_ROW, true);

		desk.moveToStage(OPPORTUNITY, "s2");

		// The edit names a stage and nothing else — there is no pipeline argument to pass, here or
		// on the queued push, which reads the row's own pipeline.
		verify(deals).editLocally(OPPORTUNITY, null, null, "s2", null);
	}

	/**
	 * Q12: a desk holding two pipelines sees both strips on one board, so a drag can send the other
	 * pipeline's stage. That would be a pipeline move in GHL, and is refused before anything queues.
	 */
	@Test
	void aStageOfAnotherPipelineIsRefused() {
		authenticateAsSales(MINE);
		givenItIsMine();
		givenTheMirrorHasIt();
		givenStage("s_other", UUID.randomUUID(), true);

		assertThatThrownBy(() -> desk.moveToStage(OPPORTUNITY, "s_other"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not on this deal's pipeline");

		verify(deals, never()).editLocally(any(), any(), any(), any(), any());
		verify(outbox, never()).enqueue(any(), any(), any());
	}

	/** A retired stage, or one the mirror has never seen, is refused rather than pushed. */
	@Test
	void aRetiredOrUnknownStageIsRefused() {
		authenticateAsSales(MINE);
		givenItIsMine();
		givenTheMirrorHasIt();
		givenStage("s_gone", PIPELINE_ROW, false);

		assertThatThrownBy(() -> desk.moveToStage(OPPORTUNITY, "s_gone"))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> desk.moveToStage(OPPORTUNITY, "s_never_mirrored"))
				.isInstanceOf(InvalidRequestException.class);

		verify(outbox, never()).enqueue(any(), any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = { "won", "lost", "abandoned" })
	void closesADealWithAnyRealOutcome(String status) {
		authenticateAsSales(MINE);
		givenItIsMine();
		givenTheMirrorHasIt();

		assertThat(desk.close(OPPORTUNITY, status).status()).isEqualTo(status);
		verify(outbox).enqueue(eq(BRAND), any(), eq(SyncOutboxEntry.Intent.CLOSE));
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
		givenTheMirrorHasIt();

		desk.close(OPPORTUNITY, "won");

		// Unit 46: the win becomes a local status plus one queued CLOSE. The webhook is still what
		// creates the case — the queue changes when GHL hears, never who opens custody.
		verify(outbox).enqueue(eq(BRAND), any(), eq(SyncOutboxEntry.Intent.CLOSE));
		verify(ghl, never()).setStatus(any(), any(), any());
		verify(ghl, never()).upsertOpportunity(any(), any(), any(), any());
		verify(ghl, never()).upsertContact(any(), any(), any(), any(), any());
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

		verify(outbox, never()).enqueue(any(), any(), any());
	}

	@Test
	void setsAFollowUpAsAGhlTask() {
		authenticateAsSales(MINE);
		givenItIsMine();
		when(ghl.createFollowUp(any(), any(), any(), any(), any(), any(), any())).thenReturn("task_1");

		assertThat(desk.followUp(OPPORTUNITY, "c1", "Call back", "2026-09-18T09:00:00Z", null, null))
				.isEqualTo("task_1");

		verify(ghl).createFollowUp("c1", OPPORTUNITY, MINE, "Call back", "2026-09-18T09:00:00Z", null, null);
	}

	/** A follow-up with no date is a note, and notes have their own route. */
	@Test
	void aFollowUpNeedsATitleAndADate() {
		authenticateAsSales(MINE);
		givenItIsMine();

		assertThatThrownBy(() -> desk.followUp(OPPORTUNITY, "c1", "  ", "2026-09-18T09:00:00Z", null, null))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> desk.followUp(OPPORTUNITY, "c1", "Call back", null, null, null))
				.isInstanceOf(InvalidRequestException.class);

		verify(ghl, never()).createFollowUp(any(), any(), any(), any(), any(), any(), any());
	}

	/** Every action refuses an opportunity outside the caller's pipeline, with 403 not 404. */
	@Test
	void anotherDesksDealIsRefusedByEveryAction() {
		authenticateAsSales(MINE);
		when(deals.isOnPipeline("opp_theirs", MINE)).thenReturn(false);

		assertThatThrownBy(() -> desk.update("opp_theirs", "x", null))
				.isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> desk.moveToStage("opp_theirs", "s2"))
				.isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> desk.close("opp_theirs", "won"))
				.isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> desk.followUp("opp_theirs", "c1", "Call", "2026-09-18T09:00:00Z", null, null))
				.isInstanceOf(ForbiddenException.class);

		verify(outbox, never()).enqueue(any(), any(), any());
		verify(deals, never()).editLocally(any(), any(), any(), any(), any());
		verify(ghl, never()).createFollowUp(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void aCallerWithNoPipelineIsRefused() {
		authenticateAsSales(null);

		assertThatThrownBy(() -> desk.close(OPPORTUNITY, "won")).isInstanceOf(ForbiddenException.class);
	}
}
