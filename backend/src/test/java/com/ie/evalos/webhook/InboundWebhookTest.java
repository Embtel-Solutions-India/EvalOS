package com.ie.evalos.webhook;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Brand;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
 * The gateway's acceptance criteria over the real filter chain and the real
 * pipeline: only a correctly signed delivery to a known endpoint does anything, a
 * redelivery does nothing twice, and a failure downstream is retriable. What the
 * accepted delivery then creates is asserted in {@code CaseIntakeServiceTest}.
 *
 * <p>No request here sends a bearer token, which is the point: the endpoint is public
 * and gated entirely by the path token and the HMAC.
 */
@WebMvcTest(controllers = InboundWebhookController.class)
@Import({ WebhookGateway.class, WebhookVerifier.class, WebhookRouter.class, GhlPaymentHandler.class,
		SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = {
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.webhook.signature-header=X-Evalos-Signature" })
class InboundWebhookTest {

	private static final String TOKEN = "local-ie-webhook-token";
	private static final String SECRET = "local-ie-webhook-secret";
	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID BRAND_XP = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private static final String INVOICE = "INV-99123";

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
	void oneBrandWithASecret() {
		Brand brand = mock(Brand.class);
		given(brand.getId()).willReturn(BRAND_IE);
		given(brand.getName()).willReturn("International Evaluations");
		given(brand.getSlug()).willReturn("international-evaluations");
		given(brand.getGhlWebhookSecret()).willReturn(SECRET);

		given(brands.findByWebhookEndpointTokenAndActiveTrue(TOKEN)).willReturn(Optional.of(brand));
		given(webhookEvents.save(any(WebhookEvent.class))).willAnswer(call -> call.getArgument(0));
	}

	/** A realistic payment.confirmed body, snake_case as GHL sends it. */
	private static String paymentBody(String invoiceRef) {
		return """
				{
				  "event_type": "payment.confirmed",
				  "invoice_ref": "%s",
				  "quote_amount": 1450.00,
				  "service_type": "EXPERT_OPINION_LETTER",
				  "visa_category": "EB2_NIW",
				  "drive_link": "https://drive.google.com/folder/abc",
				  "campaign_attribution": "eb2-niw-q3",
				  "contact": {
				    "ghl_contact_id": "ghl-c-1",
				    "full_name": "Anita Rao",
				    "email": "anita@raolaw.example",
				    "client_type": "ATTORNEY",
				    "source": "GOOGLE_ADS"
				  }
				}""".formatted(invoiceRef);
	}

	private static String sign(String body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private org.springframework.test.web.servlet.ResultActions deliver(String token, String body, String signature)
			throws Exception {
		var request = post("/api/webhooks/ghl/{token}", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
		if (signature != null) {
			request = request.header("X-Evalos-Signature", signature);
		}
		return mockMvc.perform(request);
	}

	private org.springframework.test.web.servlet.ResultActions deliverSigned(String body) throws Exception {
		return deliver(TOKEN, body, sign(body));
	}

	@Test
	void aSignedPaymentIsAcceptedArchivedAndRouted() throws Exception {
		String body = paymentBody(INVOICE);

		deliverSigned(body)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.status").value("accepted"));

		verify(intake).intake(any(Brand.class), any(CaseIntakeService.NewCase.class));

		ArgumentCaptor<WebhookEvent> archived = ArgumentCaptor.forClass(WebhookEvent.class);
		verify(webhookEvents, org.mockito.Mockito.atLeastOnce()).save(archived.capture());
		assertThat(archived.getValue().getEventType()).isEqualTo("payment.confirmed");
		assertThat(archived.getValue().getExternalId()).isEqualTo(INVOICE);
		assertThat(archived.getValue().getBrandId()).isEqualTo(BRAND_IE);
		assertThat(archived.getValue().isProcessed()).isTrue();
		assertThat(archived.getValue().getError()).isNull();
	}

	@Test
	void theBrandComesFromTheEndpointTokenNeverFromTheBody() throws Exception {
		// The body claims another brand. It is not consulted (invariant 8).
		String body = paymentBody(INVOICE).replace("\"contact\": {",
				"\"brand_id\": \"" + BRAND_XP + "\",\n  \"contact\": {");

		deliverSigned(body).andExpect(status().isOk());

		ArgumentCaptor<Brand> used = ArgumentCaptor.forClass(Brand.class);
		verify(intake).intake(used.capture(), any());
		assertThat(used.getValue().getId()).isEqualTo(BRAND_IE);
	}

	@Test
	void aWrongSignatureIsRejectedWithNoSideEffect() throws Exception {
		deliver(TOKEN, paymentBody(INVOICE), sign("a different body"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("SIGNATURE_INVALID"));

		verify(intake, never()).intake(any(), any());
		verify(webhookEvents, never()).save(any());
	}

	@Test
	void aMissingOrMalformedSignatureIsRejectedTheSameWay() throws Exception {
		deliver(TOKEN, paymentBody(INVOICE), null)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("SIGNATURE_INVALID"));

		deliver(TOKEN, paymentBody(INVOICE), "not-hex-at-all")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("SIGNATURE_INVALID"));

		verify(intake, never()).intake(any(), any());
		verify(webhookEvents, never()).save(any());
	}

	@Test
	void aBrandWithNoSecretVerifiesNothing() throws Exception {
		Brand unconfigured = mock(Brand.class);
		given(unconfigured.getGhlWebhookSecret()).willReturn(null);
		given(brands.findByWebhookEndpointTokenAndActiveTrue("no-secret-yet"))
				.willReturn(Optional.of(unconfigured));

		deliver("no-secret-yet", paymentBody(INVOICE), sign(paymentBody(INVOICE)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("SIGNATURE_INVALID"));

		verify(webhookEvents, never()).save(any());
	}

	@Test
	void anUnknownEndpointTokenIsNotFound() throws Exception {
		String body = paymentBody(INVOICE);

		deliver("someone-elses-token", body, sign(body))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("UNKNOWN_ENDPOINT"));

		verify(intake, never()).intake(any(), any());
		verify(webhookEvents, never()).save(any());
	}

	@Test
	void aRedeliveryOfTheSameInvoiceCreatesNothingNew() throws Exception {
		WebhookEvent alreadySeen = mock(WebhookEvent.class);
		given(alreadySeen.getId()).willReturn(UUID.randomUUID());
		given(alreadySeen.getBrandId()).willReturn(BRAND_IE);
		// Processed, which is what makes it a duplicate rather than a failed attempt
		// waiting to be retried.
		given(alreadySeen.isProcessed()).willReturn(true);
		given(webhookEvents.findBySourceAndExternalId(WebhookSource.GHL, INVOICE))
				.willReturn(Optional.of(alreadySeen));

		deliverSigned(paymentBody(INVOICE))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("duplicate"));

		verify(intake, never()).intake(any(), any());
		verify(webhookEvents, never()).save(any());
	}

	/**
	 * Two brands are two GHL sub-accounts numbering their own invoices, so the same
	 * invoice ref can arrive twice legitimately. The unique key is per source, not per
	 * brand, so this cannot be processed — but it must not look like a duplicate
	 * either, or a paid case would vanish and this brand would be handed another
	 * brand's event id.
	 */
	@Test
	void anotherBrandsIdempotencyKeyIsAConflictNotADuplicate() throws Exception {
		WebhookEvent otherBrands = mock(WebhookEvent.class);
		given(otherBrands.getId()).willReturn(UUID.randomUUID());
		given(otherBrands.getBrandId()).willReturn(BRAND_XP);
		given(webhookEvents.findBySourceAndExternalId(WebhookSource.GHL, INVOICE))
				.willReturn(Optional.of(otherBrands));

		deliverSigned(paymentBody(INVOICE))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("EXTERNAL_ID_BRAND_CONFLICT"));

		verify(intake, never()).intake(any(), any());
		verify(webhookEvents, never()).save(any());
	}

	@Test
	void anUnreadableOrKeylessPayloadIsABadRequest() throws Exception {
		deliver(TOKEN, "not json at all", sign("not json at all"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_PAYLOAD"));

		String noKey = "{\"event_type\":\"payment.confirmed\"}";
		deliver(TOKEN, noKey, sign(noKey))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MISSING_EXTERNAL_ID"));

		String noType = "{\"invoice_ref\":\"INV-1\"}";
		deliver(TOKEN, noType, sign(noType))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MISSING_EVENT_TYPE"));

		verify(intake, never()).intake(any(), any());
	}

	@Test
	void aPayloadMissingARequiredFieldIsABadRequest() throws Exception {
		String noServiceType = paymentBody(INVOICE).replace("\"service_type\": \"EXPERT_OPINION_LETTER\",", "");

		deliverSigned(noServiceType)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		verify(intake, never()).intake(any(), any());
	}

	@Test
	void aHandlerFailureIsRetriableAndTheArchivedRowSaysWhy() throws Exception {
		willThrow(new IllegalStateException("expert roster unreachable"))
				.given(intake).intake(any(), any());

		// A 5xx is what makes GHL redeliver; a 4xx would silently drop a paid case.
		deliverSigned(paymentBody(INVOICE)).andExpect(status().is5xxServerError());

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
		String body = paymentBody(INVOICE);
		willThrow(new IllegalStateException("transient database blip")).given(intake).intake(any(), any());

		deliverSigned(body).andExpect(status().is5xxServerError());

		// What the archive now looks like to the next delivery: present, but unprocessed.
		ArgumentCaptor<WebhookEvent> failed = ArgumentCaptor.forClass(WebhookEvent.class);
		verify(webhookEvents, org.mockito.Mockito.atLeastOnce()).save(failed.capture());
		WebhookEvent unprocessed = failed.getValue();
		assertThat(unprocessed.isProcessed()).isFalse();
		given(webhookEvents.findBySourceAndExternalId(WebhookSource.GHL, INVOICE))
				.willReturn(Optional.of(unprocessed));

		org.mockito.Mockito.reset(intake);
		deliverSigned(body)
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

		deliverSigned(body)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("accepted"));

		// Recognized, so no retry storm; deferred, so nothing was created.
		verify(intake, never()).intake(any(), any());
		ArgumentCaptor<WebhookEvent> archived = ArgumentCaptor.forClass(WebhookEvent.class);
		verify(webhookEvents, org.mockito.Mockito.atLeastOnce()).save(archived.capture());
		assertThat(archived.getValue().getEventType()).isEqualTo("contact.updated");
		assertThat(archived.getValue().isProcessed()).isTrue();
	}
}
