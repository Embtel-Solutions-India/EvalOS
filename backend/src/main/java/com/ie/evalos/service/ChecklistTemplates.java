package com.ie.evalos.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.ie.evalos.domain.ServiceType;

/**
 * What the client has to send in, per service type. Read once at intake to seed the
 * checklist; the rows are the case's own copy from then on, so editing a template
 * never changes a case already in flight.
 *
 * <p>ponytail: a static map, not a table. It moves into the database the first time
 * a Brand Manager needs to edit a checklist without a deploy — at which point the
 * seed for the new table is this map.
 */
public final class ChecklistTemplates {

	/** Every service type needs the identity and the credential itself. */
	private static final List<String> IDENTITY_AND_CREDENTIAL = List.of(
			"Passport or government photo ID",
			"Degree certificate or diploma",
			"Official transcripts / mark sheets");

	// The business's "services, rates and document list" (2026-10-08). Only what a row marks as
	// needed is seeded: every seeded item opens REQUIRED and "documents complete" waits for all of
	// them, so an optional, "if applicable" or "in case of RFE" document would block a case that
	// never needed it. The Coordinator or Case Manager adds those to the one case that does (D60).
	private static final String PASSPORT = "Copy of the passport information page "
			+ "(if your name changed, or the evaluation must carry the name as per passport)";
	private static final String WORK_LETTER = "Work experience letter(s) on company letterhead with joining date, "
			+ "end date and job duties (a notarized colleague letter works if you don't have one)";
	private static final String RESUME = "Detailed and updated resume (Word document)";
	private static final String TRANSCRIPTS = "Transcripts and degree certificates of all degrees";
	private static final String MARKSHEET_12 = "12th mark sheet";
	private static final String BENEFICIARY_FORM = "Beneficiary form";
	private static final String LCA = "Copy of the LCA and offer letter";
	private static final String POSITION_DESCRIPTION = "Position description (as per template)";
	private static final String DUTIES_TO_SUBJECT = "Relationship of duties to specific subject (as per template)";
	private static final String DEGREE_TO_SPECIALTY =
			"Relationship of degree subjects to the specialty occupation (as per template)";
	private static final String EVALUATION_DOCS = "Evaluation documents (AE, EXBE or A&EXBE)";
	private static final String SUPPORT_LETTER = "Soft copy of the support letter (Word document)";

	private static final List<String> EQUIVALENCY_WITH_EXPERIENCE = List.of(
			WORK_LETTER, RESUME, TRANSCRIPTS, MARKSHEET_12, PASSPORT, BENEFICIARY_FORM);

	private static final Map<ServiceType, List<String>> BY_SERVICE = new EnumMap<>(ServiceType.class);

	static {
		// Not on the sheet, so unchanged.
		BY_SERVICE.put(ServiceType.CREDENTIAL_EVALUATION, concat(IDENTITY_AND_CREDENTIAL,
				"Certified English translation of any non-English document"));
		BY_SERVICE.put(ServiceType.PERM, concat(IDENTITY_AND_CREDENTIAL,
				"Job description / ETA-9089 details",
				"Prior experience letters"));
		BY_SERVICE.put(ServiceType.RFE_RESPONSE, concat(IDENTITY_AND_CREDENTIAL,
				"The RFE notice as issued",
				"Copy of the original petition"));
		BY_SERVICE.put(ServiceType.TRANSLATION, List.of(
				"Source documents to be translated",
				"Passport or government photo ID"));

		BY_SERVICE.put(ServiceType.ACADEMIC_EQUIVALENCY_HIGH_SCHOOL, List.of(
				"Senior secondary mark sheet and certificate", PASSPORT));
		BY_SERVICE.put(ServiceType.ACADEMIC_EQUIVALENCY, List.of(
				TRANSCRIPTS, MARKSHEET_12, PASSPORT, BENEFICIARY_FORM));
		BY_SERVICE.put(ServiceType.EXPERIENCE_BASED_EQUIVALENCY, EQUIVALENCY_WITH_EXPERIENCE);
		BY_SERVICE.put(ServiceType.ACADEMIC_EXPERIENCE_EQUIVALENCY, EQUIVALENCY_WITH_EXPERIENCE);

		// The H-1B opinion letter. The relationship templates and evaluation documents are marked
		// optional on this row only, so they are left to be added when wanted.
		BY_SERVICE.put(ServiceType.EXPERT_OPINION_LETTER, List.of(
				LCA, WORK_LETTER, RESUME, TRANSCRIPTS, POSITION_DESCRIPTION));
		BY_SERVICE.put(ServiceType.BENEFICIARY_QUALIFICATION_LETTER, List.of(
				LCA, WORK_LETTER, RESUME, TRANSCRIPTS, POSITION_DESCRIPTION, DUTIES_TO_SUBJECT,
				DEGREE_TO_SPECIALTY, EVALUATION_DOCS));
		BY_SERVICE.put(ServiceType.POSITION_EVALUATION_LETTER, List.of(
				LCA, SUPPORT_LETTER, WORK_LETTER, RESUME, TRANSCRIPTS, POSITION_DESCRIPTION,
				DUTIES_TO_SUBJECT, DEGREE_TO_SPECIALTY, EVALUATION_DOCS));
		BY_SERVICE.put(ServiceType.SPECIALTY_OCCUPATION_LETTER, List.of(
				"Job postings by similar-sized companies showing the offered title, or similar positions, "
						+ "require a bachelor's degree (dice.com, monster.com, etc.)",
				"Employer's org chart showing the beneficiary's position (as per sample)",
				LCA, SUPPORT_LETTER, WORK_LETTER, RESUME, TRANSCRIPTS, POSITION_DESCRIPTION, DUTIES_TO_SUBJECT,
				DEGREE_TO_SPECIALTY, EVALUATION_DOCS));

		BY_SERVICE.put(ServiceType.EB1A_SUPPORT_LETTER, List.of(
				RESUME,
				TRANSCRIPTS,
				"Documents for every EB1A criterion you qualify under: prizes or awards, association memberships, "
						+ "published material about you, judging the work of others, original contributions, "
						+ "scholarly articles, artistic exhibitions, a leading or critical role, high salary, "
						+ "commercial success in the performing arts"));
		BY_SERVICE.put(ServiceType.EB2_NIW_LETTER, List.of(
				"Current updated CV",
				"Educational documents: all degree certificates and transcripts",
				"Experience letters on company letterhead detailing your roles and responsibilities",
				"Proposed endeavor: a detailed description of the project or activity",
				"Evidence of the potential to employ U.S. workers"));
		BY_SERVICE.put(ServiceType.H3_VISA_EOL, List.of(
				"Resume of the beneficiary: education, work experience and certifications",
				"Job offer / training program letter: outline, duration and location",
				"Detailed job / training description: the specific skills the beneficiary will acquire",
				"Educational documents: relevant transcripts and degree certificates",
				"Company information for the U.S. sponsor: the employer or training institution"));
		BY_SERVICE.put(ServiceType.TN_VISA, List.of(
				"Updated CV / resume: education, work experience, skills and accomplishments",
				"Educational documents: all degree certificates and official transcripts",
				"Experience letters from previous employers on company letterhead: job titles, dates and duties",
				"Job offer letter from the U.S. employer: title, duties, salary and terms",
				"Detailed job description showing how the TN position's duties align with your qualifications"));
		BY_SERVICE.put(ServiceType.O1A_VISA, List.of(
				"Evidence for each O-1A criterion you qualify under: awards and honors, professional memberships, "
						+ "published material, significant contributions, authorship, high salary, judging the work "
						+ "of others, critical or essential employment",
				"Educational documents: all degree certificates and official transcripts",
				"Experience letters from previous employers on company letterhead"));
		BY_SERVICE.put(ServiceType.L1A_VISA, List.of(
				RESUME,
				"All degree certificates and transcripts",
				"Experience letters from all relevant employers (on letterhead: title, dates, duties)",
				"Offer letter from the U.S. petitioner company",
				"Organizational chart showing the beneficiary's managerial or executive position, "
						+ "abroad and in the proposed U.S. role",
				"Evidence of at least one continuous year of employment with the company abroad in the past three years",
				"Description of managerial or executive duties, abroad and in the proposed U.S. role"));
	}

	private ChecklistTemplates() {
	}

	/**
	 * The documents to open as {@code REQUIRED}. Falls back to the identity and
	 * credential set for a service type nobody has written a template for yet —
	 * an empty checklist would let {@code markDocsComplete} pass with no documents.
	 */
	public static List<String> forService(ServiceType serviceType) {
		return BY_SERVICE.getOrDefault(serviceType, IDENTITY_AND_CREDENTIAL);
	}

	private static List<String> concat(List<String> base, String... extra) {
		return Stream.concat(base.stream(), Stream.of(extra)).toList();
	}
}
