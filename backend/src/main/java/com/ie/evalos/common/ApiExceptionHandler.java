package com.ie.evalos.common;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.integration.DocumentStoreUnavailableException;
import com.ie.evalos.integration.GhlUnavailableException;
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
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
	/**
	 * A party token on a single-case route, with more than one case behind it (Unit 35, D1).
	 *
	 * <p>Shares the 409 with {@code IllegalTransitionException} and deliberately not its code: this
	 * one asks the caller to name a case, and a portal that cannot tell the two apart tells a
	 * client "not allowed" when the answer is "which one".
	 */
	@ExceptionHandler(AmbiguousCaseException.class)
	public ResponseEntity<ApiResponse<Void>> onAmbiguousCase(AmbiguousCaseException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ApiResponse.error("SAY_WHICH_CASE", ex.getMessage()));
	}

	/**
	 * A salesperson opening a second deal for a contact who already has one.
	 *
	 * <p>409 rather than 400, and for the same reason {@code SAY_WHICH_CASE} above is: the caller
	 * has not made a mistake. A repeat client buying a second service is ordinary business — the
	 * answer is "here is the deal they already have, still want another?", not "no". The code is
	 * what lets the form draw a confirmation step instead of an error, and the existing deal's id
	 * rides along so it can link to it.
	 */
	@ExceptionHandler(DuplicateDealException.class)
	public ResponseEntity<ApiResponse<Void>> onDuplicateDeal(DuplicateDealException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ApiResponse.error("DEAL_ALREADY_OPEN",
						ex.getMessage() + " (" + ex.existingOpportunityId() + ")"));
	}

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


	/** A write to the conversation of a closed case (Unit 57). The composer shows why. */
	@ExceptionHandler(com.ie.evalos.chat.ConversationReadOnlyException.class)
	public ResponseEntity<ApiResponse<Void>> onConversationReadOnly(com.ie.evalos.chat.ConversationReadOnlyException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("CONVERSATION_READ_ONLY", ex.getMessage()));
	}

	/** No Ably key here (Unit 57): chat works over REST, without live updates. */
	@ExceptionHandler(com.ie.evalos.chat.live.RealtimeUnavailableException.class)
	public ResponseEntity<ApiResponse<Void>> onRealtimeUnavailable(com.ie.evalos.chat.live.RealtimeUnavailableException ex) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error("REALTIME_UNAVAILABLE", ex.getMessage()));
	}

	/** No VAPID keys here (Unit 57): the apps hide the notifications card. */
	@ExceptionHandler(com.ie.evalos.chat.push.PushUnavailableException.class)
	public ResponseEntity<ApiResponse<Void>> onPushUnavailable(com.ie.evalos.chat.push.PushUnavailableException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("PUSH_UNAVAILABLE", ex.getMessage()));
	}

	/** More than 30 chat messages a minute from one person (Unit 57). */
	@ExceptionHandler(com.ie.evalos.chat.ChatRateLimiter.TooManyMessagesException.class)
	public ResponseEntity<ApiResponse<Void>> onTooManyMessages(com.ie.evalos.chat.ChatRateLimiter.TooManyMessagesException ex) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiResponse.error("TOO_MANY_MESSAGES", ex.getMessage()));
	}

	/**
	 * The document store did not answer.
	 *
	 * <p><strong>Nothing in EvalOS changed.</strong> A failed put saves nothing and a failed
	 * presign hands out nothing, so a 502 tells the caller to retry rather than to report a bug.
	 * Took over the slot {@code DriveUnavailableException} held until Unit 30.
	 */
	@ExceptionHandler(DocumentStoreUnavailableException.class)
	public ResponseEntity<ApiResponse<Void>> onDocumentStoreUnavailable(DocumentStoreUnavailableException ex) {
		log.error("Document store call failed", ex);
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(ApiResponse.error("DOCUMENT_STORE_UNAVAILABLE", ex.getMessage()));
	}

	/**
	 * An upstream that did not
	 * answer is a 502, and the distinction from a 500 is what tells the reader to try again
	 * rather than to report a bug.
	 *
	 * <p>Kept as its own handler rather than folded in with the document store, so the error code
	 * names which upstream failed. A storage error on a marketing screen would send whoever reads the
	 * log at the wrong integration.
	 *
	 * <p><strong>Nothing in EvalOS changed.</strong> Every GHL read this covers feeds a view; the
	 * message is safe to echo because {@code GhlPipelineClient} composes it and never puts the
	 * API token or another team's pipeline names in it.
	 */
	@ExceptionHandler(GhlUnavailableException.class)
	public ResponseEntity<ApiResponse<Void>> onGhlUnavailable(GhlUnavailableException ex) {
		log.error("GHL read failed", ex);
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(ApiResponse.error("GHL_UNAVAILABLE", ex.getMessage()));
	}

	@ExceptionHandler({ AccessDeniedException.class, ForbiddenException.class })
	public ResponseEntity<ApiResponse<Void>> onForbidden(RuntimeException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(ApiResponse.error("FORBIDDEN", "Not permitted for this role, brand, or assignment"));
	}

	/**
	 * A URL nothing is mapped to. Without this it fell through to the catch-all below and
	 * answered <strong>500 INTERNAL_ERROR</strong>, logged at error level — this advice is a
	 * plain {@code @RestControllerAdvice}, so it does not inherit
	 * {@code ResponseEntityExceptionHandler}'s handling of Spring's own
	 * {@code ErrorResponseException}s. Every typo'd path was therefore an alertable server
	 * error. Found while asserting that {@code POST /api/cases/{id}/mark-paid} is gone.
	 *
	 * <p>No detail in the body: whether a path exists is not information a caller is owed.
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> onNoSuchRoute(NoResourceFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ApiResponse.error("NOT_FOUND", "No such endpoint"));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> onUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiResponse.error("INTERNAL_ERROR", "Something went wrong"));
	}
}
