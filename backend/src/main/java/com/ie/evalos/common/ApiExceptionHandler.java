package com.ie.evalos.common;

import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.webhook.WebhookRejected;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One place that turns exceptions into the standard envelope. Messages stay
 * generic — no stack traces, no "user not found", nothing that distinguishes a
 * wrong password from an unknown address.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> onValidation(MethodArgumentNotValidException ex) {
		String detail = ex.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(error -> error.getField() + " " + error.getDefaultMessage())
				.orElse("Request is not valid");
		return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_FAILED", detail));
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ApiResponse<Void>> onAuthentication(AuthenticationException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(ApiResponse.error("INVALID_CREDENTIALS", "Email or password is incorrect"));
	}

	/**
	 * A declared-transition violation is a conflict, not a bad request: the body was
	 * valid and the caller was permitted, the case is just not in that state. This is
	 * the one handler that returns the exception's own message, which puts a rule on
	 * every throw site: an {@link IllegalTransitionException} may name a stage, an
	 * action, a role, or a row the caller already reached through a scoped read —
	 * never whether an id outside their scope exists. Two messages that differ on
	 * that point turn this 409 into an existence oracle.
	 */
	@ExceptionHandler(IllegalTransitionException.class)
	public ResponseEntity<ApiResponse<Void>> onIllegalTransition(IllegalTransitionException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ApiResponse.error("ILLEGAL_TRANSITION", ex.getMessage()));
	}

	/**
	 * An inbound delivery the gateway refused. The status is the exception's, because
	 * the source is a machine deciding whether to retry: 401/404/400 mean "do not".
	 */
	@ExceptionHandler(WebhookRejected.class)
	public ResponseEntity<ApiResponse<Void>> onWebhookRejected(WebhookRejected ex) {
		return ResponseEntity.status(ex.status()).body(ApiResponse.error(ex.code(), ex.getMessage()));
	}

	@ExceptionHandler({ AccessDeniedException.class, ForbiddenException.class })
	public ResponseEntity<ApiResponse<Void>> onForbidden(RuntimeException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(ApiResponse.error("FORBIDDEN", "Not permitted for this role, brand, or assignment"));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> onUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiResponse.error("INTERNAL_ERROR", "Something went wrong"));
	}
}
