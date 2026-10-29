package com.ie.evalos.webhook;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.integration.GhlHttp;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.service.CaseIntakeService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Handoff A's handler: GHL says an opportunity was marked Won, EvalOS takes custody of
 * the work it represents.
 *
 * <p>This has been a payment handler and a contact handler before, and the reason it
 * settled here is that <strong>whoever owns the money decides when a case exists.</strong>
 * GHL captures the lead, opens the opportunity, invoices and collects — so by the time a
 * salesperson drags the opportunity to Won, the money is in. The webhook therefore carries
 * both facts at once: this is real work, and it has been paid for. Nothing is left for a
 * human to record, which is why there is no manual payment path any more.
 *
 * <p>Parse-then-trust. The payload is deserialized and validated in full before the
 * intake service is called, so a malformed delivery is a 400 that GHL will not retry
 * rather than a half-created case.
 *
 * <p><strong>The payload shape is the observed one, no longer an assumption.</strong> It was
 * designed as a nested {@code opportunity} / {@code contact} envelope and GHL's Custom Webhook
 * action sends nothing of the sort. What GHL itself posts is the <em>contact record</em> — its
 * Contact lookup tokens, flat at the top level. There is no opportunity block, no amount and no
 * delivery id in it, because none of that is part of a contact.
 *
 * <p>So the deal arrives the only way it can: the workflow author puts it in
 * {@code customData}, which is the one part of the body GHL does not write. Everything not in
 * either half is a human's to fill in afterwards. The mapping stays in this class so a further
 * correction is one file and never reaches the service.
 */
@Component
public class GhlOpportunityHandler {

	/**
	 * What a delivery that names no service is taken to be.
	 *
	 * <p>ponytail: GHL sends no service of its own, and a won opportunity has already been
	 * paid for — so refusing the delivery would lose a paid case over a field GHL was never
	 * asked to send. Credential evaluation is the flagship service and its checklist is the
	 * identity-and-credential set every service starts from, so a PM correcting the odd case
	 * is cheaper than a dropped one. Reached only when neither the workflow's {@code service_type} nor the
	 * won deal's Service Requested names a mapped service (2026-10-30).
	 */
	private static final ServiceType DEFAULT_SERVICE = ServiceType.CREDENTIAL_EVALUATION;

	/** Stands in for a workflow that added no fields at all, so the mapper needs no null checks. */
	private static final OpportunityWon.CustomData NO_CUSTOM_DATA =
			new OpportunityWon.CustomData(null, null, null);

	/**
	 * GHL's Custom Webhook body: the contact record, plus whatever the workflow author added
	 * under {@code customData}. {@code contact_id}, {@code full_name}, {@code email},
	 * {@code phone} — flat at the top level.
	 *
	 * <p>Note {@code contact} is a key in this payload and is <em>not</em> the contact: GHL puts
	 * attribution data there. Nothing is read from it. The rest of GHL's envelope —
	 * {@code location}, {@code workflow}, {@code tags}, {@code contact_type},
	 * {@code date_created}, {@code triggerData} — is ignored, as Boot defaults to.
	 *
	 * <p>{@code contact_id} is the client's identity in EvalOS (invariant 7) and is the one field
	 * that cannot be missing.
	 */
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record OpportunityWon(
			/** The GHL contact id, which is the client id: one contact is one client. */
			@NotBlank String contactId,
			String firstName,
			String lastName,
			/** Filled from first + last when GHL sends it blank; see the constructor. */
			@NotBlank String fullName,
			String email,
			String phone,
			String companyName,
			/** camelCase in the payload, unlike everything around it — hence the explicit name. */
			@JsonProperty("customData") @Valid CustomData customData) {

		/**
		 * GHL sends {@code full_name} pre-computed, but sends {@code ""} rather than omitting a
		 * field it has no value for — so a contact captured with only a first name can arrive
		 * nameless. Rebuilding it here means {@code @NotBlank} refuses only a delivery that
		 * genuinely names nobody, rather than one GHL merely formatted differently.
		 */
		public OpportunityWon {
			fullName = fullName == null || fullName.isBlank()
					? (orEmpty(firstName) + " " + orEmpty(lastName)).strip()
					: fullName.strip();
		}

		private static String orEmpty(String value) {
			return value == null ? "" : value;
		}

		/**
		 * The workflow author's own key/value pairs — the only part of the body GHL does not
		 * write, and the only place a <em>deal</em> can come from, since the contact record has
		 * none. {@code event_type} lives here too and is the gateway's, not this record's.
		 *
		 * <p><strong>Three fields, each here because something breaks without it</strong>, and
		 * nothing here on speculation. The rest of what a case wants — visa category, subtype,
		 * deadline, invoice ref, expert, intake note — is still a PM's to fill in; add a field
		 * here when the workflow starts sending it, not before.
		 *
		 * <p>All three stay optional. A won opportunity has already been paid for, so a workflow
		 * that loses a field must still hand the case over rather than have it refused.
		 */
		@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
		public record CustomData(
				/**
				 * Decides the checklist, and it is half the key of {@code V15}'s
				 * one-open-case-per-contact-per-service index — so on a constant service a client
				 * can only ever hold one open case, and a second purchase refreshes the first
				 * instead of opening its own.
				 */
				ServiceType serviceType,
				/** Carried onto the case so Unit 18 can close the opportunity back in GHL.
				 * Never an idempotency key — that is {@code event_id}'s job. */
				String opportunityId,
				/**
				 * What was collected, not a quote — a won opportunity was invoiced and paid
				 * before it was won. {@code @Positive} wherever present: a zero or a negative is
				 * a data error in GHL, not a free case. Absent is accepted, and leaves
				 * {@code deal_value} null rather than losing the delivery.
				 */
				@Positive BigDecimal amount) {
		}
	}

	private static final Logger log = LoggerFactory.getLogger(GhlOpportunityHandler.class);

	private final CaseIntakeService intake;
	private final WebhookPayload payloads;
	private final GhlPipelineClient ghl;
	private final GhlHttp ghlHttp;
	private final SellingBrand sellingBrand;
	private final com.ie.evalos.service.CaseServiceTypeService serviceTypes;
	private final com.ie.evalos.integration.GhlContactClient ghlContacts;
	private final com.ie.evalos.repository.BrandRepository brands;

	GhlOpportunityHandler(CaseIntakeService intake, WebhookPayload payloads, GhlPipelineClient ghl, GhlHttp ghlHttp,
			SellingBrand sellingBrand, com.ie.evalos.service.CaseServiceTypeService serviceTypes,
			com.ie.evalos.integration.GhlContactClient ghlContacts, com.ie.evalos.repository.BrandRepository brands) {
		this.ghlContacts = ghlContacts;
		this.brands = brands;
		this.intake = intake;
		this.payloads = payloads;
		this.ghl = ghl;
		this.ghlHttp = ghlHttp;
		this.sellingBrand = sellingBrand;
		this.serviceTypes = serviceTypes;
	}

	void handle(Brand brand, String rawBody) {
		OpportunityWon payload = payloads.read(rawBody, OpportunityWon.class, "opportunity");
		Optional<GhlPipelineClient.Opportunity> deal = confirmWonInGhl(brand, payload);
		intake.intake(brand, toCommand(payload, serviceOf(brand, payload, deal)));
	}

	/**
	 * The PM's <strong>New case</strong> (2026-10-30, D9 edited): the webhook's own intake, started from a GHL
	 * opportunity id or contact id instead of a delivery — for a win whose webhook never came. Nothing is
	 * typed in: the deal must be <em>won in GHL</em> (the same proof the webhook needs), and the contact, the
	 * amount and the service are read from GHL. Intake is create-or-refresh, so a case already open for that
	 * contact and service is returned, not duplicated. Still the only door into custody (invariant 8).
	 */
	public com.ie.evalos.domain.Case openFromGhl(String opportunityId, String contactId) {
		boolean byDeal = opportunityId != null && !opportunityId.isBlank();
		if (!byDeal && (contactId == null || contactId.isBlank())) {
			throw new com.ie.evalos.common.InvalidRequestException("Give the GHL opportunity ID or the GHL contact ID");
		}
		if (!ghlHttp.isConfigured() || !sellingBrand.isConfigured()) {
			throw new com.ie.evalos.common.InvalidRequestException(
					"GHL is not connected, so a case cannot be opened from it. An administrator sets it up in Settings.");
		}
		// GHL is one location, owned by the selling brand (D19d): its deals open that brand's cases only.
		com.ie.evalos.security.TenantContext caller = com.ie.evalos.security.TenantContext.current();
		if (!caller.isCrossBrand() && !sellingBrand.id().equals(caller.brandId())) {
			throw new com.ie.evalos.common.ForbiddenException("Cases from GHL open in the brand that owns the GHL location");
		}
		Brand brand = brands.findById(sellingBrand.id()).orElseThrow();

		GhlPipelineClient.Opportunity deal = byDeal ? wonDeal(opportunityId.trim()) : wonDealOf(contactId.trim());
		com.ie.evalos.integration.GhlContactClient.Contact contact;
		try {
			contact = ghlContacts.byId(deal.contactId());
		}
		catch (com.ie.evalos.integration.GhlUnavailableException missing) {
			throw refused(missing, "GHL has no contact " + deal.contactId() + " for that opportunity");
		}
		BigDecimal amount = deal.monetaryValue() != null && deal.monetaryValue().signum() > 0 ? deal.monetaryValue() : null;
		return intake.intake(brand, new CaseIntakeService.NewCase(
				new CaseIntakeService.ContactDetails(contact.id(), contact.name(), contact.email(), contact.phone(),
						contact.company(), null, null, null, null, null),
				serviceOf(brand, null, Optional.of(deal)),
				null, null, null, deal.id(), amount, null, null, null, null));
	}

	private GhlPipelineClient.Opportunity wonDeal(String opportunityId) {
		GhlPipelineClient.Opportunity deal;
		try {
			deal = ghl.byId(opportunityId);
		}
		catch (com.ie.evalos.integration.GhlUnavailableException missing) {
			throw refused(missing, "GHL has no opportunity " + opportunityId);
		}
		if (deal == null) {
			throw new com.ie.evalos.common.InvalidRequestException("GHL has no opportunity " + opportunityId);
		}
		if (!"won".equalsIgnoreCase(deal.status())) {
			throw new com.ie.evalos.common.InvalidRequestException("That opportunity is " + deal.status()
					+ " in GHL, not won. A case opens from a won deal: mark it won in GHL (that opens the case too).");
		}
		return deal;
	}

	/** The contact's most recently won deal. */
	private GhlPipelineClient.Opportunity wonDealOf(String contactId) {
		List<GhlPipelineClient.Opportunity> deals;
		try {
			deals = ghl.forContact(contactId);
		}
		catch (com.ie.evalos.integration.GhlUnavailableException missing) {
			throw refused(missing, "GHL has no contact " + contactId);
		}
		return deals.stream()
				.filter((deal) -> "won".equalsIgnoreCase(deal.status()))
				.max(java.util.Comparator.comparing(GhlPipelineClient.Opportunity::lastStatusChangeAt,
						java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
				.orElseThrow(() -> new com.ie.evalos.common.InvalidRequestException(
						"That contact has no won opportunity in GHL. A case opens from a won deal."));
	}

	/**
	 * GHL's "no such id" (400, 404, 422) is a wrong id, said plainly. Everything else — a missing scope (401/403),
	 * the rate limit (429), an outage — stays the 502 naming the real cause.
	 */
	private static RuntimeException refused(com.ie.evalos.integration.GhlUnavailableException failure, String message) {
		Integer status = failure.status();
		return status != null && (status == 400 || status == 404 || status == 422)
				? new com.ie.evalos.common.InvalidRequestException(message)
				: failure;
	}

	/**
	 * The workflow's {@code service_type} if it sent one; else the won deal's own <em>Service Requested</em>
	 * (2026-10-30: GHL's workflow cannot carry that dropdown, so every case was Credential evaluation);
	 * else {@link #DEFAULT_SERVICE}, which CaseServiceTypeListener (on creation) and the 5-minute SERVICE_TYPE_RECONCILE sweep still correct.
	 */
	private ServiceType serviceOf(Brand brand, OpportunityWon payload, Optional<GhlPipelineClient.Opportunity> deal) {
		if (payload != null && payload.customData() != null && payload.customData().serviceType() != null) {
			return payload.customData().serviceType();
		}
		return deal.flatMap((won) -> serviceTypes.requestedOn(brand.getId(),
				GhlPipelineClient.CustomFieldValue.toMap(won.customFields())))
				.orElse(DEFAULT_SERVICE);
	}

	/**
	 * The endpoint token is the whole credential for this webhook (GHL's Custom Webhook cannot sign),
	 * and a case, a portal account and an email all follow from a win — so a delivery is a
	 * <em>trigger</em> here as it is for every other event (D39): GHL is asked whether this contact
	 * really has a won deal before anything is created. A leaked token can then no longer mint
	 * cases out of thin air.
	 *
	 * <p><strong>Fails closed where it can check.</strong> A GHL outage surfaces as a 5xx, which the
	 * gateway archives unprocessed and GHL redelivers; a contact with no matching won deal is
	 * refused 409, also retried by GHL, so a status that was a moment behind the workflow still
	 * lands. If the payload names an opportunity id, that exact deal must be the contact's and won.
	 *
	 * <p><strong>Where it cannot check, it says so and carries on:</strong> GHL is one location,
	 * owned by the selling brand (D19d). Another brand's contact is not in it, and a deployment with
	 * no GHL token has nothing to ask — refusing those would lose every paid case, which is the
	 * worse failure (the same ruling as {@link #DEFAULT_SERVICE}). Both are logged at WARN.
	 */
	private Optional<GhlPipelineClient.Opportunity> confirmWonInGhl(Brand brand, OpportunityWon payload) {
		if (!ghlHttp.isConfigured() || !sellingBrand.isConfigured() || !sellingBrand.id().equals(brand.getId())) {
			log.warn("Won delivery for contact {} accepted WITHOUT confirming it in GHL: brand {} has no reachable GHL location",
					payload.contactId(), brand.getId());
			return Optional.empty();
		}
		String wanted = payload.customData() == null ? null : payload.customData().opportunityId();
		Optional<GhlPipelineClient.Opportunity> won = ghl.forContact(payload.contactId()).stream()
				.filter((deal) -> wanted == null || wanted.isBlank() || wanted.equals(deal.id()))
				.filter((deal) -> "won".equalsIgnoreCase(deal.status()))
				.findFirst();
		if (won.isEmpty()) {
			log.warn("Refused a won delivery for contact {}: GHL holds no matching won opportunity", payload.contactId());
			throw new WebhookRejected(HttpStatus.CONFLICT, "NOT_WON_IN_GHL",
					"GHL does not show a won opportunity for this contact");
		}
		return won;
	}

	/**
	 * Transport shape → domain command. The brand is not read from the payload at any
	 * point: it is the one the endpoint token resolved to.
	 *
	 * <p>The remaining nulls are the honest picture and are left visible rather than tidied
	 * away: GHL sends the person and the deal, and that is all. Visa category, subtype,
	 * deadline, invoice ref, expert and the intake note are a PM's to fill in.
	 */
	private static CaseIntakeService.NewCase toCommand(OpportunityWon payload, ServiceType service) {
		OpportunityWon.CustomData extra = payload.customData() != null ? payload.customData() : NO_CUSTOM_DATA;
		return new CaseIntakeService.NewCase(
				new CaseIntakeService.ContactDetails(payload.contactId(), payload.fullName(), payload.email(),
						payload.phone(), payload.companyName(), null, null, null, null, null),
				service,
				null,
				null,
				null,
				extra.opportunityId(),
				extra.amount(),
				null, null, null, null);
	}
}
