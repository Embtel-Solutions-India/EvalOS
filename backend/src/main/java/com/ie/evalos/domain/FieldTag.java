package com.ie.evalos.domain;

/**
 * The disciplines an expert is credentialed in, and a case needs matched.
 *
 * <p><strong>Closed vocabulary.</strong> Unknown tags are rejected — by this enum at
 * the API, and by {@code expert_primary_fields_known} in the database (V18), because
 * neither alone covers the other's gap. Unit 12 matches a case's requirement against
 * these tags by equality, and {@code "Mechanical Engineering"} never matches
 * {@code "mechanical engg"}; free text would have made the scorer guess.
 *
 * <p>The cost is a migration per newly recruited discipline. Adding a value means a
 * new migration that widens the CHECK, never an edit to the applied one
 * (invariant 9), and the enum and the constraint have to move together.
 *
 * <p><strong>These values have not been signed off by an Expert Network Manager.</strong>
 * They are the starter list from the unit spec, shipped on instruction. Treat a
 * mismatch with what an ENM actually recruits into as expected, not as a defect.
 */
public enum FieldTag {

	MECHANICAL_ENGINEERING,
	ELECTRICAL_ENGINEERING,
	CIVIL_ENGINEERING,
	CHEMICAL_ENGINEERING,
	COMPUTER_SCIENCE,
	INFORMATION_TECHNOLOGY,
	DATA_SCIENCE,
	BUSINESS_ADMINISTRATION,
	FINANCE,
	ACCOUNTING,
	MARKETING,
	ECONOMICS,
	NURSING,
	MEDICINE,
	PHARMACY,
	PUBLIC_HEALTH,
	EDUCATION,
	LAW,
	ARCHITECTURE,
	BIOLOGY,
	CHEMISTRY,
	PHYSICS,
	MATHEMATICS,
	PSYCHOLOGY,
	FINE_ARTS,
	HOSPITALITY_MANAGEMENT,
	SUPPLY_CHAIN,
	HUMAN_RESOURCES
}
