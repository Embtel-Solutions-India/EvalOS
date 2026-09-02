package com.ie.evalos.webhook;

import java.nio.charset.Charset;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.WebhookEvent;
import com.ie.evalos.domain.WebhookSource;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.WebhookEventRepository;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.service.CaseIntakeService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gateway's acceptance criteria over the real filter chain and the real pipeline:
 * only a delivery to a known, active endpoint does anything, a redelivery does nothing
 * twice, and a failure downstream is retriable. What the accepted delivery then creates
 * is asserted in {@code CaseIntakeServiceTest}.
 *
 * <p>No request here sends a bearer token or a signature header, and that is the point:
 * the endpoint is public and gated entirely by the path token, so GHL's Custom Webhook
 * action can post to it with nothing but a URL and a JSON body.
 */
@WebMvcTest(controllers = InboundWebhookController.class)
@Import({ WebhookGateway.class, WebhookRouter.class, GhlOpportunityHandler.class,
		SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class InboundWebhookTest {

	private static final String TOKEN = "local-ie-webhook-token";
	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID BRAND_XP = UUID.fromString("22222222-2222-2222-2222-222222222222");

	/** GHL's own delivery id: the idempotency key now that a contact has no invoice. */
	private static final String EVENT_ID = "evt-99123";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	BrandRepository brands;

	@MockitoBean
	WebhookEventRepository webhookEvents;

	@MockitoBean
	CaseIntakeService intake;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	@BeforeEach
	void oneActiveBrand() {
		Brand brand = mock(Brand.class);
		given(brand.getId()).willReturn(BRAND_IE);
		given(brand.getName()).willReturn("International Evaluations");
		given(brand.getSlug()).willReturn("international-evaluations");

		given(brands.findByWebhookEndpointTokenAndActiveTrue(TOKEN)).willReturn(Optional.of(brand));
		given(webhookEvents.save(any(WebhookEvent.class))).willAnswer(call -> call.getArgument(0));
	}

	/**
	 * A live GHL delivery, copied rather than imagined. The Custom Webhook action is wired to
	 * the <strong>Contact</strong> lookup, so everything GHL writes is the contact record, flat
	 * at the top level — it contains no opportunity block, no amount and no delivery id,
	 * because a contact has none of those. The deal is in {@code customData}, which is the one
	 * part of the body the workflow author fills in; GHL still sends the {@code event_id}
	 * beside it empty.
	 *
	 * <p>Note {@code contact} is present and is <em>not</em> the contact — it holds attribution
	 * data. Nothing reads it, and this body is what proves it.
	 */
	private static final String WON_BODY = """
			{
			  "contact_id": "NbJ72PwZKN26IMzEYntf",
			  "first_name": "Anita",
			  "last_name": "Rao",
			  "full_name": "Anita Rao",
			  "email": "anita@raolaw.example",
			  "tags": "2_sep_2026_6_32_am",
			  "date_created": "2026-09-02T01:03:25.791Z",
			  "full_address": "",
			  "contact_type": "lead",
			  "location": {"name": "International Evaluations", "id": "kBumF0uUOmMBB5bneYjx"},
			  "workflow": {"id": "3089c141", "name": "Webhook for Case creation in EvalOS"},
			  "triggerData": {},
			  "contact": {"attributionSource": {"sessionSource": "CRM UI", "medium": "csv_import"}},
			  "attributionSource": {},
			  "customData": {
			    "event_type": "opportunity.won",
			    "event_id": "",
			    "service_type": "EXPERT_OPINION_LETTER",
			    "opportunity_id": "opp-4711",
			    "amount": 1450.00
			  }
			}""";

	/**
	 * The same body with a delivery id put into it, for the tests that need to name the key
	 * they are asserting on. GHL does not send one — see {@link #WON_BODY} and
	 * {@link #aDeliveryCarryingNoEventIdIsKeyedOnADigestOfItsBody}.
	 */
	private static String wonBody(String eventId) {
		return WON_BODY.replace("\"event_id\": \"\"", "\"event_id\": \"" + eventId + "\"");
	}

	/** Exactly what GHL sends: a URL, a content type, a body. No other header. */
	private ResultActions deliver(String token, String body) throws Exception {
		return mockMvc.perform(post("/api/webhooks/ghl/{token}", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions deliver(String body) throws Exception {
		return deliver(TOKEN, body);
	}

	/**
	 * The stated contract, asserted as its own case rather than left implied by every
	 * other test: a delivery carrying no {@code X-Evalos-Signature} — because GHL's
	 * Custom Webhook action cannot produce one — is accepted on the token and the
	 * payload alone.
	 */
	@Test
	void aDeliveryWithNoSignatureHeaderIsAccepted() throws Exception {
		mockMvc.perform(post("/api/webhooks/ghl/{token}", TOKEN)
						.contentType(MediaType.APPLICATION_JSON)
						.content(wonBody(EVENT_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("accepted"));

		verify(intake).intake(any(Brand.class), any(CaseIntakeService.NewCase.class));
	}

	/**
	 * A signature header is not merely optional, it is ignored: a sender that still sends
	 * one, or sends a wrong one, is treated no differently from one that sends none.
	 * Nothing reads that header any more, and this is what fails if something starts to.
	 */
	@Test
	void aSignatureHeaderIfSentIsIgnored() throws Exception {
		mockMvc.perform(post("/api/webhooks/ghl/{token}", TOKEN)
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Evalos-Signature", "sha256=deadbeef")
						.content(wonBody(EVENT_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("accepted"));
	}

	/**
	 * The body is decoded as UTF-8 — JSON's charset by specification — whatever the
	 * sender declares, so the delivery is read and routed rather than failing on the
	 * charset it announced.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "UTF-8", "ISO-8859-1" })
	void aBodyCarryingNonAsciiIsStillAccepted(String charsetName) throws Exception {
		Charset charset = Charset.forName(charsetName);
		byte[] body = wonBody("evt-" + charsetName).replace("Anita Rao", "Zoë Bäcker-Muñoz").getBytes(charset);

		mockMvc.perform(post("/api/webhooks/ghl/{token}", TOKEN)
						.contentType(new MediaType(MediaType.APPLICATION_JSON, charset))
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("accepted"));
	}

	@Test
	void aWonOpportunityIsAcceptedArchivedAndRouted() throws Exception {
		deliver(wonBody(EVENT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.status").value("accepted"));

		ArgumentCaptor<CaseIntakeService.NewCase> command = ArgumentCaptor.forClass(CaseIntakeService.NewCase.class);
		verify(intake).intake(any(Brand.class), command.capture());
		// Both halves reach the service: the contact record GHL writes, and the deal the
		// workflow author put in customData. Nothing is read from the `contact` key.
		assertThat(command.getValue().contact().ghlContactId()).isEqualTo("NbJ72PwZKN26IMzEYntf");
		assertThat(command.getValue().contact().fullName()).isEqualTo("Anita Rao");
		assertThat(command.getValue().contact().email()).isEqualTo("anita@raolaw.example");
		assertThat(command.getValue().serviceType()).isEqualTo(ServiceType.EXPERT_OPINION_LETTER);
		assertThat(command.getValue().ghlOpportunityId()).isEqualTo("opp-4711");
		assertThat(command.getValue().dealValue()).isEqualByComparingTo("1450.00");

		ArgumentCaptor<WebhookEvent> archived = ArgumentCaptor.forClass(WebhookEvent.class);
		verify(webhookEvents, org.mockito.Mockito.atLeastOnce()).save(archived.capture());
		assertThat(archived.getValue().getEventType()).isEqualTo("opportunity.won");
		assertThat(archived.getValue().getExternalId()).isEqualTo(EVENT_ID);
		assertThat(archived.getValue().getBrandId()).isEqualTo(BRAND_IE);
		assertThat(archived.getValue().isProcessed()).isTrue();
		assertThat(archived.getValue().getError()).isNull();
	}

	@Test
	void theBrandComesFromTheEndpointTokenNeverFromTheBody() throws Exception {
		// The body claims another brand. It is not consulted (invariant 8).
		String body = wonBody(EVENT_ID).replace("\"customData\": {",
				"\"brand_id\": \"" + BRAND_XP + "\",\n  \"customData\": {");

		deliver(body).andExpect(status().isOk());

		ArgumentCaptor<Brand> used = ArgumentCaptor.forClass(Brand.class);
		verify(intake).intake(used.capture(), any());
		assertThat(used.getValue().getId()).isEqualTo(BRAND_IE);
	}

	/**
	 * The token is the whole credential now, so this is the test that says it is actually
	 * checked. A token nobody issued gets a 404 and no side effect — not a 401, because
	 * the caller is a machine with no other credential to go and try.
	 */
	@Test
	void anUnknownEndpointTokenIsNotFound() throws Exception {
		deliver("someone-elses-token", wonBody(EVENT_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("UNKNOWN_ENDPOINT"));

		verify(intake, never()).intake(any(), any());
		verify(webhookEvents, never()).save(any());
	}

	/**
	 * Deactivating a brand has to stop its webhook, and the {@code AndActiveTrue} in the
	 * lookup is the only thing enforcing it: a real, correctly spelled token whose brand
	 * is inactive resolves to nothing, and the caller cannot tell that apart from a token
	 * that never existed.
	 */
	@Test
	void anInactiveBrandsTokenIsTheSameNotFound() throws Exception {
		given(brands.findByWebhookEndpointTokenAndActiveTrue("retired-brand-token"))
				.willReturn(Optional.empty());

		deliver("retired-brand-token", wonBody(EVENT_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("UNKNOWN_ENDPOINT"));

		verify(intake, never()).intake(any(), any());
		verify(webhookEvents, never()).save(any());
	}

	@Test
	void aRedeliveryOfTheSameEventCreatesNothingNew() throws Exception {
		WebhookEvent alreadySeen = mock(WebhookEvent.class);
		given(alreadySeen.getId()).willReturn(UUID.randomUUID());
		given(alreadySeen.getBrandId()).willReturn(BRAND_IE);
		// Processed, which is what makes it a duplicate rather than a failed attempt
		// waiting to be retried.
		given(alreadySeen.isProcessed()).willReturn(true);
		given(webhookEvents.findBySourceAndBrandIdAndExternalId(WebhookSource.GHL, BRAND_IE, EVENT_ID))
				.willReturn(Optional.of(alreadySeen));

		deliver(wonBody(EVENT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("duplicate"));

		verify(intake, never()).intake(any(), any());
		verify(webhookEvents, never()).save(any());
	}

	/** {@code webhook_id} is the accepted second name for the same idempotency key. */
	@Test
	void anEventCarryingWebhookIdInsteadOfEventIdIsKeyedOnIt() throws Exception {
		// Nested under customData like everything else the workflow author adds.
		deliver(wonBody(EVENT_ID).replace("\"event_id\"", "\"webhook_id\""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("accepted"));

		ArgumentCaptor<WebhookEvent> archived = ArgumentCaptor.forClass(WebhookEvent.class);
		verify(webhookEvents, org.mockito.Mockito.atLeastOnce()).save(archived.capture());
		assertThat(archived.getValue().getExternalId()).isEqualTo(EVENT_ID);
	}

	/**
	 * Two brands are two GHL sub-accounts numbering their own events, so the same
	 * external id arriving for a different brand is a different event and gets its own
	 * case. The lookup is brand-scoped, matching the unique key, so the other brand's
	 * row is not even a candidate.
	 */
	@Test
	void theSameExternalIdFromAnotherBrandIsItsOwnEvent() throws Exception {
		// Only XpertsPortal has archived this external id. International Evaluations
		// asking about the same id must not find it.
		given(webhookEvents.findBySourceAndBrandIdAndExternalId(WebhookSource.GHL, BRAND_XP, EVENT_ID))
				.willReturn(Optional.of(mock(WebhookEvent.class)));

		deliver(wonBody(EVENT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("accepted"));

		verify(intake).intake(any(Brand.class), any(CaseIntakeService.NewCase.class));
	}

	@Test
	void anUnreadableOrKeylessPayloadIsABadRequest() throws Exception {
		deliver("not json at all")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_PAYLOAD"));

		// No event type anywhere — neither top-level nor in customData — is still a refusal:
		// with nothing to route on there is no handler to call.
		deliver("{\"event_id\":\"evt-1\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MISSING_EVENT_TYPE"));

		deliver("{\"customData\":{\"event_id\":\"evt-1\"}}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MISSING_EVENT_TYPE"));

		verify(intake, never()).intake(any(), any());
	}

	/**
	 * GHL mints no delivery id of its own and sends the workflow's {@code event_id} as
	 * {@code ""} until somebody wires a value into it, so refusing a keyless payload refused
	 * every real delivery. A digest of the body stands in: a retry replays the same bytes and
	 * still dedupes, and a different event still gets its own key.
	 */
	@Test
	void aDeliveryCarryingNoEventIdIsKeyedOnADigestOfItsBody() throws Exception {
		// WON_BODY as GHL sends it: event_id empty, because a contact record has no delivery id.
		String keyless = WON_BODY;

		deliver(keyless)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("accepted"));

		ArgumentCaptor<WebhookEvent> archived = ArgumentCaptor.forClass(WebhookEvent.class);
		verify(webhookEvents, org.mockito.Mockito.atLeastOnce()).save(archived.capture());
		String firstKey = archived.getValue().getExternalId();
		assertThat(firstKey).startsWith("sha256:").hasSize("sha256:".length() + 64);

		// The same bytes again are the same key — which is what makes a retry a duplicate.
		deliver(keyless).andExpect(status().isOk());
		verify(webhookEvents, org.mockito.Mockito.atLeastOnce()).save(archived.capture());
		assertThat(archived.getValue().getExternalId()).isEqualTo(firstKey);

		// A different contact is a different event, not a redelivery of this one.
		deliver(keyless.replace("NbJ72PwZKN26IMzEYntf", "SomeOtherContactId01")).andExpect(status().isOk());
		verify(webhookEvents, org.mockito.Mockito.atLeastOnce()).save(archived.capture());
		assertThat(archived.getValue().getExternalId()).isNotEqualTo(firstKey);
	}

	@Test
	void aPayloadMissingARequiredFieldIsABadRequest() throws Exception {
		// `contact_id` is the client's identity (invariant 7), so it is the one field a
		// delivery cannot be without: a case has to point at somebody.
		String noContactId = wonBody(EVENT_ID).replace("\"contact_id\": \"NbJ72PwZKN26IMzEYntf\",", "");
		deliver(noContactId)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		// The contact's name is what every downstream surface identifies the case by. GHL
		// sends "" rather than omitting, so both spellings of "nameless" are refused — but
		// only once first and last are gone too, since the name is rebuilt from those.
		String nameless = wonBody(EVENT_ID)
				.replace("\"first_name\": \"Anita\",", "")
				.replace("\"last_name\": \"Rao\",", "")
				.replace("\"Anita Rao\"", "\"   \"");
		deliver(nameless)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		verify(intake, never()).intake(any(), any());
	}

	/**
	 * A workflow that lost its customData fields, or never had them. A won opportunity has
	 * already been paid for, so the only unacceptable outcome is losing it: the name is rebuilt
	 * from its parts, the service falls back to the flagship one, and the deal is simply
	 * absent rather than fatal. Every field here is one somebody can delete in the GHL UI
	 * without EvalOS ever hearing about it.
	 */
	@Test
	void whatAWorkflowOmitsIsFilledInRatherThanRefused() throws Exception {
		String bare = WON_BODY
				.replace("\"full_name\": \"Anita Rao\",", "")
				.replace("\"service_type\": \"EXPERT_OPINION_LETTER\",", "")
				.replace("\"opportunity_id\": \"opp-4711\",", "")
				.replace("\"amount\": 1450.00", "\"unused\": 0");

		deliver(bare)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("accepted"));

		ArgumentCaptor<CaseIntakeService.NewCase> command = ArgumentCaptor.forClass(CaseIntakeService.NewCase.class);
		verify(intake).intake(any(Brand.class), command.capture());
		assertThat(command.getValue().serviceType()).isEqualTo(ServiceType.CREDENTIAL_EVALUATION);
		assertThat(command.getValue().contact().fullName()).isEqualTo("Anita Rao");
		// `contact_id` is the client id, carried through untouched.
		assertThat(command.getValue().contact().ghlContactId()).isEqualTo("NbJ72PwZKN26IMzEYntf");
		assertThat(command.getValue().dealValue()).isNull();
		assertThat(command.getValue().ghlOpportunityId()).isNull();
	}

	/**
	 * An amount GHL cannot have meant is a data error in the workflow, not a free case, and
	 * the {@code @Positive} is the only thing between it and a paid case worth nothing.
	 */
	@Test
	void anAmountThatIsNotRealMoneyIsRefused() throws Exception {
		for (String badAmount : new String[] { "0", "-1", "0.00" }) {
			deliver(WON_BODY.replace("\"amount\": 1450.00", "\"amount\": " + badAmount))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}

		verify(intake, never()).intake(any(), any());
	}

	/**
	 * The payload's typed fields are parsed before anything is created, so a value the
	 * workflow cannot have meant is refused as unreadable rather than quietly dropped to null.
	 * For the service that matters twice over: a null would then be silently defaulted, and a
	 * case born of a typo would look deliberate.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"\"service_type\": \"EXPERT_OPINION_LETTER\"|\"service_type\": \"NOT_A_SERVICE\"",
			"\"amount\": 1450.00|\"amount\": \"one thousand\"" })
	void anUnreadableTypedFieldIsAMalformedPayload(String fromPipeTo) throws Exception {
		String[] fromTo = fromPipeTo.split("\\|");

		deliver(WON_BODY.replace(fromTo[0], fromTo[1]))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_PAYLOAD"));

		verify(intake, never()).intake(any(), any());
	}

	@Test
	void aHandlerFailureIsRetriableAndTheArchivedRowSaysWhy() throws Exception {
		willThrow(new IllegalStateException("expert roster unreachable"))
				.given(intake).intake(any(), any());

		// A 5xx is what makes GHL redeliver; a 4xx would silently drop a paid case.
		deliver(wonBody(EVENT_ID)).andExpect(status().is5xxServerError());

		ArgumentCaptor<WebhookEvent> archived = ArgumentCaptor.forClass(WebhookEvent.class);
		verify(webhookEvents, org.mockito.Mockito.atLeastOnce()).save(archived.capture());
		assertThat(archived.getValue().isProcessed()).isFalse();
		assertThat(archived.getValue().getError()).contains("expert roster unreachable");
	}

	/**
	 * The second half of the retry contract, and the half that is easy to lose: after a
	 * failure the archive row exists but is unprocessed, so the dedupe lookup finds it.
	 * If that counts as a duplicate the handler never runs again and a paid case is gone
	 * for good — the redelivery has to retry, and still not create a second case.
	 */
	@Test
	void aRedeliveryAfterAFailureRetriesInsteadOfLookingLikeADuplicate() throws Exception {
		String body = wonBody(EVENT_ID);
		willThrow(new IllegalStateException("transient database blip")).given(intake).intake(any(), any());

		deliver(body).andExpect(status().is5xxServerError());

		// What the archive now looks like to the next delivery: present, but unprocessed.
		ArgumentCaptor<WebhookEvent> failed = ArgumentCaptor.forClass(WebhookEvent.class);
		verify(webhookEvents, org.mockito.Mockito.atLeastOnce()).save(failed.capture());
		WebhookEvent unprocessed = failed.getValue();
		assertThat(unprocessed.isProcessed()).isFalse();
		given(webhookEvents.findBySourceAndBrandIdAndExternalId(WebhookSource.GHL, BRAND_IE, EVENT_ID))
				.willReturn(Optional.of(unprocessed));

		org.mockito.Mockito.reset(intake);
		deliver(body)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("accepted"));

		// The retry ran the handler exactly once more, and reused the same archive row.
		verify(intake).intake(any(Brand.class), any(CaseIntakeService.NewCase.class));
		assertThat(unprocessed.isProcessed()).isTrue();
		assertThat(unprocessed.getError()).isNull();
	}

	@Test
	void aRecognizedButDeferredTypeIsArchivedAndAcked() throws Exception {
		String body = """
				{"event_type": "contact.updated", "event_id": "evt-77", "contact": {"ghl_contact_id": "ghl-c-1"}}""";

		deliver(body)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("accepted"));

		// Recognized, so no retry storm; deferred, so nothing was created.
		verify(intake, never()).intake(any(), any());
		ArgumentCaptor<WebhookEvent> archived = ArgumentCaptor.forClass(WebhookEvent.class);
		verify(webhookEvents, org.mockito.Mockito.atLeastOnce()).save(archived.capture());
		assertThat(archived.getValue().getEventType()).isEqualTo("contact.updated");
		assertThat(archived.getValue().isProcessed()).isTrue();
	}

	/**
	 * {@code contact.created} used to be the live type. Under Case Creation v2.0 a lead is
	 * front-of-house work and EvalOS takes custody only when the money is in, so routing
	 * one to intake would re-open the unpaid window v2.0 closed. It is acked, not failed —
	 * a retry cannot make a deliberate no-op into work.
	 */
	@Test
	void aCreatedContactNoLongerCreatesACase() throws Exception {
		String body = """
				{"event_type": "contact.created", "event_id": "evt-78", "service_type": "EXPERT_OPINION_LETTER",
				 "contact": {"ghl_contact_id": "ghl-c-1", "full_name": "Anita Rao"}}""";

		deliver(body)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("accepted"));

		verify(intake, never()).intake(any(), any());
	}
}
