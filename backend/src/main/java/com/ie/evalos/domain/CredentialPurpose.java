package com.ie.evalos.domain;

/** Why a credential token was minted. The database check constraint in {@code V43} mirrors this. */
public enum CredentialPurpose {

	/** First password, for an account that has never had one — including every seeded account. */
	SET,

	/** Replacement password, for an account that has one and whose owner cannot remember it. */
	RESET
}
