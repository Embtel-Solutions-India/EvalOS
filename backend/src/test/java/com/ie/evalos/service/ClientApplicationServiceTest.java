package com.ie.evalos.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ClientApplication;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.integration.GhlWriteClient;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ClientApplicationRepository;
import com.ie.evalos.security.PortalPrincipal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The funnel's one decision that cost an argument: <strong>the opportunity is opened when the
 * client picks a service, not when they submit</strong> (`43` §6a, resolved 2026-09-15). The
 * questionnaire is the longest part of the funnel and therefore where people stop, so a lead who
 * abandons halfway must already be on a salesperson's board. Two tests pin it:
 * {@link #pickingAServiceOpensTheDeal()} and {@link #aSecondStartReturnsTheDraftAndOpensNoSecondDeal()}.
 *
 * <p>{@link #noStageAndNoAssigneeAreSent()} pins the other half of the boundary: EvalOS puts the
 * opportunity on the intake pipeline and stops. Placement and routing are GHL's automation's, and
 * a "hot stage" property that briefly lived here made EvalOS hold a second opinion about them.
 *
 * <p>The second is the other half. {@code createOpportunity} is <strong>not</strong> an upsert —
 * `39` §3a's escape hatch, taken deliberately because a repeat client's second evaluation is a
 * genuine second deal — so nothing in GHL prevents a double-submit making two. The
 * `ghl_opportunity_id` column is the only thing that does.
 */
class ClientApplicationServiceTest {

	private static final UUID BRAND = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID ACCOUNT = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private static final String CONTACT = "ghl-contact-1";

	/** The EvalOS opportunity row's id — what goes into GHL's correlation custom field. */
	private static final UUID LOCAL_OPPORTUNITY = UUID.fromString("33333333-3333-3333-3333-333333333333");

	private final ClientApplicationRepository applications = mock(ClientApplicationRepository.class);

	private final ClientAccountRepository accounts = mock(ClientAccountRepository.class);

	private final GhlWriteClient ghl = mock(GhlWriteClient.class);

	private final com.ie.evalos.repository.PipelineRepository pipelines =
			mock(com.ie.evalos.repository.PipelineRepository.class);

	private static final String SERVICE_FIELD = "ghl-field-service";

	private static final String SUBMITTED_FIELD = "ghl-field-submitted";

	private static final String CORRELATION_FIELD = "ghl-field-correlation";

	private final OpportunityMirrorService deals = mock(OpportunityMirrorService.class);

	private final SyncOutboxService outbox = mock(SyncOutboxService.class);

	private final ClientApplicationService service = new ClientApplicationService(applications, accounts,
			ghl, pipelines, SERVICE_FIELD, SUBMITTED_FIELD, CORRELATION_FIELD, deals, outbox);

	/** The same service with no custom field configured — the unconfigured environment. */
	private ClientApplicationService withoutCustomFields() {
		return new ClientApplicationService(applications, accounts, ghl, pipelines, "", "", "", deals,
				outbox);
	}

	private ClientAccount client;

	@BeforeEach
	void signedInClient() {
		client = new ClientAccount(BRAND, "ana@example.com");
		client.linkGhlContact(CONTACT);
		client.setFirstName("Ana");
		client.setLastName("Okafor");
		given(accounts.findByBrandIdAndGhlContactId(BRAND, CONTACT)).willReturn(Optional.of(client));
		// ScopedEntity assigns ids at persist; the service reads getId() to scope, so the account
		// needs a real one or every ownership comparison would pass by matching null against null.
		setId(client, ACCOUNT);

		// The stages are never read — EvalOS resolves the pipeline id and sends no stage — but GHL
		// returns them, so the fixture does too rather than pretending a shape that never arrives.
		// The intake pipeline is the one a GM marked INTAKE, not one matched by name — Unit 44b
		// retired `evalos.ghl.intake-pipeline-name` because a rename in GHL silently stopped every
		// request from reaching Sales.
		com.ie.evalos.domain.Pipeline intake =
				new com.ie.evalos.domain.Pipeline(BRAND, "pipe-1", "Client Intake", 0);
		setId(intake, UUID.randomUUID());
		given(pipelines.findByBrandIdAndPurposeAndMissingSinceIsNullOrderByPositionAsc(BRAND,
				com.ie.evalos.domain.PipelinePurpose.INTAKE)).willReturn(List.of(intake));
		given(applications.saveAndFlush(any())).willAnswer((call) -> call.getArgument(0));
		// The local row EvalOS opens before it calls GHL — the correlation key's whole mechanism.
		given(deals.openLocally(any(), any(), any())).willAnswer((call) -> {
			com.ie.evalos.domain.Opportunity row =
					new com.ie.evalos.domain.Opportunity(BRAND, null, UUID.randomUUID());
			setId(row, LOCAL_OPPORTUNITY);
			return java.util.Optional.of(row);
		});
	}

	@Test
	void pickingAServiceOpensTheDeal() {
		given(ghl.createOpportunity(eq("pipe-1"), eq(CONTACT), anyString(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		ClientApplicationService.ApplicationView started =
				service.start(token(), "academic_evaluation", "Academic Evaluation", "immigration");

		assertThat(started.status()).isEqualTo("DRAFT");
		assertThat(started.serviceName()).isEqualTo("Academic Evaluation");

		// The name a salesperson scans a board for: the person, then what they want.
		ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
		verify(ghl).createOpportunity(eq("pipe-1"), eq(CONTACT), name.capture(), any(), any(),
				any(), any());
		assertThat(name.getValue()).isEqualTo("Ana Okafor — Academic Evaluation");

		// Never the upsert: it means one open opportunity per contact per pipeline, so a repeat
		// client's second request would overwrite their first rather than open a second.
		verify(ghl, never()).upsertOpportunity(any(), any(), any(), any());
	}

	/**
	 * <strong>EvalOS places the deal on the pipeline and stops.</strong> Which stage it lands in
	 * and who it is assigned to are GHL's automation's, which is what `00b` keeps GHL for — so
	 * both arguments go as null. A configured "hot stage" resolved by name lived here briefly and
	 * was removed: it was a second opinion about pipeline placement, stale the first time somebody
	 * reworked the workflow in GHL. `assignedTo` was never sent, for the reason
	 * `SalesOpportunityController` gives — nothing links a GHL user to an EvalOS team member.
	 */
	@Test
	void noStageAndNoAssigneeAreSent() {
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		service.start(token(), "academic_evaluation", "Academic Evaluation", null);

		ArgumentCaptor<String> stage = ArgumentCaptor.forClass(String.class);
		verify(ghl).createOpportunity(eq("pipe-1"), eq(CONTACT), anyString(), any(), stage.capture(),
				any(), any());
		assertThat(stage.getValue()).isNull();
	}

	/**
	 * <strong>The service goes to GHL as a custom field, and that is what lets GHL route it.</strong>
	 *
	 * <p>A workflow triggered by opportunity-created can branch on a custom field; it cannot branch
	 * on the free text in the name, which was the only place the service appeared before. The
	 * <em>id</em> is sent rather than the display name because the name is written for a human
	 * reading a board and is reworded whenever the catalog is, while a workflow condition has to
	 * keep matching.
	 *
	 * <p>Sent on <em>create</em> and not on submit, because that is when the trigger fires — and
	 * the deal is opened when the client picks a service, which {@link #pickingAServiceOpensTheDeal()}
	 * pins.
	 *
	 * <p>{@code containsEntry} rather than {@code containsExactly}: the same create also carries the
	 * correlation key (Unit 44d), and this test is about the routing field. The two are asserted
	 * together in {@link #theLocalRowIsOpenedBeforeGhlIsCalledAndItsIdIsTheCorrelationKey()}.
	 */
	@Test
	void theRequestedServiceIsSentAsTheRoutingField() {
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		service.start(token(), "eb1a_expert_opinion_letter", "EB-1A Expert Opinion Letter", null);

		ArgumentCaptor<java.util.Map<String, String>> fields = ArgumentCaptor.forClass(java.util.Map.class);
		verify(ghl).createOpportunity(eq("pipe-1"), eq(CONTACT), anyString(), any(), any(), any(),
				fields.capture());
		assertThat(fields.getValue()).containsEntry(SERVICE_FIELD, "eb1a_expert_opinion_letter");
	}

	/**
	 * An environment that has not created the field yet still opens the deal.
	 *
	 * <p>Null rather than an empty map, because {@code createOpportunity} drops blank entries
	 * anyway and a caller sending <code>{}</code> reads as "this request has no service", which is
	 * never true. **Losing the lead over an unconfigured field would be the worse failure**: the
	 * whole reason the deal is opened at service-pick is that a client who abandons the
	 * questionnaire must already be on a salesperson's board.
	 */
	@Test
	void anUnconfiguredFieldStillOpensTheDeal() {
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		withoutCustomFields().start(token(), "academic_evaluation", "Academic Evaluation", null);

		ArgumentCaptor<java.util.Map<String, String>> fields = ArgumentCaptor.forClass(java.util.Map.class);
		verify(ghl).createOpportunity(eq("pipe-1"), eq(CONTACT), anyString(), any(), any(), any(),
				fields.capture());
		assertThat(fields.getValue()).isNull();
	}

	/**
	 * <strong>Submitting tells GHL, and it tells it the one way that cannot undo GHL's routing.</strong>
	 *
	 * <p>The deal was opened when the client picked a service (D10), so by now GHL's own workflow
	 * has very probably moved it onto a service-specific pipeline. {@code setOpportunityFields}
	 * sends custom fields and nothing else — no pipeline, no stage, no name — so there is nothing
	 * for this call to move back. `moveStage` and `updateOpportunity` both carry a pipeline and are
	 * asserted absent here for exactly that reason.
	 */
	@Test
	void submittingMarksTheDealSubmittedWithoutTouchingItsPipeline() {
		ClientApplication existing = draft("opp-1");
		given(applications.findById(existing.getId())).willReturn(Optional.of(existing));

		service.submit(token(), existing.getId());

		verify(ghl).setOpportunityFields("opp-1", java.util.Map.of(SUBMITTED_FIELD, "SUBMITTED"));
		verify(ghl, never()).moveStage(any(), any(), any());
		verify(ghl, never()).updateOpportunity(any(), any(), any(), any(), any());
	}

	/**
	 * <strong>A GHL outage at submit does not throw the questionnaire away.</strong>
	 *
	 * <p>The opposite of the rule one line up in the service, and the difference is what Sales can
	 * see. No opportunity at all is a 502, because an application Sales cannot see reads to the
	 * client as "sent" and to the business as nothing at all. Here the deal is already on the
	 * board — only the marker is missing — so refusing would lose a completed questionnaire over a
	 * flag. EvalOS owns the request; GHL is the copy that lags.
	 */
	@Test
	void aFailedSubmitSignalStillSubmitsTheRequest() {
		ClientApplication existing = draft("opp-1");
		given(applications.findById(existing.getId())).willReturn(Optional.of(existing));
		org.mockito.BDDMockito.willThrow(new GhlUnavailableException("GHL did not answer"))
				.given(ghl).setOpportunityFields(any(), any());

		ClientApplicationService.ApplicationView submitted = service.submit(token(), existing.getId());

		assertThat(submitted.status()).isEqualTo("SUBMITTED");
	}

	/** No field configured is no call, not an empty one. */
	@Test
	void anUnconfiguredSubmittedFieldSendsNothing() {
		ClientApplication existing = draft("opp-1");
		given(applications.findById(existing.getId())).willReturn(Optional.of(existing));

		withoutCustomFields().submit(token(), existing.getId());

		verify(ghl, never()).setOpportunityFields(any(), any());
	}

	/**
	 * <strong>The correlation key, and the failure it exists to prevent.</strong>
	 *
	 * <p>{@code 00d} §6.1 calls this the single biggest sequencing error in {@code 00c}:
	 * {@code POST /opportunities/} is not idempotent and an outbox gives at-least-once delivery, so
	 * a create that TIMES OUT leaves EvalOS unable to tell "GHL never got it" from "GHL got it and
	 * the answer was lost". Retrying blind is exactly how one opportunity becomes two.
	 *
	 * <p>The fix is a key EvalOS chooses and GHL hands back — and it only works if the key is
	 * persisted <em>before</em> the call. So the order is asserted, not just the payload: the local
	 * row is opened first, its id goes into the custom field, and GHL's id is recorded afterwards.
	 */
	@Test
	void theLocalRowIsOpenedBeforeGhlIsCalledAndItsIdIsTheCorrelationKey() {
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		service.start(token(), "academic_evaluation", "Academic Evaluation", null);

		org.mockito.InOrder order = org.mockito.Mockito.inOrder(deals, ghl);
		order.verify(deals).openLocally(eq("pipe-1"), eq(CONTACT), anyString());
		order.verify(ghl).createOpportunity(any(), any(), any(), any(), any(), any(), any());
		order.verify(deals).linkGhl(LOCAL_OPPORTUNITY, "opp-1");

		ArgumentCaptor<java.util.Map<String, String>> fields = ArgumentCaptor.forClass(java.util.Map.class);
		verify(ghl).createOpportunity(any(), any(), anyString(), any(), any(), any(), fields.capture());
		assertThat(fields.getValue()).containsEntry(CORRELATION_FIELD, LOCAL_OPPORTUNITY.toString());
		// The service field rides along on the same create — two fields, two jobs.
		assertThat(fields.getValue()).containsEntry(SERVICE_FIELD, "academic_evaluation");
	}

	/**
	 * A retry reuses the row it already opened rather than minting a second one.
	 *
	 * <p>This is the other half of the mechanism. {@code linkOpportunityIfMissing} runs on start
	 * <em>and</em> on every save, so without this a client typing into the questionnaire after a GHL
	 * outage would open a fresh correlation key on each keystroke-driven save — and the retry would
	 * then be searching for a key GHL never saw.
	 */
	@Test
	void aRetryReusesTheRowItAlreadyOpened() {
		ClientApplication existing = draft(null);
		existing.linkOpportunityRow(LOCAL_OPPORTUNITY);
		given(applications.findByClientAccountIdAndStatus(ACCOUNT, ClientApplication.Status.DRAFT))
				.willReturn(Optional.of(existing));
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		service.start(token(), "academic_evaluation", "Academic Evaluation", null);

		verify(deals, never()).openLocally(any(), any(), any());
		ArgumentCaptor<java.util.Map<String, String>> fields = ArgumentCaptor.forClass(java.util.Map.class);
		verify(ghl).createOpportunity(any(), any(), anyString(), any(), any(), any(), fields.capture());
		assertThat(fields.getValue()).containsEntry(CORRELATION_FIELD, LOCAL_OPPORTUNITY.toString());
	}

	/**
	 * An unmirrored intake pipeline still opens the deal.
	 *
	 * <p>The local row cannot be written without a mirrored pipeline to hang it off, so the create
	 * goes out with no correlation key — which is the duplicate exposure that exists today, not a
	 * new one. Losing a client's request because the {@code PIPELINE_MIRROR} sweep is behind would
	 * be the worse failure by a wide margin.
	 */
	@Test
	void anUnmirroredPipelineStillOpensTheDealWithoutACorrelationKey() {
		given(deals.openLocally(any(), any(), any())).willReturn(Optional.empty());
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		service.start(token(), "academic_evaluation", "Academic Evaluation", null);

		ArgumentCaptor<java.util.Map<String, String>> fields = ArgumentCaptor.forClass(java.util.Map.class);
		verify(ghl).createOpportunity(any(), any(), anyString(), any(), any(), any(), fields.capture());
		assertThat(fields.getValue()).doesNotContainKey(CORRELATION_FIELD);
		assertThat(fields.getValue()).containsEntry(SERVICE_FIELD, "academic_evaluation");
		verify(deals, never()).linkGhl(any(), any());
	}

	/**
	 * <strong>No price is sent, and that is a decision rather than an omission.</strong> EvalOS
	 * holds no price list — the catalog carries descriptions and document lists, not amounts — and
	 * what the work is worth is Sales' to set on the deal. A zero would be a priced deal worth
	 * nothing rather than an unpriced one, and it would land in the GM dashboard's won figures as
	 * exactly that.
	 */
	@Test
	void noPriceIsSent() {
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		service.start(token(), "academic_evaluation", "Academic Evaluation", null);

		ArgumentCaptor<BigDecimal> value = ArgumentCaptor.forClass(BigDecimal.class);
		verify(ghl).createOpportunity(eq("pipe-1"), eq(CONTACT), anyString(), value.capture(), any(),
				any(), any());
		assertThat(value.getValue()).isNull();
	}

	@Test
	void aSecondStartReturnsTheDraftAndOpensNoSecondDeal() {
		ClientApplication existing = draft("opp-1");
		given(applications.findByClientAccountIdAndStatus(ACCOUNT, ClientApplication.Status.DRAFT))
				.willReturn(Optional.of(existing));

		ClientApplicationService.ApplicationView again =
				service.start(token(), "academic_evaluation", "Academic Evaluation", null);

		assertThat(again.serviceName()).isEqualTo("Course-by-Course Evaluation");
		verify(applications, never()).saveAndFlush(any());
		verify(ghl, never()).createOpportunity(any(), any(), any(), any(), any(), any(), any());
	}

	/**
	 * <strong>A GHL outage must not cost the client their questionnaire.</strong> The row is
	 * written first and the opportunity linked after, so an upstream refusal leaves a usable
	 * draft; the next save retries. The opposite order trades somebody else's outage for the
	 * client's typing.
	 */
	@Test
	void aGhlOutageStillLeavesTheClientADraft() {
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willThrow(new GhlUnavailableException("GHL is not configured here"));

		ClientApplicationService.ApplicationView started =
				service.start(token(), "academic_evaluation", "Academic Evaluation", null);

		assertThat(started.status()).isEqualTo("DRAFT");
	}

	/**
	 * <strong>...but submitting one Sales cannot see is refused.</strong> It reads to the client
	 * as "sent" and to the business as nothing at all, which is the one failure this flow must
	 * not have.
	 */
	@Test
	void submitWithNoOpportunityIsRefusedAndTheDraftSurvives() {
		ClientApplication stranded = draft(null);
		given(applications.findById(stranded.getId())).willReturn(Optional.of(stranded));
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willThrow(new GhlUnavailableException("GHL is not configured here"));

		assertThatThrownBy(() -> service.submit(token(), stranded.getId()))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(stranded.isDraft()).isTrue();
	}

	@Test
	void submitStampsTheTimeAndTouchesTheOpportunityNotAtAll() {
		ClientApplication ready = draft("opp-1");
		given(applications.findById(ready.getId())).willReturn(Optional.of(ready));

		ClientApplicationService.ApplicationView submitted = service.submit(token(), ready.getId());

		assertThat(submitted.status()).isEqualTo("SUBMITTED");
		assertThat(submitted.submittedAt()).isNotNull();
		// The deal has existed since `start`, and where it sits is GHL's automation's business.
		// Moving it from here would be EvalOS overriding a workflow it does not own.
		verify(ghl, never()).moveStage(any(), any(), any());
		verify(ghl, never()).updateOpportunity(any(), any(), any(), any(), any());
	}

	@Test
	void aSubmittedRequestCannotBeEditedOrSubmittedTwice() {
		ClientApplication done = draft("opp-1");
		done.submit();
		given(applications.findById(done.getId())).willReturn(Optional.of(done));

		assertThatThrownBy(() -> service.save(token(), done.getId(), "{}", null))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.submit(token(), done.getId()))
				.isInstanceOf(InvalidRequestException.class);
	}

	/**
	 * <strong>403, not an empty result.</strong> Reading through a per-account finder would make
	 * "not yours" and "does not exist" the same answer, and only one of them is honest.
	 */
	@Test
	void anotherClientsRequestIsRefused() {
		ClientApplication theirs = new ClientApplication(BRAND, UUID.randomUUID(), "academic_evaluation",
				"Academic Evaluation", null);
		setId(theirs, UUID.randomUUID());
		given(applications.findById(theirs.getId())).willReturn(Optional.of(theirs));

		assertThatThrownBy(() -> service.save(token(), theirs.getId(), "{}", null))
				.isInstanceOf(ForbiddenException.class);
	}

	/** A token naming nobody we hold reaches no application at all. */
	@Test
	void aTokenWhoseContactWeDoNotHoldIsRefused() {
		PortalPrincipal stranger = new PortalPrincipal(UUID.randomUUID(), BRAND, null,
				PortalAudience.CLIENT, null, "ghl-somebody-else");

		assertThatThrownBy(() -> service.mine(stranger)).isInstanceOf(ForbiddenException.class);
	}

	/** The answers ceiling is a trust-boundary check, not a judgement about how much a client types. */
	@Test
	void anUnboundedAnswersPayloadIsRefused() {
		ClientApplication open = draft("opp-1");
		given(applications.findById(open.getId())).willReturn(Optional.of(open));

		assertThatThrownBy(() -> service.save(token(), open.getId(), "x".repeat(64 * 1024 + 1), null))
				.isInstanceOf(InvalidRequestException.class);
	}

	private PortalPrincipal token() {
		return new PortalPrincipal(UUID.randomUUID(), BRAND, null, PortalAudience.CLIENT, null, CONTACT);
	}

	private ClientApplication draft(String opportunityId) {
		ClientApplication row = new ClientApplication(BRAND, ACCOUNT, "course_by_course_evaluation",
				"Course-by-Course Evaluation", null);
		setId(row, UUID.randomUUID());
		if (opportunityId != null) {
			row.linkOpportunity(opportunityId);
		}
		return row;
	}

	private static GhlWriteClient.UpsertedOpportunity opened(String id) {
		return new GhlWriteClient.UpsertedOpportunity(id, CONTACT, "pipe-1", "stage-new", "open",
				"Ana Okafor", BigDecimal.ZERO, true);
	}

	/** {@code ScopedEntity} assigns ids at persist, and these entities are never persisted here. */
	private static void setId(Object entity, UUID id) {
		try {
			java.lang.reflect.Field field = com.ie.evalos.domain.ScopedEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(entity, id);
		}
		catch (ReflectiveOperationException cannotHappen) {
			throw new IllegalStateException(cannotHappen);
		}
	}
}
