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
 * Handoff A's handler: GHL says a contact was created, EvalOS takes custody of the
 * work that contact represents.
 *
 * <p>This used to be a payment handler, and the difference matters. The webhook is no
 * longer proof of payment — it is proof that somebody exists and wants something. The
 * case it creates is unpaid until a GM or Brand Manager records the money, and
 * nothing that costs an expert's time can happen before then.
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
public class GhlContactHandler {

	/** GHL sends snake_case; unknown extra fields are ignored, as Boot defaults to. */
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record ContactCreated(
			@NotNull @Valid Contact contact,
			/** Which service this contact asked for. Required: it decides the checklist,
			 * and it is half of the key that says which case this contact belongs to. */
			@NotNull ServiceType serviceType,
			ServiceSubtype serviceSubtype,
			VisaCategory visaCategory,
			UUID selectedExpertId,
			/** A quote, not a payment. {@code markPaid} records what was actually taken. */
			@Positive BigDecimal quoteAmount,
			Instant deadline,
			String driveLink,
			String invoiceRef,
			String campaignAttribution,
			/** True only when GHL already knows this contact paid. Defaults to false. */
			boolean paid) {

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

	GhlContactHandler(CaseIntakeService intake, ObjectMapper objectMapper, Validator validator) {
		this.intake = intake;
		this.objectMapper = objectMapper;
		this.validator = validator;
	}

	void handle(Brand brand, String rawBody) {
		intake.intake(brand, toCommand(validated(parse(rawBody))));
	}

	private ContactCreated parse(String rawBody) {
		try {
			return objectMapper.readValue(rawBody, ContactCreated.class);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
			throw new WebhookRejected(HttpStatus.BAD_REQUEST, "MALFORMED_PAYLOAD",
					"contact payload could not be read");
		}
	}

	private ContactCreated validated(ContactCreated payload) {
		Set<ConstraintViolation<ContactCreated>> violations = validator.validate(payload);
		if (!violations.isEmpty()) {
			ConstraintViolation<ContactCreated> first = violations.iterator().next();
			throw new WebhookRejected(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
					first.getPropertyPath() + " " + first.getMessage());
		}
		return payload;
	}

	/**
	 * Transport shape → domain command. The brand is not read from the payload at any
	 * point: it is the one the endpoint token resolved to.
	 */
	private static CaseIntakeService.NewCase toCommand(ContactCreated payload) {
		ContactCreated.Contact contact = payload.contact();
		return new CaseIntakeService.NewCase(
				new CaseIntakeService.ContactDetails(contact.ghlContactId(), contact.fullName(), contact.email(),
						contact.phone(), contact.company(), contact.clientType(), contact.source(),
						contact.utmSource(), contact.utmMedium(), contact.utmCampaign()),
				payload.serviceType(),
				payload.serviceSubtype(),
				payload.visaCategory(),
				payload.selectedExpertId(),
				payload.quoteAmount(),
				payload.deadline(),
				payload.driveLink(),
				payload.invoiceRef(),
				payload.campaignAttribution(),
				payload.paid());
	}
}
