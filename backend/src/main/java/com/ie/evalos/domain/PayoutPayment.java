package com.ie.evalos.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One transfer that left the bank, covering however many delivered drafts it covered.
 *
 * <p>EvalOS did not send it. Somebody sent it through Zelle or whatever else, outside
 * this system, and recorded it here — so {@code method} and {@code reference} are
 * whatever they wrote down, and there is no rail behind either.
 *
 * <p><b>{@code amount}, {@code paidDate} and {@code expertId} are {@code updatable =
 * false}.</b> Correcting any of them means the payment as recorded did not happen,
 * which is a void-and-re-record rather than an edit. {@code method}, {@code reference}
 * and {@code notes} stay editable while unconfirmed, because somebody types a
 * reference wrong and has to fix it.
 */
@Entity
@Table(name = "payout_payment")
public class PayoutPayment extends ScopedEntity {

	@Column(name = "expert_id", nullable = false, updatable = false)
	private UUID expertId;

	@Column(name = "amount", nullable = false, updatable = false)
	private BigDecimal amount;

	@Column(name = "currency", nullable = false, updatable = false)
	private String currency;

	@Column(name = "method", nullable = false)
	private String method;

	@Column(name = "reference", nullable = false)
	private String reference;

	@Column(name = "paid_date", nullable = false, updatable = false)
	private Instant paidDate;

	@Column(name = "notes")
	private String notes;

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	/** The staff member who recorded that the money went out. */
	@Column(name = "recorded_by", nullable = false, updatable = false)
	private UUID recordedBy;

	protected PayoutPayment() {
		// for JPA
	}

	public PayoutPayment(UUID brandId, UUID expertId, BigDecimal amount, String currency, String method,
			String reference, Instant paidDate, String notes, UUID recordedBy) {
		super(brandId);
		this.expertId = expertId;
		this.amount = amount;
		this.currency = currency;
		this.method = method;
		this.reference = reference;
		this.paidDate = paidDate;
		this.notes = notes;
		this.recordedBy = recordedBy;
	}

	public UUID getExpertId() {
		return expertId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public String getReference() {
		return reference;
	}

	public void setReference(String reference) {
		this.reference = reference;
	}

	public Instant getPaidDate() {
		return paidDate;
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

	public Instant getConfirmedAt() {
		return confirmedAt;
	}

	public void setConfirmedAt(Instant confirmedAt) {
		this.confirmedAt = confirmedAt;
	}

	public UUID getRecordedBy() {
		return recordedBy;
	}
}
