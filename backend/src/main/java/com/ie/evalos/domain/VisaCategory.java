package com.ie.evalos.domain;

/** The petition the evaluation supports. */
public enum VisaCategory {

	H1B,
	EB1A,
	EB2_NIW,
	O1,
	TN,
	PERM,
	L1A,
	/** The three NACES purposes. They collapsed into OTHER until Unit 33, which lost the
	 * distinction the credential-evaluation side of the business runs on. */
	EDUCATION,
	EMPLOYMENT,
	ADMISSION,
	OTHER
}
