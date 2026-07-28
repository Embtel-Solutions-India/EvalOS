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
 * One expert payout, recorded by hand. EvalOS tracks that money was owed and
 * paid; it never moves money and has no disbursement rail, so {@code method} and
 * {@code reference} are whatever the person filling the form wrote down.
 */
@Entity
@Table(name = "payout_ledger")
public class PayoutLedger extends ScopedEntity {

	@Column(name = "case_id")
	private UUID caseId;

	@Column(name = "expert_id")
	private UUID expertId;

	@Column(name = "amount")
	private BigDecimal amount;

	@Column(name = "currency")
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(name = "status")
	private PayoutStatus status;

	@Column(name = "method")
	private String method;

	@Column(name = "reference")
	private String reference;

	@Column(name = "due_date")
	private Instant dueDate;

	@Column(name = "paid_date")
	private Instant paidDate;

	/** The staff member who recorded the row, for accountability. */
	@Column(name = "recorded_by")
	private UUID recordedBy;

	protected PayoutLedger() {
		// for JPA
	}
}
