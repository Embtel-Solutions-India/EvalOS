package com.ie.evalos.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

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

	/** Taxonomy tags matching draws on (Unit 11). */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "primary_fields")
	private String[] primaryFields;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "secondary_fields")
	private String[] secondaryFields;

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

	/** Unit 09's expert card. Matching preference reads it properly in Unit 11. */
	public ExpertTier getTier() {
		return tier;
	}

	/** Set by Unit 11's roster screen; today only tests and seed data write it. */
	public void setAvailability(Availability availability) {
		this.availability = availability;
	}

	public Availability getAvailability() {
		return availability;
	}

	@JsonIgnore
	public String getPaymentDetail() {
		return paymentDetail;
	}

	public void setPaymentDetail(String paymentDetail) {
		this.paymentDetail = paymentDetail;
	}
}
