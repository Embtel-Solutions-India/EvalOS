package com.ie.evalos.webhook;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Handoff A's handler: GHL says an invoice was paid, EvalOS takes custody. The
 * webhook is the proof of payment — EvalOS never talks to a payment processor.
 *
 * <p>Parse-then-trust. The payload is deserialized and validated in full before the
 * intake service is called, so a malformed delivery is a 400 that GHL will not
 * retry rather than a half-created case.
 *
 * <p>The payload shape is the design's assumption, not a confirmed contract (see the
 * open question in the progress tracker). It is deliberately kept in this class so a
 * correction to it is one file and never reaches the service.
 */
@Component
public class GhlPaymentHandler {

	/** GHL sends snake_case; unknown extra fields are ignored, as Boot defaults to. */
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record PaymentConfirmed(
			@NotNull @Valid Contact contact,
			@NotNull ServiceType serviceType,
			ServiceSubtype serviceSubtype,
			VisaCategory visaCategory,
			UUID selectedExpertId,
			@NotNull @Positive BigDecimal quoteAmount,
			Instant deadline,
			String driveLink,
			String invoiceRef,
			String paymentId,
			String campaignAttribution) {

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

	GhlPaymentHandler(CaseIntakeService intake, ObjectMapper objectMapper, Validator validator) {
		this.intake = intake;
		this.objectMapper = objectMapper;
		this.validator = validator;
	}

	void handle(Brand brand, String rawBody) {
		PaymentConfirmed payload = validated(parse(rawBody));
		intake.intake(brand, toCommand(payload));
	}

	private PaymentConfirmed parse(String rawBody) {
		try {
			return objectMapper.readValue(rawBody, PaymentConfirmed.class);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
			throw new WebhookRejected(HttpStatus.BAD_REQUEST, "MALFORMED_PAYLOAD",
					"payment.confirmed payload could not be read");
		}
	}

	private PaymentConfirmed validated(PaymentConfirmed payload) {
		Set<ConstraintViolation<PaymentConfirmed>> violations = validator.validate(payload);
		if (!violations.isEmpty()) {
			ConstraintViolation<PaymentConfirmed> first = violations.iterator().next();
			throw new WebhookRejected(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
					first.getPropertyPath() + " " + first.getMessage());
		}
		// The idempotency key the gateway deduplicated on has to be one of these, so a
		// payload carrying neither would create a second case on redelivery.
		if (blank(payload.invoiceRef()) && blank(payload.paymentId())) {
			throw new WebhookRejected(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
					"one of invoice_ref or payment_id is required");
		}
		return payload;
	}

	/**
	 * Transport shape → domain command. The brand is not read from the payload at any
	 * point: it is the one the endpoint token resolved to (invariant 8).
	 */
	private static CaseIntakeService.NewCase toCommand(PaymentConfirmed payload) {
		PaymentConfirmed.Contact contact = payload.contact();
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
				blank(payload.invoiceRef()) ? payload.paymentId() : payload.invoiceRef(),
				payload.campaignAttribution());
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
