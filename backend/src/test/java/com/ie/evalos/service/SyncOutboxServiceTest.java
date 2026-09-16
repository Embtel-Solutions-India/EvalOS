package com.ie.evalos.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.SyncEntity;
import com.ie.evalos.domain.SyncOutboxEntry;
import com.ie.evalos.integration.GhlFailure;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.integration.GhlWriteClient;
import com.ie.evalos.repository.OpportunityRepository;
import com.ie.evalos.repository.PipelineRepository;
import com.ie.evalos.repository.SyncOutboxRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * The outbox's four decisions, and the one that the whole of {@code 00d} §6.1 is about.
 *
 * <p>A retry is only safe if it cannot double-apply. {@code POST /opportunities/} is not idempotent,
 * so a create that <em>timed out</em> leaves EvalOS unable to tell "GHL never got it" from "GHL got
 * it and the reply was lost" — and at-least-once delivery over that is exactly how one opportunity
 * becomes two. {@link #aTimedOutCreateIsLinkedRatherThanMadeTwice()} is the test that says the fix
 * works; everything else here protects the budget while it does.
 */
class SyncOutboxServiceTest {

	private static final UUID BRAND = UUID.randomUUID();

	private static final String CORRELATION_FIELD = "ghl-field-correlation";

	private final SyncOutboxRepository outbox = mock(SyncOutboxRepository.class);

	private final OpportunityRepository opportunities = mock(OpportunityRepository.class);

	private final PipelineRepository pipelines = mock(PipelineRepository.class);

	private final GhlWriteClient ghl = mock(GhlWriteClient.class);

	private final GhlPipelineClient ghlReads = mock(GhlPipelineClient.class);

	private SyncOutboxService service = newService(CORRELATION_FIELD);

	private SyncOutboxService newService(String correlationField) {
		return new SyncOutboxService(outbox, opportunities, pipelines, ghl, ghlReads, BRAND.toString(),
				correlationField);
	}

	private Pipeline pipeline;

	@BeforeEach
	void mirroredPipeline() {
		pipeline = new Pipeline(BRAND, "pipe-1", "Sales", 0);
		ReflectionTestUtils.setField(pipeline, "id", UUID.randomUUID());
		given(pipelines.findById(pipeline.getId())).willReturn(Optional.of(pipeline));
		given(outbox.save(any())).willAnswer((call) -> call.getArgument(0));
		given(outbox.findById(any())).willReturn(Optional.empty());
	}

	private Opportunity local(String ghlId) {
		Opportunity row = new Opportunity(BRAND, ghlId, pipeline.getId());
		ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
		row.syncFromGhl("contact-1", pipeline.getId(), null, "Ana — Academic Evaluation",
				new BigDecimal("500"), "open", null, null, null, null, null, null);
		given(opportunities.findById(row.getId())).willReturn(Optional.of(row));
		return row;
	}

	private SyncOutboxEntry queued(Opportunity row) {
		SyncOutboxEntry entry = new SyncOutboxEntry(BRAND, SyncEntity.OPPORTUNITY, row.getId(),
				SyncOutboxEntry.Intent.UPSERT);
		ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());
		given(outbox.findByBrandIdAndSentAtIsNullAndDeadAtIsNullOrderByQueuedAtAsc(eq(BRAND), any(Limit.class)))
				.willReturn(List.of(entry));
		return entry;
	}

	private static GhlPipelineClient.Opportunity inGhl(String id, String name) {
		return new GhlPipelineClient.Opportunity(id, name, "contact-1", "pipe-1", "s1", "open",
				new BigDecimal("500"), null, null, null, null, null, null);
	}

	/**
	 * <strong>The test {@code 00d} §6.1 exists for.</strong>
	 *
	 * <p>EvalOS created the deal, GHL never answered, and the row still has no {@code ghl_id}. On
	 * retry the outbox asks GHL for the <em>contact's</em> opportunities — the only filter GHL
	 * actually offers, since neither search endpoint accepts a custom-field one — and finds its own
	 * correlation key. The create already landed. Linking is the right answer; creating again would
	 * be the duplicate the whole scheme exists to prevent.
	 */
	@Test
	void aTimedOutCreateIsLinkedRatherThanMadeTwice() {
		Opportunity row = local(null);
		queued(row);
		// GHL's row carries the correlation key EvalOS wrote on the first attempt.
		given(ghlReads.forContact("contact-1"))
				.willReturn(List.of(inGhl("opp-already-there", "Ana [" + row.getId() + "]")));

		var result = service.drain();

		assertThat(result.sent()).isEqualTo(1);
		assertThat(row.getGhlId()).isEqualTo("opp-already-there");
		then(ghl).should(never()).createOpportunity(any(), any(), any(), any(), any(), any(), any());
	}

	/** When the contact genuinely has no such deal, the create goes out — carrying the key. */
	@Test
	void aCreateThatNeverLandedIsSentWithTheCorrelationKey() {
		Opportunity row = local(null);
		queued(row);
		given(ghlReads.forContact("contact-1")).willReturn(List.of());
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(new GhlWriteClient.UpsertedOpportunity("opp-new", "contact-1", "pipe-1", "s1",
						"open", "Ana", null, true));

		service.drain();

		org.mockito.ArgumentCaptor<java.util.Map<String, String>> fields = org.mockito.ArgumentCaptor.captor();
		then(ghl).should().createOpportunity(eq("pipe-1"), eq("contact-1"), anyString(), any(), any(),
				any(), fields.capture());
		assertThat(fields.getValue()).containsEntry(CORRELATION_FIELD, row.getId().toString());
		assertThat(row.getGhlId()).isEqualTo("opp-new");
	}

	/**
	 * With no correlation field there is nothing to look for, so GHL is not asked.
	 *
	 * <p>The create goes out unguarded — which is the duplicate exposure that exists today rather
	 * than a new one. Refusing to retry instead would lose the client's request outright, which is
	 * the worse of the two.
	 */
	@Test
	void anUnconfiguredCorrelationFieldSkipsTheLookupRatherThanRefusingToRetry() {
		Opportunity row = local(null);
		queued(row);
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(new GhlWriteClient.UpsertedOpportunity("opp-new", "contact-1", "pipe-1", "s1",
						"open", "Ana", null, true));

		newService("").drain();

		then(ghlReads).should(never()).forContact(any());
		then(ghl).should().createOpportunity(any(), any(), any(), any(), any(), any(), any());
	}

	/** A row GHL already knows is an update, not a second create. */
	@Test
	void anAlreadyLinkedRowIsUpdated() {
		Opportunity row = local("opp-1");
		queued(row);
		given(ghl.updateOpportunity(any(), any(), any(), any(), any()))
				.willReturn(new GhlWriteClient.UpsertedOpportunity("opp-1", "contact-1", "pipe-1", "s1",
						"open", "Ana", null, false));

		assertThat(service.drain().sent()).isEqualTo(1);

		then(ghl).should().updateOpportunity(eq("opp-1"), eq("pipe-1"), anyString(), any(), any());
		then(ghl).should(never()).createOpportunity(any(), any(), any(), any(), any(), any(), any());
	}

	/**
	 * <strong>A 429 stops the whole drain, not just the row that hit it.</strong>
	 *
	 * <p>{@code 00d} §6.3: "429 must pause the WHOLE outbox (the budget is per location)". Backing
	 * off one row while the next fires immediately is not a back-off — it is the same number of
	 * requests in the same second, arriving from a different line of code.
	 */
	@Test
	void aRateLimitHaltsTheDrainAndLeavesTheRowPending() {
		Opportunity row = local("opp-1");
		SyncOutboxEntry entry = queued(row);
		willThrow(new GhlUnavailableException("rate limited", null, GhlFailure.RATE_LIMITED, 429))
				.given(ghl).updateOpportunity(any(), any(), any(), any(), any());

		var result = service.drain();

		assertThat(result.halted()).isTrue();
		assertThat(result.dead()).isZero();
		assertThat(entry.isPending()).isTrue();
	}

	/**
	 * A 401/403 halts it too, for a different reason: nothing in EvalOS can fix a missing scope.
	 *
	 * <p>"Retrying a scope failure ten times across every pending row spends the entire budget on a
	 * credential problem." The row stays pending so that fixing the grant is all it takes.
	 */
	@Test
	void aScopeFailureHaltsTheDrainRatherThanBurningEveryRow() {
		Opportunity row = local("opp-1");
		SyncOutboxEntry entry = queued(row);
		willThrow(new GhlUnavailableException("no scope", null, GhlFailure.UNAUTHORIZED, 403))
				.given(ghl).updateOpportunity(any(), any(), any(), any(), any());

		var result = service.drain();

		assertThat(result.halted()).isTrue();
		assertThat(entry.isPending()).isTrue();
		assertThat(entry.getLastFailure()).isEqualTo(GhlFailure.UNAUTHORIZED);
	}

	/**
	 * A refusal no retry can fix is dead-lettered on the first attempt.
	 *
	 * <p>A malformed body sent again is still malformed. Looping on it spends budget proving that,
	 * and buries the row under four more attempts before anybody sees it.
	 */
	@Test
	void aNonRetriableRefusalIsDeadLetteredImmediately() {
		Opportunity row = local("opp-1");
		SyncOutboxEntry entry = queued(row);
		willThrow(new GhlUnavailableException("bad request", null, GhlFailure.REFUSED, 400))
				.given(ghl).updateOpportunity(any(), any(), any(), any(), any());

		var result = service.drain();

		assertThat(result.dead()).isEqualTo(1);
		assertThat(result.halted()).isFalse();
		assertThat(entry.getDeadAt()).isNotNull();
		assertThat(entry.isPending()).isFalse();
	}

	/** A transient failure is retried — and bounded, so it cannot spend budget forever. */
	@Test
	void aTransientFailureIsRetriedUntilTheCapThenDeadLettered() {
		Opportunity row = local("opp-1");
		SyncOutboxEntry entry = queued(row);
		willThrow(new GhlUnavailableException("GHL did not answer", null, GhlFailure.NO_ANSWER, null))
				.given(ghl).updateOpportunity(any(), any(), any(), any(), any());

		assertThat(service.drain().retrying()).isEqualTo(1);
		assertThat(entry.isPending()).isTrue();

		// Four attempts already spent; the fifth is the cap.
		ReflectionTestUtils.setField(entry, "attempts", 4);
		assertThat(service.drain().dead()).isEqualTo(1);
		assertThat(entry.getDeadAt()).isNotNull();
	}

	/**
	 * An EvalOS defect is dead-lettered, never retried.
	 *
	 * <p>A {@code NullPointerException} does not become true on the fourth attempt, and looping on it
	 * would hide the bug behind a queue that never drains.
	 */
	@Test
	void anEvalosBugIsDeadLetteredRatherThanLoopedOn() {
		Opportunity row = local("opp-1");
		SyncOutboxEntry entry = queued(row);
		willThrow(new IllegalStateException("our bug"))
				.given(ghl).updateOpportunity(any(), any(), any(), any(), any());

		assertThat(service.drain().dead()).isEqualTo(1);
		assertThat(entry.getDeadReason()).contains("our bug");
	}

	/** Nothing to sync into means nothing to drain, and GHL is not called. */
	@Test
	void aBlankSellingBrandDrainsNothing() {
		SyncOutboxService unconfigured = new SyncOutboxService(outbox, opportunities, pipelines, ghl,
				ghlReads, "", CORRELATION_FIELD);

		assertThat(unconfigured.drain().attempted()).isZero();
		then(ghl).shouldHaveNoInteractions();
	}

}
