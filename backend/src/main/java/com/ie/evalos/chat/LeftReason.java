package com.ie.evalos.chat;

/** Why a participant left a conversation. Stamped on the member row, which is never deleted. */
public enum LeftReason {
	REASSIGNED, OFFER_DECLINED, OFFER_TIMED_OUT, OFFER_SUPERSEDED, PIPELINE_REVOKED, ROLE_CHANGED, DEACTIVATED,
	ACCOUNT_REMOVED
}
