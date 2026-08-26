package com.ie.evalos.webhook;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.WebhookEvent;
import com.ie.evalos.domain.WebhookSource;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.WebhookEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The one way an external event enters EvalOS: resolve brand → dedupe → archive →
 * route → ack. Nothing before the archive writes anything (invariant 10), and nothing
 * here contains business logic (invariant 12) — it routes to a service.
 *
 * <p>The endpoint token is the whole credential. There is no signature step: GHL's
 * Custom Webhook action cannot compute one, so requiring it meant Handoff A could not
 * be configured from GHL at all. What still stands between the URL and a case is the
 * unguessable per-brand token, the brand having to be active, and the payload contract
 * below — an unusable body is a 400 that never reaches a handler.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> Each step commits on its own so
 * the archive row outlives a failed handler: that is what lets the row record the
 * error and lets a redelivery find the case half-created — which it never is,
 * because the handler's own transaction rolled back.
 */
@Component
public class WebhookGateway {

	private static final Logger log = LoggerFactory.getLogger(WebhookGateway.class);

	/**
	 * Where the idempotency key is looked for, in order. The source's own event id
	 * first: a contact has no invoice, and keying on the contact id instead would make
	 * a returning client's second order look like a duplicate.
	 *
	 * <p>Both names are delivery-scoped on purpose. A bare {@code "id"} was tried and
	 * removed twice: in most webhook envelopes it is the *resource's* id, so a returning
	 * client's second order would carry the id of the first and be swallowed as a
	 * duplicate — exactly the failure moving off {@code invoice_ref} was meant to avoid.
	 * If GHL turns out to send only a resource id, the answer is a delivery-id header,
	 * not this list.
	 *
	 * <p>A payload carrying none of these is rejected rather than processed — see
	 * {@link #externalId}.
	 */
	private static final String[] EXTERNAL_ID_FIELDS = { "event_id", "webhook_id" };

	private static final String EVENT_TYPE_FIELD = "event_type";

	/** What the source is told. A duplicate is a success: it already happened. */
	public record Ack(String status, UUID eventId) {
	}

	private final BrandRepository brands;
	private final WebhookEventRepository webhookEvents;
	private final WebhookRouter router;
	private final ObjectMapper objectMapper;

	WebhookGateway(BrandRepository brands, WebhookEventRepository webhookEvents,
			WebhookRouter router, ObjectMapper objectMapper) {
		this.brands = brands;
		this.webhookEvents = webhookEvents;
		this.router = router;
		this.objectMapper = objectMapper;
	}

	public Ack accept(WebhookSource source, String endpointToken, byte[] rawBytes) {
		// Brand resolution is the authentication step: an unknown or inactive token has
		// no brand, so there is nothing to take custody of the work. A lookup is not a
		// side effect, so the rule that nothing is written before the caller is
		// established still holds.
		Brand brand = brands.findByWebhookEndpointTokenAndActiveTrue(endpointToken)
				.orElseThrow(() -> {
					log.warn("Inbound {} webhook for unknown or inactive endpoint token", source);
					return new WebhookRejected(HttpStatus.NOT_FOUND, "UNKNOWN_ENDPOINT",
							"No such webhook endpoint");
				});

		// JSON is UTF-8 by specification (RFC 8259), so this is the right charset to name
		// explicitly rather than leaving the servlet layer to pick one from the request.
		String rawBody = new String(rawBytes, StandardCharsets.UTF_8);

		JsonNode body = parse(rawBody);
		String eventType = required(body, EVENT_TYPE_FIELD);
		String externalId = externalId(body);

		// Scoped to the brand, matching the unique key: two brands numbering their own
		// invoices may send the same external id, and each is its own event.
		Optional<WebhookEvent> alreadySeen = webhookEvents
				.findBySourceAndBrandIdAndExternalId(source, brand.getId(), externalId);

		if (alreadySeen.map(WebhookEvent::isProcessed).orElse(false)) {
			log.info("Duplicate {} '{}' external id {} for brand {} — no second side effect",
					source, eventType, externalId, brand.getId());
			return new Ack("duplicate", alreadySeen.get().getId());
		}

		// Archived but never processed means the last attempt failed and this delivery is
		// the retry it asked for. Reusing that row is what keeps the retry from creating a
		// second case: "already seen" is not the same as "already done".
		WebhookEvent archived = alreadySeen.orElseGet(() -> webhookEvents.save(
				new WebhookEvent(source, eventType, externalId, brand.getId(), rawBody)));
		if (alreadySeen.isPresent()) {
			log.info("Retrying {} '{}' external id {} for brand {} after an earlier failure",
					source, eventType, externalId, brand.getId());
		}

		try {
			router.route(brand, eventType, rawBody);
			archived.markProcessed();
		}
		catch (RuntimeException ex) {
			// The row records why, then the failure propagates as a retriable 5xx so the
			// source redelivers. The handler's own transaction has already rolled back.
			archived.recordError(ex.toString());
			webhookEvents.save(archived);
			log.error("Handler for {} '{}' external id {} failed", source, eventType, externalId, ex);
			throw ex;
		}
		webhookEvents.save(archived);
		return new Ack("accepted", archived.getId());
	}

	private JsonNode parse(String rawBody) {
		try {
			return objectMapper.readTree(rawBody);
		}
		catch (JsonProcessingException ex) {
			throw new WebhookRejected(HttpStatus.BAD_REQUEST, "MALFORMED_PAYLOAD", "Payload is not valid JSON");
		}
	}

	private static String required(JsonNode body, String field) {
		String value = text(body, field);
		if (value == null) {
			throw new WebhookRejected(HttpStatus.BAD_REQUEST, "MISSING_EVENT_TYPE",
					"Payload has no '" + field + "'");
		}
		return value;
	}

	/**
	 * Without an idempotency key a redelivery would create a second case, so a
	 * payload that carries none is refused rather than processed once and hoped about.
	 */
	private static String externalId(JsonNode body) {
		for (String field : EXTERNAL_ID_FIELDS) {
			String value = text(body, field);
			if (value != null) {
				return value;
			}
		}
		throw new WebhookRejected(HttpStatus.BAD_REQUEST, "MISSING_EXTERNAL_ID",
				"Payload carries no idempotency key");
	}

	private static String text(JsonNode body, String field) {
		JsonNode value = body.get(field);
		if (value == null || value.isNull() || value.asText().isBlank()) {
			return null;
		}
		return value.asText();
	}
}
