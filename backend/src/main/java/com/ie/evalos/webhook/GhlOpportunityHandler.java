package com.ie.evalos.webhook;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.ClientType;
import com.ie.evalos.domain.ServiceSubtype;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.SourceChannel;
import com.ie.evalos.domain.VisaCategory;
import com.ie.evalos.service.CaseIntakeService;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * <p>The payload shape is the design's assumption, not a confirmed contract (see the
 * open question in the progress tracker). It is deliberately kept in this class so a
 * correction to it is one file and never reaches the service.
 */
@Component
public class GhlOpportunityHandler {

	/** GHL sends snake_case; unknown extra fields are ignored, as Boot defaults to. */
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record OpportunityWon(
			@NotNull @Valid Opportunity opportunity,
			@NotNull @Valid Contact contact,
			/** Which service was bought. Required: it decides the checklist, and it is half
			 * of the key that says which case this contact belongs to. */
			@NotNull ServiceType serviceType,
			ServiceSubtype serviceSubtype,
			VisaCategory visaCategory,
			UUID selectedExpertId,
			Instant deadline,
			String driveLink,
			String invoiceRef,
			String campaignAttribution,
			/**
			 * What sales wrote on the opportunity, handed to the people who will do the work
			 * (Unit 23). Optional: most deliveries carry none, and refusing one that does not
			 * would fail Handoff A over a nicety. It is never stored on the case — it becomes
			 * the note on the {@code CREATED} audit row.
			 */
			String notes) {

		/**
		 * The won deal. There is no {@code paid} field: won <em>is</em> paid, so it is not
		 * the payload's to assert.
		 */
		@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
		public record Opportunity(
				/** Carried onto the case so Unit 18 can close the opportunity back in GHL.
				 * Never an idempotency key — that is {@code event_id}'s job. */
				@NotBlank String ghlOpportunityId,
				/** What was collected, not a quote. Required and positive: a won opportunity
				 * with no money on it is a data error in GHL, not a free case. */
				@NotNull @Positive BigDecimal amount) {
		}

		@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
		public record Contact(
				String ghlContactId,
				@NotBlank String fullName,
				String email,
				String phone,
				String company,
				ClientType clientType,
				SourceChannel source,
				String utmSource,
				String utmMedium,
				String utmCampaign) {
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
	 */
	private static CaseIntakeService.NewCase toCommand(OpportunityWon payload) {
		OpportunityWon.Contact contact = payload.contact();
		return new CaseIntakeService.NewCase(
				new CaseIntakeService.ContactDetails(contact.ghlContactId(), contact.fullName(), contact.email(),
						contact.phone(), contact.company(), contact.clientType(), contact.source(),
						contact.utmSource(), contact.utmMedium(), contact.utmCampaign()),
				payload.serviceType(),
				payload.serviceSubtype(),
				payload.visaCategory(),
				payload.selectedExpertId(),
				payload.opportunity().ghlOpportunityId(),
				payload.opportunity().amount(),
				payload.deadline(),
				payload.driveLink(),
				payload.invoiceRef(),
				payload.campaignAttribution(),
				payload.notes());
	}
}
