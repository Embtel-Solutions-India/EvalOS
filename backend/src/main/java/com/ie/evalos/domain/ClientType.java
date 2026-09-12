package com.ie.evalos.domain;

/** Who the paying customer is. */
public enum ClientType {

	ATTORNEY,
	EMPLOYER,
	INDIVIDUAL,
	AGENT,
	/** A NACES member agency ordering an evaluation. Unit 33. */
	NACES
}
