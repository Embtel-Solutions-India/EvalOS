package com.ie.evalos.domain;

/**
 * What an expert is willing to put their name to.
 *
 * <p>Closed for the same reasons as {@link FieldTag} and enforced the same two ways
 * (V18's {@code expert_letter_types_known}). The values follow the two deliverables
 * and the goals the design already names, so this list is narrower than
 * {@link ServiceType} on purpose: it is the expert's signing appetite, not the
 * catalogue EvalOS sells.
 */
public enum LetterType {

	CREDENTIAL_EVALUATION,
	EXPERT_OPINION_LETTER,
	RFE_RESPONSE,
	PERM_LETTER,
	TRANSLATION_CERTIFICATION,
	RECOMMENDATION_LETTER,
	WAGE_LEVEL_LETTER
}
