package com.ie.evalos.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ie.evalos.common.PaymentDetailConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A brand-scoped roster expert. Experts are not shared across brands: the same
 * person recruited by two brands is two rows.
 *
 * <p>This entity deliberately has no {@code toString()}. It holds the one
 * encrypted field in EvalOS, and a generated {@code toString} is the usual way a
 * secret ends up in a log line.
 */
@Entity
@Table(name = "expert")
public class Expert extends ScopedEntity {

	@Column(name = "full_name")
	private String fullName;

	@Column(name = "title")
	private String title;

	@Column(name = "institution")
	private String institution;

	/**
	 * How the Expert Network Manager reaches this person, and the import's upsert key
	 * (V18's partial unique index on {@code (brand_id, lower(email))}). Nothing mails
	 * from here — EvalOS has no mail server (invariant 14); Unit 15 sends the signing
	 * request through Dropbox Sign.
	 */
	@Column(name = "email")
	private String email;

	@Column(name = "phone")
	private String phone;

	/**
	 * Taxonomy tags matching draws on (Unit 12). Held as {@code text[]} of
	 * {@link FieldTag} names rather than an enum array: enum arrays buy nothing here
	 * and risk a {@code ddl-auto=validate} mismatch. The accessors are the typed
	 * boundary, and V18's CHECK is what stops an untyped writer.
	 */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "primary_fields")
	private String[] primaryFields;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "secondary_fields")
	private String[] secondaryFields;

	/** {@link LetterType} names — what this expert will sign. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "letter_types")
	private String[] letterTypes;

	@Enumerated(EnumType.STRING)
	@Column(name = "availability")
	private Availability availability;

	@Enumerated(EnumType.STRING)
	@Column(name = "tier")
	private ExpertTier tier;

	/** 1–10. */
	@Column(name = "quality_score")
	private BigDecimal qualityScore;

	@Column(name = "avg_response_hours")
	private BigDecimal avgResponseHours;

	// `total_cases_completed` and `current_active_count` are NOT mapped, deliberately.
	//
	// Both columns exist in `V7` as `NOT NULL DEFAULT 0` and both are still there — the fields
	// that used to mirror them here are gone because they were dead weight: private, with **no
	// accessor at all**, so nothing could read or write them even by accident. Hibernate simply
	// omits them on insert and Postgres applies the default.
	//
	// **The columns stay on purpose.** They are this codebase's standing example behind
	// `code-standards.md`'s "prefer deriving over storing" — `ExpertLoadService` counts
	// `evalos_case` instead, because a stored counter is wrong the moment a case moves and
	// nothing increments it. Around forty comments, specs and memories cite them by name as
	// exactly that; dropping the columns would make every one of those references describe
	// something that no longer exists, to save two ints per row. Do not add fields back here.

	@Enumerated(EnumType.STRING)
	@Column(name = "agreement_status")
	private AgreementStatus agreementStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_status")
	private ExpertPaymentStatus paymentStatus;

	@Column(name = "total_payments_pending", nullable = false)
	private BigDecimal totalPaymentsPending = BigDecimal.ZERO;

	/** {@link PerformanceFlag} names. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "performance_flags")
	private String[] performanceFlags;

	/**
	 * What this expert usually charges, which Unit 16 prefills a payout with.
	 *
	 * <p>Not a price: nothing client-facing reads it, and it is not what the brand
	 * charges for the service. The build plan listed a fee among this roster's fields
	 * and {@code V7} had no column for one.
	 */
	@Column(name = "standard_fee")
	private BigDecimal standardFee;

	@Column(name = "recruitment_source")
	private String recruitmentSource;

	@Column(name = "date_onboarded")
	private LocalDate dateOnboarded;

	@Column(name = "notes")
	private String notes;

	/**
	 * How this expert is paid, in free text. The only encrypted field in EvalOS:
	 * it is written and read exclusively through {@link PaymentDetailConverter},
	 * so the column holds ciphertext. Never map it into a DTO, an outbound webhook
	 * payload, or a log line — {@code @JsonIgnore} stops the accidental case, not
	 * the deliberate one.
	 */
	@Convert(converter = PaymentDetailConverter.class)
	@Column(name = "payment_detail")
	@JsonIgnore
	private String paymentDetail;

	// --- Unit 33: the standing dossier -------------------------------------------------
	//
	// One wide table, not an `expert_credentials` child: the relationship is 1:1, there is
	// no history to keep and no second implementation, so a join here would be added for
	// symmetry and paid for on every profile read.
	//
	// `last_active_date` from the roster sheet is deliberately absent — it is
	// max(offered_at) over `expert_case_offer`, the same fact with no writer to forget.

	/**
	 * The roster's human id ({@code IE-EXP-###}), unique per brand (V35). This is what a
	 * case sheet's {@code evaluator_id} points at, so an import cannot link a case to an
	 * expert without it.
	 */
	@Column(name = "expert_code")
	private String expertCode;

	/**
	 * The niche inside the field — "Power Systems &amp; Smart Grids". Free text on purpose:
	 * {@link #secondaryFields} is the closed vocabulary, and a niche is exactly where a
	 * closed vocabulary is wrong on its first unseen value.
	 */
	@Column(name = "sub_specialization")
	private String subSpecialization;

	@Column(name = "highest_degree")
	private String highestDegree;

	@Column(name = "degree_field")
	private String degreeField;

	/** Where the degree came from, which is not {@link #institution} — that is where they work now. */
	@Column(name = "degree_institution")
	private String degreeInstitution;

	@Column(name = "current_position")
	private String currentPosition;

	@Enumerated(EnumType.STRING)
	@Column(name = "affiliation_type")
	private AffiliationType affiliationType;

	/** US-based is preferred for USCIS letters, so location is a filter, not a footnote. */
	@Column(name = "country")
	private String country;

	@Column(name = "state_region")
	private String stateRegion;

	@Column(name = "years_experience")
	private Integer yearsExperience;

	@Column(name = "linkedin_url")
	private String linkedinUrl;

	/**
	 * {@link VisaCategory} names — the petitions this expert will write for. <strong>Not the
	 * same fact as {@link #letterTypes}</strong>: that is the deliverable they will sign,
	 * this is what the deliverable supports.
	 */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "visa_categories")
	private String[] visaCategories;

	// The standing metrics an expert opinion letter rests on.

	@Column(name = "publications")
	private Integer publications;

	@Column(name = "citations")
	private Integer citations;

	@Column(name = "h_index")
	private Integer hIndex;

	@Column(name = "patents")
	private Integer patents;

	// Narrative and comma-separated, as a roster sheet has them. Free text rather than
	// arrays: nothing queries inside these, and four GIN indexes to render a detail view
	// would be cost with no reader.

	@Column(name = "notable_awards")
	private String notableAwards;

	@Column(name = "professional_memberships")
	private String professionalMemberships;

	@Column(name = "editorial_roles")
	private String editorialRoles;

	@Column(name = "languages")
	private String languages;

	/** Can take a 48-hour rush. */
	@Column(name = "rush_available", nullable = false)
	private boolean rushAvailable;

	/**
	 * Typical days to complete a letter, which is not {@link #avgResponseHours} — that is
	 * how fast they answer an offer, and a fast replier can be a slow writer.
	 */
	@Column(name = "avg_turnaround_days")
	private Integer avgTurnaroundDays;

	protected Expert() {
		// for JPA
	}

	public Expert(UUID brandId, String fullName) {
		super(brandId);
		this.fullName = fullName;
	}

	/** Read by the state machine before an expert is put on a case (Unit 04). */
	/** Added for Unit 08's assignment picker — the first consumer that needed to show a name. */
	public String getFullName() {
		return fullName;
	}

	public void setFullName(String fullName) {
		this.fullName = fullName;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getInstitution() {
		return institution;
	}

	public void setInstitution(String institution) {
		this.institution = institution;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public List<FieldTag> getPrimaryFields() {
		return values(primaryFields, FieldTag.class);
	}

	public void setPrimaryFields(List<FieldTag> primaryFields) {
		this.primaryFields = names(primaryFields);
	}

	public List<FieldTag> getSecondaryFields() {
		return values(secondaryFields, FieldTag.class);
	}

	public void setSecondaryFields(List<FieldTag> secondaryFields) {
		this.secondaryFields = names(secondaryFields);
	}

	public List<LetterType> getLetterTypes() {
		return values(letterTypes, LetterType.class);
	}

	public void setLetterTypes(List<LetterType> letterTypes) {
		this.letterTypes = names(letterTypes);
	}

	/** Unit 09's expert card. Matching preference reads it properly in Unit 12. */
	public ExpertTier getTier() {
		return tier;
	}

	public void setTier(ExpertTier tier) {
		this.tier = tier;
	}

	/** Set by Unit 11's roster screen; before that only tests and seed data wrote it. */
	public void setAvailability(Availability availability) {
		this.availability = availability;
	}

	public Availability getAvailability() {
		return availability;
	}

	/**
	 * Availability for anything that has to bucket <em>every</em> row: unset reads as INACTIVE.
	 *
	 * <p>The column is nullable (V7) with no default and the sheet import need not set it, so an
	 * expert nobody has assessed is a real row. Null is not a fifth state — for staffing the next
	 * case "nobody has said" and INACTIVE are the same answer — but it is a value that throws
	 * rather than counts as zero when used as an {@link java.util.EnumMap} key or a {@code switch}
	 * subject, which is how one unset row 500'd the expert-network metrics endpoint.
	 *
	 * <p><strong>Aggregations, groupings and filters call this; a single expert's own record does
	 * not.</strong> A count or a filter that drops the row is wrong — a roster row missing from
	 * every column is a row nobody will think to fix. But the profile and the roster row show
	 * {@code getAvailability()} raw, so the UI can say "not set" rather than assert INACTIVE about
	 * someone nobody has asked.
	 */
	public Availability availabilityOrInactive() {
		return availability == null ? Availability.INACTIVE : availability;
	}

	public BigDecimal getQualityScore() {
		return qualityScore;
	}

	public void setQualityScore(BigDecimal qualityScore) {
		this.qualityScore = qualityScore;
	}

	public BigDecimal getAvgResponseHours() {
		return avgResponseHours;
	}

	/**
	 * The concerns recorded against this expert. Added for Unit 12, which <em>shows</em> them
	 * on the shortlist card rather than scoring them: folding a {@code CLIENT_COMPLAINT} into
	 * a number hides the one thing a human should see before assigning. The typed accessor is
	 * the boundary, as with the taxonomy arrays above.
	 */
	public List<PerformanceFlag> getPerformanceFlags() {
		return values(performanceFlags, PerformanceFlag.class);
	}

	public void setPerformanceFlags(List<PerformanceFlag> performanceFlags) {
		this.performanceFlags = names(performanceFlags);
	}

	public BigDecimal getStandardFee() {
		return standardFee;
	}

	public void setStandardFee(BigDecimal standardFee) {
		this.standardFee = standardFee;
	}

	public AgreementStatus getAgreementStatus() {
		return agreementStatus;
	}

	public ExpertPaymentStatus getPaymentStatus() {
		return paymentStatus;
	}

	/**
	 * What Unit 16 will owe this expert. Read here, maintained there — and today it is
	 * a permanent zero, like the two case counters: nothing has ever written it. The
	 * roster does not display it for that reason.
	 */
	public BigDecimal getTotalPaymentsPending() {
		return totalPaymentsPending;
	}

	public String getRecruitmentSource() {
		return recruitmentSource;
	}

	public void setRecruitmentSource(String recruitmentSource) {
		this.recruitmentSource = recruitmentSource;
	}

	public LocalDate getDateOnboarded() {
		return dateOnboarded;
	}

	public void setDateOnboarded(LocalDate dateOnboarded) {
		this.dateOnboarded = dateOnboarded;
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

	@JsonIgnore
	public String getPaymentDetail() {
		return paymentDetail;
	}

	public void setPaymentDetail(String paymentDetail) {
		this.paymentDetail = paymentDetail;
	}

	/**
	 * Whether a payment detail is on file — the only thing any screen is told about the
	 * encrypted field (invariant 4). Derived here rather than in a mapper so no DTO ever
	 * has to touch {@link #paymentDetail} to answer the question.
	 */
	public boolean hasPaymentDetail() {
		return paymentDetail != null && !paymentDetail.isBlank();
	}

	/**
	 * A stored tag no longer in the enum throws rather than being skipped.
	 *
	 * <p>Silently dropping it would make an expert's disciplines quietly narrower than
	 * their row says, which Unit 12 would then score on. V18's CHECK means such a row
	 * cannot be written in the first place, so a throw here is a genuine "the
	 * vocabulary was narrowed without a migration" signal.
	 */
	private static <E extends Enum<E>> List<E> values(String[] stored, Class<E> type) {
		return stored == null ? List.of() : Stream.of(stored).map(name -> Enum.valueOf(type, name)).toList();
	}

	/** Null and empty are both stored as NULL: an empty array is not a distinct state here. */
	private static String[] names(List<? extends Enum<?>> values) {
		return values == null || values.isEmpty() ? null : values.stream().map(Enum::name).toArray(String[]::new);
	}

	public String getExpertCode() {
		return expertCode;
	}

	public void setExpertCode(String expertCode) {
		this.expertCode = expertCode;
	}

	public String getSubSpecialization() {
		return subSpecialization;
	}

	public void setSubSpecialization(String subSpecialization) {
		this.subSpecialization = subSpecialization;
	}

	public String getHighestDegree() {
		return highestDegree;
	}

	public void setHighestDegree(String highestDegree) {
		this.highestDegree = highestDegree;
	}

	public String getDegreeField() {
		return degreeField;
	}

	public void setDegreeField(String degreeField) {
		this.degreeField = degreeField;
	}

	public String getDegreeInstitution() {
		return degreeInstitution;
	}

	public void setDegreeInstitution(String degreeInstitution) {
		this.degreeInstitution = degreeInstitution;
	}

	public String getCurrentPosition() {
		return currentPosition;
	}

	public void setCurrentPosition(String currentPosition) {
		this.currentPosition = currentPosition;
	}

	public AffiliationType getAffiliationType() {
		return affiliationType;
	}

	public void setAffiliationType(AffiliationType affiliationType) {
		this.affiliationType = affiliationType;
	}

	public String getCountry() {
		return country;
	}

	public void setCountry(String country) {
		this.country = country;
	}

	public String getStateRegion() {
		return stateRegion;
	}

	public void setStateRegion(String stateRegion) {
		this.stateRegion = stateRegion;
	}

	public Integer getYearsExperience() {
		return yearsExperience;
	}

	public void setYearsExperience(Integer yearsExperience) {
		this.yearsExperience = yearsExperience;
	}

	public String getLinkedinUrl() {
		return linkedinUrl;
	}

	public void setLinkedinUrl(String linkedinUrl) {
		this.linkedinUrl = linkedinUrl;
	}

	public Integer getPublications() {
		return publications;
	}

	public void setPublications(Integer publications) {
		this.publications = publications;
	}

	public Integer getCitations() {
		return citations;
	}

	public void setCitations(Integer citations) {
		this.citations = citations;
	}

	public Integer getHIndex() {
		return hIndex;
	}

	public void setHIndex(Integer hIndex) {
		this.hIndex = hIndex;
	}

	public Integer getPatents() {
		return patents;
	}

	public void setPatents(Integer patents) {
		this.patents = patents;
	}

	public String getNotableAwards() {
		return notableAwards;
	}

	public void setNotableAwards(String notableAwards) {
		this.notableAwards = notableAwards;
	}

	public String getProfessionalMemberships() {
		return professionalMemberships;
	}

	public void setProfessionalMemberships(String professionalMemberships) {
		this.professionalMemberships = professionalMemberships;
	}

	public String getEditorialRoles() {
		return editorialRoles;
	}

	public void setEditorialRoles(String editorialRoles) {
		this.editorialRoles = editorialRoles;
	}

	public String getLanguages() {
		return languages;
	}

	public void setLanguages(String languages) {
		this.languages = languages;
	}

	public Integer getAvgTurnaroundDays() {
		return avgTurnaroundDays;
	}

	public void setAvgTurnaroundDays(Integer avgTurnaroundDays) {
		this.avgTurnaroundDays = avgTurnaroundDays;
	}

	public List<VisaCategory> getVisaCategories() {
		return values(visaCategories, VisaCategory.class);
	}

	public void setVisaCategories(List<VisaCategory> visaCategories) {
		this.visaCategories = names(visaCategories);
	}

	public boolean isRushAvailable() {
		return rushAvailable;
	}

	public void setRushAvailable(boolean rushAvailable) {
		this.rushAvailable = rushAvailable;
	}
}
