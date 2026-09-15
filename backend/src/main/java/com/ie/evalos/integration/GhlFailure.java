package com.ie.evalos.integration;

/**
 * Why a GHL call failed, classified <strong>at the door</strong> — Unit 45's first requirement.
 *
 * <p><strong>The problem this fixes, stated by {@code 00d} §6.3:</strong> "{@code GhlHttp} flattens
 * status into a message string, so 4xx, 5xx and timeout are indistinguishable. Only
 * 5xx/timeout/408/429 are retriable; {@code 429} must pause the <em>whole</em> outbox (the budget
 * is per location); {@code 401}/{@code 403} must <em>halt</em> it and alert, because retrying a
 * scope failure ten times across every pending row spends the entire budget on a credential
 * problem."
 *
 * <p>A string cannot carry that. Every caller downstream — the outbox, the delta sweep, the
 * sync-status surface — has to make the same three-way decision, and parsing it back out of
 * {@code "GHL refused the request with HTTP 429"} is how one of them gets it wrong.
 *
 * <p><strong>This changes no HTTP status EvalOS returns.</strong> Every one of these still maps to
 * a 502 for a staff caller: the fault is upstream either way, and a reader being told to retry
 * rather than to report a bug is the distinction that matters to them. What changes is that
 * <em>code</em> can now tell these apart, which is the thing that was impossible before.
 */
public enum GhlFailure {

	/** No token or no location in this environment. Nothing left the JVM. */
	NOT_CONFIGURED,

	/**
	 * 401 or 403 — the credential is wrong, or is missing the scope this call needs.
	 *
	 * <p><strong>Never retried, and it stops a queue rather than skipping a row.</strong> Every
	 * pending write would fail the same way, so retrying spends the location's entire request
	 * budget proving one credential is still broken. It is also the one failure a human must be
	 * told about: nothing in EvalOS can fix a missing scope.
	 */
	UNAUTHORIZED,

	/**
	 * 429 — the location's 100-requests-per-10-seconds budget is spent.
	 *
	 * <p>Retriable, but only after a pause that applies to <em>everything</em>: the budget belongs
	 * to the GHL location, not to the call that happened to hit the wall, so backing off one row
	 * while the next goes out immediately is not a back-off at all. {@link GhlHttp} pushes its own
	 * pacer forward on this, so today's callers already benefit.
	 */
	RATE_LIMITED,

	/**
	 * 408, or a request that never answered inside its timeout, or a transport failure.
	 *
	 * <p>Retriable, and the one class where a retry is genuinely likely to succeed unchanged.
	 * <strong>It is also the dangerous one for a create</strong>: a timeout means EvalOS does not
	 * know whether GHL acted, which is exactly how at-least-once delivery over a non-idempotent
	 * create produces two opportunities ({@code 00d} §6.1). The correlation custom field in slice
	 * 44d is the fix; this classification is what lets the outbox know to apply it.
	 */
	NO_ANSWER,

	/**
	 * Any other 4xx — a malformed body, an unknown id, a field GHL rejected.
	 *
	 * <p><strong>Not retriable.</strong> The request is wrong, and sending it again unchanged will
	 * be wrong again. A queue moves this row to dead rather than round the loop.
	 */
	REFUSED,

	/** 5xx. GHL's own fault, and retriable — the request was fine. */
	UPSTREAM_ERROR,

	/**
	 * GHL answered 2xx with no body where the caller needed one.
	 *
	 * <p>Not retriable: a repeat of a call GHL considered successful writes it twice.
	 */
	EMPTY_RESPONSE;

	/**
	 * Whether sending the identical request again could plausibly succeed.
	 *
	 * <p>{@code 00d} §6.3's list exactly — 5xx, timeout, 408, 429 — and nothing wider. The
	 * temptation is to add {@code REFUSED} "in case it was transient"; it was not, and a 400
	 * retried is budget spent on a body that is still malformed.
	 */
	public boolean isRetriable() {
		return this == RATE_LIMITED || this == NO_ANSWER || this == UPSTREAM_ERROR;
	}

	/**
	 * Whether this failure means every other pending call will fail the same way.
	 *
	 * <p>Two of them, for two different reasons. {@link #UNAUTHORIZED} is a credential nothing in
	 * EvalOS can repair, so the queue halts and a human is told. {@link #RATE_LIMITED} is a budget
	 * that belongs to the whole location, so the queue pauses — a per-row back-off while the next
	 * row fires immediately is not a back-off.
	 *
	 * <p>{@link #NOT_CONFIGURED} is deliberately <em>not</em> here: nothing left the JVM, so there
	 * is no queue state to protect and no upstream to be gentle with.
	 */
	public boolean stopsEverything() {
		return this == UNAUTHORIZED || this == RATE_LIMITED;
	}

	/** Classifies a status code GHL actually returned. */
	public static GhlFailure ofStatus(int status) {
		if (status == 401 || status == 403) {
			return UNAUTHORIZED;
		}
		if (status == 429) {
			return RATE_LIMITED;
		}
		if (status == 408) {
			return NO_ANSWER;
		}
		return status >= 500 ? UPSTREAM_ERROR : REFUSED;
	}
}
