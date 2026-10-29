package com.ie.evalos.domain;

/** Off-happy-path state a case can hold without leaving its stage. */
public enum ExceptionState {

	NONE,
	ON_HOLD_AWAITING_CLIENT,
	EXPERT_DECLINED_REMATCHING,
	REFUND_REQUESTED,
	/**
	 * A case closed without delivery because it should not have been worked (2026-10-30): opened by
	 * mistake, a duplicate, the client withdrew. Held on the CLOSED case like an approved refund's
	 * {@link #REFUND_REQUESTED}. A case is never deleted: its chat membership is history (V69).
	 */
	CANCELLED
}
