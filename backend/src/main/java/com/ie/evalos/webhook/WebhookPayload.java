package com.ie.evalos.webhook;

import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Parse-then-trust, in one place.
 *
 * <p>Every handler in this package does the same two things before it touches a service: read the
 * body into its own record, and validate it in full — so a malformed delivery is a 400 GHL will not
 * retry rather than a half-applied change. It was private to {@link GhlOpportunityHandler} until
 * Unit 45d added a second handler; copying the two methods would have been copying the shape of the
 * refusal, which is the part that must not diverge between events.
 */
@Component
class WebhookPayload {

	private final ObjectMapper objectMapper;
	private final Validator validator;

	WebhookPayload(ObjectMapper objectMapper, Validator validator) {
		this.objectMapper = objectMapper;
		this.validator = validator;
	}

	/**
	 * @param what names the entity in the refusal, so a rejected delivery says which event was
	 *             unreadable rather than only that something was
	 */
	<T> T read(String rawBody, Class<T> shape, String what) {
		return validated(parse(rawBody, shape, what));
	}

	private <T> T parse(String rawBody, Class<T> shape, String what) {
		try {
			return objectMapper.readValue(rawBody, shape);
		}
		catch (JsonProcessingException ex) {
			throw new WebhookRejected(HttpStatus.BAD_REQUEST, "MALFORMED_PAYLOAD",
					what + " payload could not be read");
		}
	}

	private <T> T validated(T payload) {
		Set<ConstraintViolation<T>> violations = validator.validate(payload);
		if (!violations.isEmpty()) {
			ConstraintViolation<T> first = violations.iterator().next();
			throw new WebhookRejected(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
					first.getPropertyPath() + " " + first.getMessage());
		}
		return payload;
	}
}
