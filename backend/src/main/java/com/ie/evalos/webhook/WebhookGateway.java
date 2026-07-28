package com.ie.evalos.webhook;

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
 * The one way an external event enters EvalOS: resolve brand → verify → dedupe →
 * archive → route → ack. Nothing before the archive writes anything (invariant 10),
 * and nothing here contains business logic (invariant 12) — it routes to a service.
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
	 * Where the idempotency key is looked for, in order. Invoice ref first because
	 * that is what a GHL payment is identified by; the rest let a new source be
	 * deduplicated without touching this class.
	 */
	private static final String[] EXTERNAL_ID_FIELDS = { "invoice_ref", "payment_id", "event_id", "id" };

	private static final String EVENT_TYPE_FIELD = "event_type";

	/** What the source is told. A duplicate is a success: it already happened. */
	public record Ack(String status, UUID eventId) {

		static Ack accepted(UUID eventId) {
			return new Ack("accepted", eventId);
		}

		static Ack duplicate(UUID eventId) {
			return new Ack("duplicate", eventId);
		}
	}

	private final BrandRepository brands;
	private final WebhookVerifier verifier;
	private final WebhookEventRepository webhookEvents;
	private final WebhookRouter router;
	private final ObjectMapper objectMapper;

	WebhookGateway(BrandRepository brands, WebhookVerifier verifier, WebhookEventRepository webhookEvents,
			WebhookRouter router, ObjectMapper objectMapper) {
		this.brands = brands;
		this.verifier = verifier;
		this.webhookEvents = webhookEvents;
		this.router = router;
		this.objectMapper = objectMapper;
	}

	public Ack accept(WebhookSource source, String endpointToken, String signature, String rawBody) {
		// Brand resolution comes first, though the spec lists verification first: the
		// secret belongs to the brand, so there is nothing to verify against until the
		// token is resolved. The rule it protects — no side effect before verification —
		// still holds, because a lookup is not a side effect.
		Brand brand = brands.findByWebhookEndpointTokenAndActiveTrue(endpointToken)
				.orElseThrow(() -> {
					log.warn("Inbound {} webhook for unknown or inactive endpoint token", source);
					return new WebhookRejected(HttpStatus.NOT_FOUND, "UNKNOWN_ENDPOINT",
							"No such webhook endpoint");
				});

		verifier.verify(brand, signature, rawBody);

		JsonNode body = parse(rawBody);
		String eventType = required(body, EVENT_TYPE_FIELD);
		String externalId = externalId(body);

		// A rejected signature is logged, not archived: an unverified body is not
		// evidence of anything, and archiving it would let anyone who can reach the URL
		// fill the table.
		Optional<WebhookEvent> alreadySeen = webhookEvents.findBySourceAndExternalId(source, externalId);
		if (alreadySeen.isPresent()) {
			WebhookEvent previous = alreadySeen.get();
			// The idempotency key is unique per source, not per brand, and each brand is a
			// separately-numbering GHL sub-account — so two brands can legitimately issue
			// the same invoice number. Treating that as this brand's duplicate would drop a
			// paid case silently and hand this caller another brand's event id, so it is
			// refused loudly instead. The root fix is a per-brand unique key; see the open
			// question in context/progress-tracker.md.
			if (!brand.getId().equals(previous.getBrandId())) {
				log.error("External id {} from {} is already archived against brand {}; brand {} cannot "
						+ "be processed under the same key — a paid case is waiting on a per-brand "
						+ "idempotency key", externalId, source, previous.getBrandId(), brand.getId());
				throw new WebhookRejected(HttpStatus.CONFLICT, "EXTERNAL_ID_BRAND_CONFLICT",
						"This idempotency key is already in use by another brand");
			}
			log.info("Duplicate {} '{}' external id {} for brand {} — no second side effect",
					source, eventType, externalId, brand.getId());
			return Ack.duplicate(previous.getId());
		}

		WebhookEvent archived = webhookEvents.save(
				new WebhookEvent(source, eventType, externalId, brand.getId(), true, rawBody));

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
		return Ack.accepted(archived.getId());
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
