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
	 * The contact mirror's events — Unit 45d.
	 *
	 * <p><strong>These were deferred, and what changed is where they now go.</strong> They used to
	 * be no-ops with a note explaining that a contact is not a reason to open a <em>case</em> —
	 * still true, and Case Creation v2.0's ruling stands untouched. What did not exist when that
	 * note was written is a mirror for them to land in. They update {@code contact_snapshot} and
	 * reach {@code CaseIntakeService} at no point.
	 */
	private static final Set<String> CONTACT_CHANGED = Set.of("contact.created", "contact.updated");

	/**
	 * The opportunity mirror's events — Unit 45d.
	 *
	 * <p><strong>Both tenses of both verbs, deliberately.</strong> {@code event_type} is typed by
	 * hand into a GHL workflow's Custom Webhook action, so the difference between
	 * {@code opportunity.update} and {@code opportunity.updated} is a typo away from a silently
	 * unmirrored deal — and the handler re-reads GHL either way, so accepting both costs nothing.
	 * Create and update are one handler for the same reason: the read does not care which it was.
	 *
	 * <p><strong>{@code opportunity.won} is not in this set and must not join it.</strong> That one
	 * is Handoff A — it creates a case (invariant 8) — and an event that both creates custody and
	 * writes the mirror would make two very different failures look like one.
	 */
	private static final Set<String> OPPORTUNITY_CHANGED = Set.of("opportunity.create",
			"opportunity.created", "opportunity.update", "opportunity.updated",
			"opportunity.stage_changed", "opportunity.status_changed");

	/** Recognized in the design, and deliberately a no-op: nothing in EvalOS models a refund yet. */
	private static final Set<String> DEFERRED = Set.of("refund.requested");

	private final GhlOpportunityHandler ghlOpportunities;
	private final GhlMirrorHandler mirror;

	WebhookRouter(GhlOpportunityHandler ghlOpportunities, GhlMirrorHandler mirror) {
		this.ghlOpportunities = ghlOpportunities;
		this.mirror = mirror;
	}

	void route(Brand brand, String eventType, String rawBody) {
		if (OPPORTUNITY_WON.equals(eventType)) {
			ghlOpportunities.handle(brand, rawBody);
		}
		else if (CONTACT_CHANGED.contains(eventType)) {
			mirror.contactChanged(brand, rawBody);
		}
		else if (OPPORTUNITY_CHANGED.contains(eventType)) {
			mirror.opportunityChanged(brand, rawBody);
		}
		else if (DEFERRED.contains(eventType)) {
			log.info("Event type '{}' is recognized but not yet implemented — archived and acked", eventType);
		}
		else {
			log.warn("Unknown event type '{}' — archived and acked, nothing routed", eventType);
		}
	}
}
