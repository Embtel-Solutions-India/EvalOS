package com.ie.evalos.domain;

/** Off-happy-path state a case can hold without leaving its stage. */
public enum ExceptionState {

	NONE,
	ON_HOLD_AWAITING_CLIENT,
	EXPERT_DECLINED_REMATCHING,
	REFUND_REQUESTED
}
