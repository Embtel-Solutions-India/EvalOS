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
 * <p><strong>The first 28 values were never signed off by an Expert Network Manager</strong> —
 * a starter list from the unit spec, drawn for credential-evaluation degree fields. Unit 33
 * checked them against a real roster and found that <strong>ten of its twenty-two
 * disciplines had no tag at all</strong>, which the scorer reports as a zero on a 40-point
 * factor rather than as an error. The eleven values below the break close that gap and are
 * evidenced; the rest still are not. Treat a further mismatch as expected, not as a defect.
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
	HUMAN_RESOURCES,

	// Unit 33. V18's list was drawn for credential-evaluation degree fields; these are the
	// disciplines an expert-opinion-letter roster actually recruits into, and until V35
	// widened the CHECK an expert carrying one of them could not be spelled at all.
	// Applied Mathematics is MATHEMATICS and Clinical Medicine is MEDICINE — same
	// discipline, different name. PHARMACOLOGY sits beside PHARMACY because a science and
	// a practice are not the same thing.
	AEROSPACE_ENGINEERING,
	ARTIFICIAL_INTELLIGENCE,
	BIOMEDICAL_ENGINEERING,
	BIOTECHNOLOGY,
	CYBERSECURITY,
	ENVIRONMENTAL_ENGINEERING,
	MATERIALS_SCIENCE,
	NEUROSCIENCE,
	PHARMACOLOGY,
	RENEWABLE_ENERGY_ENGINEERING,
	SOFTWARE_ENGINEERING
}
