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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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

	/** D3c's backfill door. Stubbed to do nothing, so a client who has a contact id keeps it. */
	private final ClientAccountService accountsService = mock(ClientAccountService.class);

	/** Unit 53 / 2026-09-19: the submit confirmation and the document count it states. */
	private final ClientMailer mailer = mock(ClientMailer.class);
	private final ApplicationDocumentService requestDocuments = mock(ApplicationDocumentService.class);

	private final ClientApplicationService service = new ClientApplicationService(applications, accounts,
			ghl, pipelines, SERVICE_FIELD, SUBMITTED_FIELD, CORRELATION_FIELD, deals, outbox,
			accountsService, mailer, requestDocuments);

	/** The same service with no custom field configured — the unconfigured environment. */
	private ClientApplicationService withoutCustomFields() {
		return new ClientApplicationService(applications, accounts, ghl, pipelines, "", "", "", deals,
				outbox, accountsService, mailer, requestDocuments);
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

	/**
	 * <strong>Submitting is what opens the deal (D10, changed 2026-09-16).</strong>
	 *
	 * <p>It was service-pick until then, so that a client who abandoned the questionnaire still
	 * reached a salesperson. Refused twice, carried at the third asking: a deal on the board is now
	 * a finished request and nothing else.
	 */
	@Test
	void submittingOpensTheDeal() {
		given(ghl.createOpportunity(eq("pipe-1"), eq(CONTACT), anyString(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		submitFresh(service, "academic_evaluation", "Academic Evaluation");

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

		submitFresh(service, "academic_evaluation", "Academic Evaluation");

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
	 * <p>Sent on the <em>create</em>, because that is when the workflow trigger fires — and the
	 * create happens at submit, which {@link #submittingOpensTheDeal()} pins.
	 *
	 * <p>{@code containsEntry} rather than {@code containsExactly}: the same create also carries the
	 * correlation key (Unit 44d), and this test is about the routing field. The two are asserted
	 * together in {@link #theLocalRowIsOpenedBeforeGhlIsCalledAndItsIdIsTheCorrelationKey()}.
	 */
	@Test
	void theRequestedServiceIsSentAsTheRoutingField() {
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		submitFresh(service, "eb1a_expert_opinion_letter", "EB-1A Expert Opinion Letter");

		ArgumentCaptor<java.util.Map<String, String>> fields = ArgumentCaptor.captor();
		verify(ghl).createOpportunity(eq("pipe-1"), eq(CONTACT), anyString(), any(), any(), any(),
				fields.capture());
		assertThat(fields.getValue()).containsEntry(SERVICE_FIELD, "eb1a_expert_opinion_letter");
	}

	/**
	 * An environment that has not created the field yet still opens the deal.
	 *
	 * <p>Null rather than an empty map, because {@code createOpportunity} drops blank entries
	 * anyway and a caller sending <code>{}</code> reads as "this request has no service", which is
	 * never true. **Losing the request over an unconfigured field would be the worse failure**: the
	 * client has finished the questionnaire by this point, and an environment that has not created
	 * the field yet must not turn that into nothing.
	 */
	@Test
	void anUnconfiguredFieldStillOpensTheDeal() {
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		submitFresh(withoutCustomFields(), "academic_evaluation", "Academic Evaluation");

		ArgumentCaptor<java.util.Map<String, String>> fields = ArgumentCaptor.captor();
		verify(ghl).createOpportunity(eq("pipe-1"), eq(CONTACT), anyString(), any(), any(), any(),
				fields.capture());
		assertThat(fields.getValue()).isNull();
	}

	/**
	 * <strong>The SUBMITTED marker rides the create, and the second call is gone (D12).</strong>
	 *
	 * <p>It used to be a {@code setOpportunityFields} follow-up, because the old D10 opened the deal
	 * at service-pick and a board could not tell somebody browsing from a finished request. Only a
	 * submit opens a deal now, so every opportunity is a submitted one and a second call would
	 * announce what the row's existence already says. The field is still written so a GHL workflow
	 * keyed on it keeps firing.
	 *
	 * <p>{@code moveStage} and {@code updateOpportunity} both carry a pipeline and are asserted
	 * absent: EvalOS places the deal and never moves it again.
	 */
	@Test
	void theSubmittedMarkerRidesTheCreateAndNothingFollowsIt() {
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		submitFresh(service, "academic_evaluation", "Academic Evaluation");

		ArgumentCaptor<java.util.Map<String, String>> fields = ArgumentCaptor.captor();
		verify(ghl).createOpportunity(any(), any(), anyString(), any(), any(), any(), fields.capture());
		assertThat(fields.getValue()).containsEntry(SUBMITTED_FIELD, "SUBMITTED");

		verify(ghl, never()).setOpportunityFields(any(), any());
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
	/**
	 * <strong>A GHL outage at submit refuses, and that is stricter than it used to be (D12).</strong>
	 *
	 * <p>The failure used to be swallowed: the deal already existed, only the marker was missing,
	 * so refusing would have thrown away a finished questionnaire over a flag. Under D10 a failed
	 * create means Sales has <em>no deal at all</em> — telling a client "sent" for that is the one
	 * lie this flow must not tell. The draft survives and the next attempt retries.
	 */
	@Test
	void aFailedCreateRefusesTheSubmitAndKeepsTheDraft() {
		ClientApplication row = freshDraft("academic_evaluation", "Academic Evaluation");
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willThrow(new GhlUnavailableException("GHL did not answer"));

		assertThatThrownBy(() -> service.submit(token(), row.getId()))
				.isInstanceOf(InvalidRequestException.class);

		assertThat(row.isDraft()).isTrue();
	}

	/**
	 * <strong>An unmarked INTAKE pipeline refuses the submit without ever calling GHL.</strong>
	 *
	 * <p>This was the state of a fresh environment on 2026-09-19 — twelve mirrored pipelines, all
	 * {@code UNASSIGNED} — and it produced a 400 saying "we could not reach our systems, please try
	 * again in a moment" while GHL was perfectly reachable and no amount of trying would ever help.
	 * The refusal is right; what was wrong is that it was indistinguishable from an outage, so the
	 * pipeline resolution now happens outside the outage catch and logs at ERROR.
	 *
	 * <p>Asserting {@code shouldHaveNoInteractions} on the GHL client is the point: an environment
	 * problem must not spend a request proving itself.
	 */
	@Test
	void anUnmarkedIntakePipelineRefusesTheSubmitWithoutCallingGhl() {
		given(pipelines.findByBrandIdAndPurposeAndMissingSinceIsNullOrderByPositionAsc(BRAND,
				com.ie.evalos.domain.PipelinePurpose.INTAKE)).willReturn(java.util.List.of());
		ClientApplication row = freshDraft("academic_evaluation", "Academic Evaluation");

		assertThatThrownBy(() -> service.submit(token(), row.getId()))
				.isInstanceOf(InvalidRequestException.class);

		assertThat(row.isDraft()).isTrue();
		then(ghl).shouldHaveNoInteractions();
	}

	/** Two marked pipelines is the same refusal: a request has nowhere unambiguous to go. */
	@Test
	void twoMarkedIntakePipelinesAlsoRefuseRatherThanGuessing() {
		com.ie.evalos.domain.Pipeline second =
				new com.ie.evalos.domain.Pipeline(BRAND, "pipe-2", "Also Intake", 1);
		setId(second, UUID.randomUUID());
		com.ie.evalos.domain.Pipeline first =
				new com.ie.evalos.domain.Pipeline(BRAND, "pipe-1", "Client Intake", 0);
		setId(first, UUID.randomUUID());
		given(pipelines.findByBrandIdAndPurposeAndMissingSinceIsNullOrderByPositionAsc(BRAND,
				com.ie.evalos.domain.PipelinePurpose.INTAKE)).willReturn(java.util.List.of(first, second));
		ClientApplication row = freshDraft("academic_evaluation", "Academic Evaluation");

		assertThatThrownBy(() -> service.submit(token(), row.getId()))
				.isInstanceOf(InvalidRequestException.class);

		then(ghl).shouldHaveNoInteractions();
	}

	/** No field configured omits it from the create rather than sending it blank. */
	@Test
	void anUnconfiguredSubmittedFieldIsOmitted() {
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		submitFresh(withoutCustomFields(), "academic_evaluation", "Academic Evaluation");

		ArgumentCaptor<java.util.Map<String, String>> fields = ArgumentCaptor.captor();
		verify(ghl).createOpportunity(any(), any(), anyString(), any(), any(), any(), fields.capture());
		assertThat(fields.getValue()).isNull();
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

		submitFresh(service, "academic_evaluation", "Academic Evaluation");

		org.mockito.InOrder order = org.mockito.Mockito.inOrder(deals, ghl);
		order.verify(deals).openLocally(eq("pipe-1"), eq(CONTACT), anyString());
		order.verify(ghl).createOpportunity(any(), any(), any(), any(), any(), any(), any());
		order.verify(deals).linkGhl(LOCAL_OPPORTUNITY, "opp-1");

		ArgumentCaptor<java.util.Map<String, String>> fields = ArgumentCaptor.captor();
		verify(ghl).createOpportunity(any(), any(), anyString(), any(), any(), any(), fields.capture());
		assertThat(fields.getValue()).containsEntry(CORRELATION_FIELD, LOCAL_OPPORTUNITY.toString());
		// The service field rides along on the same create — two fields, two jobs.
		assertThat(fields.getValue()).containsEntry(SERVICE_FIELD, "academic_evaluation");
	}

	/**
	 * A retry reuses the row it already opened rather than minting a second one.
	 *
	 * <p>This is the other half of the mechanism. A refused submit leaves the application a draft,
	 * so the client presses the button again — and without this each attempt would open a fresh
	 * correlation key, leaving the retry searching for one GHL never saw.
	 */
	@Test
	void aRetryReusesTheRowItAlreadyOpened() {
		ClientApplication existing = freshDraft("academic_evaluation", "Academic Evaluation");
		existing.linkOpportunityRow(LOCAL_OPPORTUNITY);
		given(ghl.createOpportunity(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(opened("opp-1"));

		service.submit(token(), existing.getId());

		verify(deals, never()).openLocally(any(), any(), any());
		ArgumentCaptor<java.util.Map<String, String>> fields = ArgumentCaptor.captor();
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

		submitFresh(service, "academic_evaluation", "Academic Evaluation");

		ArgumentCaptor<java.util.Map<String, String>> fields = ArgumentCaptor.captor();
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

		submitFresh(service, "academic_evaluation", "Academic Evaluation");

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
	 * <strong>Starting a request reaches GHL zero times (D10).</strong> The whole funnel up to the
	 * submit button is EvalOS's own rows — a client browsing and typing costs the GHL budget
	 * nothing, and an outage over there is invisible until they press submit.
	 */
	@Test
	void startingAndSavingReachGhlZeroTimes() {
		ClientApplication row = freshDraft("academic_evaluation", "Academic Evaluation");

		ClientApplicationService.ApplicationView started =
				service.start(token(), "academic_evaluation", "Academic Evaluation", null);
		service.save(token(), row.getId(), "{\"q1\":\"a\"}", null);

		verify(ghl, never()).createOpportunity(any(), any(), any(), any(), any(), any(), any());
		verify(deals, never()).openLocally(any(), any(), any());
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

	/**
	 * A draft with no opportunity, registered with the repository mock — the state a client is in
	 * when they press submit, which is the only thing that opens a deal now (D10).
	 */
	private ClientApplication freshDraft(String serviceId, String serviceName) {
		ClientApplication row = new ClientApplication(BRAND, ACCOUNT, serviceId, serviceName, null);
		setId(row, UUID.randomUUID());
		given(applications.findById(row.getId())).willReturn(Optional.of(row));
		return row;
	}

	/** Start-to-submit in one line, for the tests that are about what the create carries. */
	private ClientApplication submitFresh(ClientApplicationService svc, String serviceId, String serviceName) {
		ClientApplication row = freshDraft(serviceId, serviceName);
		svc.submit(token(), row.getId());
		return row;
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
