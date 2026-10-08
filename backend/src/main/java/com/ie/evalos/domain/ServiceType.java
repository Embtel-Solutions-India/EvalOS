package com.ie.evalos.domain;

/** What the customer bought. */
public enum ServiceType {

	CREDENTIAL_EVALUATION,
	EXPERT_OPINION_LETTER,
	PERM,
	RFE_RESPONSE,
	TRANSLATION,
	/** Unit 33: both already sold, neither previously spellable. */
	RECOMMENDATION_LETTER,
	WAGE_LEVEL_LETTER,
	/**
	 * The services on the business's "services, rates and document list" (2026-10-08), one per row
	 * with its own checklist. {@link #EXPERT_OPINION_LETTER} is that sheet's H-1B opinion letter.
	 */
	ACADEMIC_EQUIVALENCY_HIGH_SCHOOL,
	ACADEMIC_EQUIVALENCY,
	EXPERIENCE_BASED_EQUIVALENCY,
	ACADEMIC_EXPERIENCE_EQUIVALENCY,
	BENEFICIARY_QUALIFICATION_LETTER,
	POSITION_EVALUATION_LETTER,
	SPECIALTY_OCCUPATION_LETTER,
	EB1A_SUPPORT_LETTER,
	EB2_NIW_LETTER,
	H3_VISA_EOL,
	TN_VISA,
	O1A_VISA,
	L1A_VISA
}
