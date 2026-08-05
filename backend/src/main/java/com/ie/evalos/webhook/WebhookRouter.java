package com.ie.evalos.webhook;

import java.util.Set;

import com.ie.evalos.domain.Brand;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Which handler an event type belongs to. The vocabulary lives here so the gateway
 * stays about transport and the handlers stay about their own payloads. One source, GHL:
 * the second inbound source this was built to accommodate was a signature provider, and
 * that provider was dropped.
 *
 * <p>Anything not handled is acknowledged rather than failed. A retry cannot make an
 * unimplemented event type implemented, and a source that keeps redelivering
 * something nobody will ever process is worse than a logged no-op.
 */
@Component
public class WebhookRouter {

	private static final Logger log = LoggerFactory.getLogger(WebhookRouter.class);

	static final String OPPORTUNITY_WON = "opportunity.won";

	/**
	 * Recognized in the design, and deliberately no-ops.
	 *
	 * <p><strong>{@code contact.created} is one of them, as of Case Creation v2.0.</strong>
	 * It used to be the live type, and a lead is now front-of-house work: EvalOS takes
	 * custody when the opportunity is Won, because that is the point the money is in.
	 * Routing a contact to intake would re-open the unpaid window v2.0 closed.
	 *
	 * <p>{@code contact.updated} is here for the neighbouring reason. Intake is
	 * create-or-update, so it would technically work — but an edit to a contact is not a
	 * reason to open a case, and routing it there would turn every field change in GHL
	 * into new work for a brand that never asked for it.
	 */
	private static final Set<String> DEFERRED = Set.of("refund.requested", "contact.created", "contact.updated");

	private final GhlOpportunityHandler ghlOpportunities;

	WebhookRouter(GhlOpportunityHandler ghlOpportunities) {
		this.ghlOpportunities = ghlOpportunities;
	}

	void route(Brand brand, String eventType, String rawBody) {
		if (OPPORTUNITY_WON.equals(eventType)) {
			ghlOpportunities.handle(brand, rawBody);
		}
		else if (DEFERRED.contains(eventType)) {
			log.info("Event type '{}' is recognized but not yet implemented — archived and acked", eventType);
		}
		else {
			log.warn("Unknown event type '{}' — archived and acked, nothing routed", eventType);
		}
	}
}
