package com.ie.evalos.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One delivered draft an expert is owed for, opened when the case is delivered.
 *
 * <p><b>How it was paid lives on {@link PayoutPayment}, not here</b> (Unit 16b). The
 * expert charges per draft and is paid weekly, so one transfer settles several of
 * these rows and carries one method, one reference and one date. Three copies of that
 * string is three chances to fumble a digit and three dates that can disagree.
 */
@Entity
@Table(name = "payout_ledger")
public class PayoutLedger extends ScopedEntity {

	@Column(name = "case_id", updatable = false)
	private UUID caseId;

	@Column(name = "expert_id", updatable = false)
	private UUID expertId;

	/**
	 * Null means "not decided yet" — an expert with no {@code standard_fee} gets a row
	 * the form makes somebody fill in. A prefill of 0 would be a number somebody could
	 * settle without noticing.
	 */
	@Column(name = "amount")
	private BigDecimal amount;

	@Column(name = "currency", nullable = false)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private PayoutStatus status;

	@Column(name = "due_date")
	private Instant dueDate;

	/** The transfer that settled this row, or null while it is still PENDING. */
	@Column(name = "payment_id")
	private UUID paymentId;

	/** The staff member who last recorded something about this row. */
	@Column(name = "recorded_by")
	private UUID recordedBy;

	protected PayoutLedger() {
		// for JPA
	}

	public PayoutLedger(UUID brandId, UUID caseId, UUID expertId, BigDecimal amount, String currency,
			Instant dueDate) {
		super(brandId);
		this.caseId = caseId;
		this.expertId = expertId;
		this.amount = amount;
		this.currency = currency;
		this.dueDate = dueDate;
		this.status = PayoutStatus.PENDING;
	}

	public UUID getCaseId() {
		return caseId;
	}

	public UUID getExpertId() {
		return expertId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	public String getCurrency() {
		return currency;
	}

	public PayoutStatus getStatus() {
		return status;
	}

	/** Written by the refund path (Unit 04) to void a pending row, and by Unit 16b. */
	public void setStatus(PayoutStatus status) {
		this.status = status;
	}

	public Instant getDueDate() {
		return dueDate;
	}

	public UUID getPaymentId() {
		return paymentId;
	}

	public UUID getRecordedBy() {
		return recordedBy;
	}

	public void setRecordedBy(UUID recordedBy) {
		this.recordedBy = recordedBy;
	}
}
