package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.CaseTransitions.Action;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The refund path, split out from the rest of the state machine because it is the
 * one transition that touches money: it reverses revenue recognition and voids a
 * payout somebody is expecting.
 *
 * <p>GM-only, checked here as well as at the endpoint. A method-security
 * annotation guards one route; this guards the operation, so a later caller — a
 * job, a webhook handler, another service — cannot reach it as anyone else.
 *
 * <p><b>How "refunded" is stored.</b> There is no refunded column and Unit 04 adds
 * no migration, so an approved refund leaves the case at {@code CLOSED} while
 * keeping {@code exception_state = REFUND_REQUESTED}. That pair *is* the refunded
 * flag, and {@link #isRevenueRecognized(Case)} is the one reading of it — every
 * dashboard that sums delivered value goes through it (invariant 5).
 */
@Service
public class RefundService {

	private final CaseLifecycleService lifecycle;
	private final PayoutLedgerRepository payouts;

	RefundService(CaseLifecycleService lifecycle, PayoutLedgerRepository payouts) {
		this.lifecycle = lifecycle;
		this.payouts = payouts;
	}

	/**
	 * Approve: void every pending payout for the case, close it flagged refunded, and
	 * publish {@code case.refunded} for Unit 18 to relay to GHL. Delivered value stops
	 * counting as earned the moment this commits.
	 */
	@Transactional
	public Case approveRefund(UUID caseId) {
		requireGm();
		Case subject = lifecycle.load(caseId);
		Stage to = CaseTransitions.target(subject, Action.APPROVE_REFUND);

		List<PayoutLedger> pending = payouts.findByCaseIdAndStatus(subject.getId(), PayoutStatus.PENDING);
		pending.forEach(payout -> payout.setStatus(PayoutStatus.VOIDED));
		payouts.saveAll(pending);

		// The exception state is deliberately left at REFUND_REQUESTED: on a CLOSED
		// case that is what marks it refunded rather than delivered.
		return lifecycle.apply(subject, to, Action.APPROVE_REFUND, null,
				c -> c.setCaseClosedDate(Instant.now()));
	}

	/** Deny: the case goes back to the stage it never actually left. */
	@Transactional
	public Case denyRefund(UUID caseId, String reason) {
		requireGm();
		Case subject = lifecycle.load(caseId);
		Stage to = CaseTransitions.target(subject, Action.DENY_REFUND);

		return lifecycle.apply(subject, to, Action.DENY_REFUND, reason,
				c -> c.setExceptionState(ExceptionState.NONE));
	}

	/**
	 * Whether this case's value counts as earned. Delivery is the sole recognition
	 * event and a GM-approved refund reverses it (invariant 5), so every revenue
	 * figure filters on this rather than on {@code delivery_date} alone.
	 */
	public static boolean isRevenueRecognized(Case subject) {
		// Paid as well as delivered: since Handoff A moved to contact intake, a case can
		// exist and even be worked without money behind it, so delivery alone no longer
		// implies earned.
		return subject.isPaid() && subject.getDeliveryDate() != null && !isRefunded(subject);
	}

	/**
	 * A closed case still carrying {@code REFUND_REQUESTED} is one whose refund the
	 * GM approved — a merely *requested* refund is not a reversal, so an open case in
	 * that state still counts as earned.
	 */
	public static boolean isRefunded(Case subject) {
		return subject.getCurrentStage() == Stage.CLOSED
				&& subject.getExceptionState() == ExceptionState.REFUND_REQUESTED;
	}

	private static void requireGm() {
		if (TenantContext.current().role() != Role.GM) {
			throw new ForbiddenException("Only the GM may rule on a refund");
		}
	}
}
