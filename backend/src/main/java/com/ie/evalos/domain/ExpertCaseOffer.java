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
 * One offer of one case to one expert, and what became of it.
 *
 * <p><strong>Why the row exists.</strong> Acceptance rate is one of the four factors
 * {@code ExpertMatchService} scores on, and nothing in EvalOS could answer it: the decline is
 * in the audit trail inside a jsonb snapshot, and {@code evalos_case.expert_id} is overwritten
 * by a reassignment. This table is the aggregable form of a fact the trail already records —
 * not a second history, a queryable projection of one.
 *
 * <p><strong>Append-only in spirit, two mutable facts in fact.</strong> {@link #outcome} moves
 * off {@link OfferOutcome#OFFERED} exactly once, through {@link #resolve}, and the {@link #fee}
 * may change only while it is still {@code OFFERED} (Unit 65); every other column is
 * {@code updatable = false}. The rows are written by the transitions that already exist —
 * {@code assignCaseManager} and {@code reassignExpert} open one, {@code expertDeclined} and
 * {@code expertSigned} close it — inside those transactions, so an offer and the transition
 * that caused it commit together or not at all.
 */
@Entity
@Table(name = "expert_case_offer")
public class ExpertCaseOffer extends ScopedEntity {

	@Column(name = "case_id", nullable = false, updatable = false)
	private UUID caseId;

	@Column(name = "expert_id", nullable = false, updatable = false)
	private UUID expertId;

	@Column(name = "offered_at", nullable = false, updatable = false)
	private Instant offeredAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "outcome", nullable = false)
	private OfferOutcome outcome;

	@Column(name = "outcome_at")
	private Instant outcomeAt;

	@Column(name = "decline_reason")
	private String declineReason;

	/**
	 * What this case pays the expert, in the brand's currency (Unit 65). Nullable only for offers
	 * closed before V79 and for an open one whose expert had no standard fee at migration time;
	 * every offer made since is priced by {@code OfferFees.price}.
	 */
	@Column(name = "fee")
	private BigDecimal fee;

	/** Who set the current {@link #fee}; null when the migration did. The history is in audit_event. */
	@Column(name = "fee_set_by")
	private UUID feeSetBy;

	@Column(name = "fee_set_at")
	private Instant feeSetAt;

	protected ExpertCaseOffer() {
		// for JPA
	}

	/** A fresh, unanswered offer. The only state a row is ever created in. */
	public ExpertCaseOffer(UUID brandId, UUID caseId, UUID expertId) {
		super(brandId);
		this.caseId = caseId;
		this.expertId = expertId;
		this.offeredAt = Instant.now();
		this.outcome = OfferOutcome.OFFERED;
	}

	/**
	 * Moves this offer off {@code OFFERED}, once.
	 *
	 * <p><strong>First write wins, and a second is a no-op rather than an error.</strong> Unit
	 * 15 has two acts that both mean accepted — the expert pressing Accept in the portal, then
	 * Dropbox Sign's {@code signed} callback — and on the ordinary happy path both fire.
	 * Throwing would turn a normal sequence into a failed transition. The guard lives here, in
	 * the one place that owns the column, rather than in each of the four callers.
	 *
	 * <p>A <em>different</em> later outcome is swallowed too, not just a repeat: staff
	 * recording a timeout and the real signature landing afterwards is the same shape of race,
	 * and the audit trail records both acts either way. What this row holds is the first
	 * answer, which is the one the acceptance rate should be built on.
	 *
	 * <p>{@code OFFERED} is not a resolution. Accepting it would set {@link #outcomeAt} while
	 * leaving the outcome open, which is precisely the disagreement V19's
	 * {@code expert_case_offer_outcome_dated} exists to forbid — and it would surface at flush,
	 * as a 500 rolling back an otherwise valid transition, rather than here at the call.
	 *
	 * @return whether this call was the one that resolved it, so the caller knows whether to save
	 */
	public boolean resolve(OfferOutcome resolution, String reason) {
		if (resolution == OfferOutcome.OFFERED) {
			throw new IllegalStateException("OFFERED is not a resolution");
		}
		if (outcome != OfferOutcome.OFFERED) {
			return false;
		}
		outcome = resolution;
		outcomeAt = Instant.now();
		declineReason = reason;
		return true;
	}

	public UUID getCaseId() {
		return caseId;
	}

	public UUID getExpertId() {
		return expertId;
	}

	public Instant getOfferedAt() {
		return offeredAt;
	}

	public OfferOutcome getOutcome() {
		return outcome;
	}

	public Instant getOutcomeAt() {
		return outcomeAt;
	}

	public String getDeclineReason() {
		return declineReason;
	}

	/**
	 * Sets the amount, only while nobody has answered (Unit 65 rule 3): an accepted fee is the
	 * agreed price, and a declined or superseded offer has nothing left to price.
	 */
	public void setFee(BigDecimal fee, UUID actor) {
		if (outcome != OfferOutcome.OFFERED) {
			throw new IllegalTransitionException("This offer is " + outcome + " and its fee is final");
		}
		this.fee = fee;
		this.feeSetBy = actor;
		this.feeSetAt = Instant.now();
	}

	public BigDecimal getFee() {
		return fee;
	}

	public UUID getFeeSetBy() {
		return feeSetBy;
	}

	public Instant getFeeSetAt() {
		return feeSetAt;
	}
}
