package com.ie.evalos.webhook;

import java.math.BigDecimal;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.service.CaseIntakeService;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

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
	 * is cheaper than a dropped one. Add {@code service_type} to the workflow's customData and
	 * nothing here guesses.
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

	private final CaseIntakeService intake;
	private final ObjectMapper objectMapper;
	private final Validator validator;

	GhlOpportunityHandler(CaseIntakeService intake, ObjectMapper objectMapper, Validator validator) {
		this.intake = intake;
		this.objectMapper = objectMapper;
		this.validator = validator;
	}

	void handle(Brand brand, String rawBody) {
		intake.intake(brand, toCommand(validated(parse(rawBody))));
	}

	private OpportunityWon parse(String rawBody) {
		try {
			return objectMapper.readValue(rawBody, OpportunityWon.class);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
			throw new WebhookRejected(HttpStatus.BAD_REQUEST, "MALFORMED_PAYLOAD",
					"opportunity payload could not be read");
		}
	}

	private OpportunityWon validated(OpportunityWon payload) {
		Set<ConstraintViolation<OpportunityWon>> violations = validator.validate(payload);
		if (!violations.isEmpty()) {
			ConstraintViolation<OpportunityWon> first = violations.iterator().next();
			throw new WebhookRejected(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
					first.getPropertyPath() + " " + first.getMessage());
		}
		return payload;
	}

	/**
	 * Transport shape → domain command. The brand is not read from the payload at any
	 * point: it is the one the endpoint token resolved to.
	 *
	 * <p>The remaining nulls are the honest picture and are left visible rather than tidied
	 * away: GHL sends the person and the deal, and that is all. Visa category, subtype,
	 * deadline, invoice ref, expert and the intake note are a PM's to fill in.
	 */
	private static CaseIntakeService.NewCase toCommand(OpportunityWon payload) {
		OpportunityWon.CustomData extra = payload.customData() != null ? payload.customData() : NO_CUSTOM_DATA;
		return new CaseIntakeService.NewCase(
				new CaseIntakeService.ContactDetails(payload.contactId(), payload.fullName(), payload.email(),
						payload.phone(), payload.companyName(), null, null, null, null, null),
				extra.serviceType() != null ? extra.serviceType() : DEFAULT_SERVICE,
				null,
				null,
				null,
				extra.opportunityId(),
				extra.amount(),
				null, null, null, null);
	}
}
