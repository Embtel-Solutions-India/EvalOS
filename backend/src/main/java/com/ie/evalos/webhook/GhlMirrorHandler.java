package com.ie.evalos.webhook;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.service.ContactSnapshotService;
import com.ie.evalos.service.OpportunityMirrorService;

import jakarta.validation.constraints.NotBlank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * GHL changed something; the mirror catches up — Unit 45, slice D.
 *
 * <p><strong>This is the first handler that writes GHL's own data into EvalOS rows</strong>, and it
 * is the direction Units 44–48 were built for: until now every GHL→EvalOS event was Handoff A,
 * which creates a case and touches no mirror at all.
 *
 * <p><strong>The event is a trigger, not a payload, and the distinction is load-bearing for
 * opportunities.</strong> GHL's Custom Webhook action posts the <em>contact record</em> flat — see
 * {@link GhlOpportunityHandler}'s note, written after the nested envelope this codebase first
 * assumed turned out not to exist. There is no stage, no status and no value in it. Anything a
 * workflow author adds under {@code customData} is hand-typed, so a mirror fed from it would be
 * only as correct as the last person to edit that workflow. For an opportunity the event therefore
 * supplies one fact — which contact — and {@link OpportunityMirrorService#absorbForContact} takes
 * the field values from GHL, where they are authoritative.
 *
 * <p><strong>A contact is the one case where the payload IS the entity</strong>, so it costs no
 * read: name, email, phone and company are exactly what GHL posts and exactly what
 * {@code contact_snapshot} holds.
 *
 * <p><strong>It creates no case and closes no opportunity.</strong> A case is born of
 * {@code opportunity.won} alone (invariant 8), and nothing here is on that path — an update that
 * happened to arrive first must not pre-empt it, and a won event must not be absorbed into a
 * lifecycle decision.
 */
@Component
public class GhlMirrorHandler {

	private static final Logger log = LoggerFactory.getLogger(GhlMirrorHandler.class);

	/**
	 * What GHL posts: the contact record, flat and snake_case at the top level.
	 *
	 * <p>{@code contact_id} is the only field that cannot be missing — it is the client's identity
	 * (invariant 7) and, for an opportunity event, the whole of what the event is asked to carry.
	 * Everything else is optional because GHL sends {@code ""} rather than omitting a field it has
	 * no value for, and a contact with only a phone number is a contact.
	 */
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record ContactChanged(
			@NotBlank String contactId,
			String firstName,
			String lastName,
			String fullName,
			String email,
			String phone,
			String companyName) {

		/** Same rebuild as Handoff A's: GHL sends a blank {@code full_name}, not a missing one. */
		public ContactChanged {
			fullName = fullName == null || fullName.isBlank()
					? (orEmpty(firstName) + " " + orEmpty(lastName)).strip()
					: fullName.strip();
		}

		private static String orEmpty(String value) {
			return value == null ? "" : value;
		}
	}

	private final WebhookPayload payloads;
	private final ContactSnapshotService contacts;
	private final OpportunityMirrorService opportunities;
	private final GhlPipelineClient ghl;

	GhlMirrorHandler(WebhookPayload payloads, ContactSnapshotService contacts,
			OpportunityMirrorService opportunities, GhlPipelineClient ghl) {
		this.payloads = payloads;
		this.contacts = contacts;
		this.opportunities = opportunities;
		this.ghl = ghl;
	}

	/**
	 * A contact was created or edited in GHL.
	 *
	 * <p>Create and edit are one handler because {@code findOrCreate} is one operation: a create
	 * EvalOS already saw is an edit, and an edit EvalOS never saw is a create. Splitting them would
	 * make a dropped {@code contact.created} turn the next edit into a refusal.
	 *
	 * <p>The brand is the one the endpoint token resolved to, never a field in the body — the same
	 * rule Handoff A follows, and the reason a stolen payload cannot reach another brand's rows.
	 */
	void contactChanged(Brand brand, String rawBody) {
		ContactChanged payload = payloads.read(rawBody, ContactChanged.class, "contact");
		contacts.findOrCreate(brand.getId(), new ContactSnapshotService.Details(payload.contactId(),
				payload.fullName(), payload.email(), payload.phone(), payload.companyName(),
				null, null, null, null, null));
	}

	/**
	 * An opportunity changed in GHL: re-read the contact's deals and absorb them.
	 *
	 * <p><strong>The contact's deals, not the one deal, and that is the API's doing.</strong>
	 * {@code GET /opportunities/search} takes a {@code contactId} filter — verified, and already
	 * relied on by 45c's retry-after-timeout read — and a contact holds a handful of deals, so one
	 * request answers "what does GHL say about this person's pipeline work" completely. Asking for
	 * a single opportunity would need an id the payload does not carry.
	 *
	 * <p>The read is outside any transaction; {@code absorbForContact} opens its own.
	 */
	void opportunityChanged(Brand brand, String rawBody) {
		ContactChanged payload = payloads.read(rawBody, ContactChanged.class, "opportunity change");
		int written = opportunities.absorbForContact(ghl.forContact(payload.contactId()));
		log.info("Opportunity change for contact {} absorbed {} row(s) into the mirror",
				payload.contactId(), written);
	}
}
