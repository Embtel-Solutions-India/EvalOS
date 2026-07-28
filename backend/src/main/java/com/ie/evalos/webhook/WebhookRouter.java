package com.ie.evalos.webhook;

import java.util.Set;

import com.ie.evalos.domain.Brand;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Which handler an event type belongs to. The vocabulary lives here so the gateway
 * stays about transport and the handlers stay about their own payloads; Unit 15 adds
 * the Dropbox Sign types alongside these.
 *
 * <p>Anything not handled is acknowledged rather than failed. A retry cannot make an
 * unimplemented event type implemented, and a source that keeps redelivering
 * something nobody will ever process is worse than a logged no-op.
 */
@Component
public class WebhookRouter {

	private static final Logger log = LoggerFactory.getLogger(WebhookRouter.class);

	static final String PAYMENT_CONFIRMED = "payment.confirmed";

	/** Recognized in the design, payloads not yet confirmed — deliberate no-ops. */
	private static final Set<String> DEFERRED = Set.of("refund.requested", "contact.updated");

	private final GhlPaymentHandler ghlPayments;

	WebhookRouter(GhlPaymentHandler ghlPayments) {
		this.ghlPayments = ghlPayments;
	}

	void route(Brand brand, String eventType, String rawBody) {
		if (PAYMENT_CONFIRMED.equals(eventType)) {
			ghlPayments.handle(brand, rawBody);
		}
		else if (DEFERRED.contains(eventType)) {
			log.info("Event type '{}' is recognized but not yet implemented — archived and acked", eventType);
		}
		else {
			log.warn("Unknown event type '{}' — archived and acked, nothing routed", eventType);
		}
	}
}
