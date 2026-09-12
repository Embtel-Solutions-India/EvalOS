package com.ie.evalos.domain;

/**
 * What kind of organisation an expert is attached to.
 *
 * <p>Closed rather than free text because the Expert Network Manager filters the roster
 * on it: a petition arguing academic standing wants a university affiliation, one arguing
 * industry impact does not. Enforced twice, like every other vocabulary here — the enum at
 * the API, {@code expert_affiliation_type_known} (V35) in the database.
 */
public enum AffiliationType {

	UNIVERSITY,
	INDUSTRY,
	NATIONAL_LAB,
	GOVERNMENT,
	INDEPENDENT
}
