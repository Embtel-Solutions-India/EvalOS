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

	@Column(name = "total_cases_completed", nullable = false)
	private int totalCasesCompleted;

	@Column(name = "current_active_count", nullable = false)
	private int currentActiveCount;

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

	public BigDecimal getQualityScore() {
		return qualityScore;
	}

	public void setQualityScore(BigDecimal qualityScore) {
		this.qualityScore = qualityScore;
	}

	public BigDecimal getAvgResponseHours() {
		return avgResponseHours;
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
}
