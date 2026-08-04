package com.ie.evalos.common;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.integration.DriveUnavailableException;
import com.ie.evalos.webhook.WebhookRejected;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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

	/**
	 * A body Jackson could not bind. Unit 11 made this worth handling: the expert form
	 * carries closed vocabularies ({@code FieldTag}, {@code LetterType}), and an unknown
	 * tag has to be a 400 that says which value was not recognised — before this, it fell
	 * through to the catch-all and answered 500 for what is squarely a bad request.
	 *
	 * <p>Only the offending value and the vocabulary's name are echoed, never Jackson's
	 * own message: that quotes the surrounding JSON and enumerates every accepted value,
	 * which is a payload echo and a vocabulary dump for the sake of one wrong word.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> onUnreadableBody(HttpMessageNotReadableException ex) {
		String detail = ex.getCause() instanceof InvalidFormatException invalid
				? "%s is not a known %s".formatted(invalid.getValue(), invalid.getTargetType().getSimpleName())
				: "Request body is not valid";
		return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_FAILED", detail));
	}

	/**
	 * The same problem in a query parameter rather than a body: a required one absent, or one
	 * that would not convert to its declared type.
	 *
	 * <p>Unhandled until Unit 12, and a real gap rather than a new one — every typed query
	 * parameter in the app was affected. {@code GET /api/experts/roster?tier=platinum} and
	 * {@code ?page=first} both answered <strong>500</strong> for what is squarely a bad request,
	 * and the shortlist's required {@code fieldTag} made it impossible to ignore. Fixed here, in
	 * the one place every route's parameter binding already routes through, rather than by
	 * loosening the parameter types at the routes.
	 *
	 * <p>Only the parameter's name is echoed, for the reason above: Spring's own message for a
	 * failed enum conversion enumerates every accepted value.
	 */
	@ExceptionHandler({ MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class })
	public ResponseEntity<ApiResponse<Void>> onBadParameter(Exception ex) {
		String name = ex instanceof MissingServletRequestParameterException missing
				? missing.getParameterName()
				: ((MethodArgumentTypeMismatchException) ex).getName();
		return ResponseEntity.badRequest()
				.body(ApiResponse.error("VALIDATION_FAILED", name + " is missing or not a value this route accepts"));
	}

	/** A request the caller can fix, stating what to fix. See the exception's own note. */
	@ExceptionHandler(InvalidRequestException.class)
	public ResponseEntity<ApiResponse<Void>> onInvalidRequest(InvalidRequestException ex) {
		return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_FAILED", ex.getMessage()));
	}

	/**
	 * A sheet larger than the configured limit. A 400 rather than the container's 500:
	 * the ENM re-exports a smaller file, which is something they can act on.
	 */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ApiResponse<Void>> onUploadTooLarge(MaxUploadSizeExceededException ex) {
		return ResponseEntity.badRequest()
				.body(ApiResponse.error("UPLOAD_TOO_LARGE", "That file is larger than the import accepts"));
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

	/**
	 * Google Drive did not take the document (Unit 13).
	 *
	 * <p>502 rather than 500, because the fault is upstream and the distinction is what tells
	 * the PM to try again instead of reporting a bug. The message is the exception's own and
	 * is deliberately incapable of naming a case or a folder — the client already knows which
	 * case it asked about, and the folder id is in the log, where the person who has to go and
	 * fix the sharing can find it.
	 *
	 * <p><strong>Nothing in EvalOS changed.</strong> The profile is generated from the roster
	 * row on every request, so there is nothing to roll back, and the audit row is written
	 * only after a successful upload.
	 */
	@ExceptionHandler(DriveUnavailableException.class)
	public ResponseEntity<ApiResponse<Void>> onDriveUnavailable(DriveUnavailableException ex) {
		log.error("Google Drive write failed", ex);
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(ApiResponse.error("DRIVE_UNAVAILABLE", ex.getMessage()));
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
