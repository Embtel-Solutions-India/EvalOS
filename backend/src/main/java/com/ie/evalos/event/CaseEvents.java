package com.ie.evalos.event;

import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Stage;

/**
 * The internal domain event every case transition publishes, and the catalog of
 * its types. Unit 04 only publishes: the in-app notification centre (Unit 06) and
 * the outbound webhook dispatcher (Unit 18) subscribe later.
 *
 * <p>The payload carries brand/case/contact/attribution references and the stage —
 * nothing else. Never the deal value, never {@code payment_detail}, never a note
 * or a reason a member typed (invariants 4 and 11). Those belong in the audit
 * trail, which is internal, not in an event that leaves the building.
 */
public final class CaseEvents {

	/**
	 * The wire name is the contract an external subscriber sees; the enum constant
	 * is what code refers to. The catalog is open — a later unit adds a type here
	 * rather than passing a loose string.
	 */
	public enum Type {

		/**
		 * A won GHL opportunity became a case, and <strong>this is the pool arrival</strong> —
		 * the case is already paid, because GHL invoices and collects before marking an
		 * opportunity Won (Case Creation v2.0). Published only on intake's create path, never
		 * on a refresh.
		 */
		CASE_CREATED("case.created"),
		/**
		 * <strong>Dead: nothing publishes this, and nothing should subscribe to it.</strong> It
		 * was the pool arrival while a case could exist before its money and a staff member
		 * recorded payment by hand; Case Creation v2.0 deleted that path, so payment is not a
		 * separate event any more — {@link #CASE_CREATED} already means paid. Kept as a constant
		 * only because the wire name is persisted on rows already written. Unit 18 must not wire
		 * this as the payment signal: it will never fire.
		 *
		 * <p>The {@code paid} <em>flag</em> is very much alive and is still one of the two facts
		 * revenue recognition needs, with {@link #CASE_DELIVERED} the other and neither
		 * sufficient alone (invariant 5) — see {@code RefundService.isRevenueRecognized}.
		 */
		CASE_PAID("case.paid"),
		/** Tells GHL to send the client their document checklist (Unit 18). */
		CHECKLIST_REQUESTED("checklist.requested"),
		/**
		 * Chase the client for the documents still outstanding. Raised manually by the
		 * Coordinator (Unit 10) and on the 24h/48h timers (Unit 19) — the same event either
		 * way, because GHL delivers the message and does not care which one asked.
		 * EvalOS sends no mail itself (invariant 14).
		 */
		CHECKLIST_REMINDER("checklist.reminder"),
		/**
		 * A case still short of its documents after three business days.
		 *
		 * <p>Declared here and published by nothing yet: Unit 19 owns the timer, and Unit 10
		 * owns the contract it fires against. Unlike {@link #CHECKLIST_REMINDER} this one is
		 * inward — an in-app alert to the PM and Brand Manager, not a client message.
		 */
		DOCS_ESCALATION_DAY3("docs.escalation.day3"),
		PM_ASSIGNED("case.pm_assigned"),
		COORDINATOR_ASSIGNED("case.coordinator_assigned"),
		DOCUMENTS_COMPLETED("documents.completed"),
		EXPERT_ASSIGNED("expert.assigned"),
		DRAFT_SUBMITTED("draft.submitted"),
		DRAFT_RETURNED("draft.returned"),
		DRAFT_PM_APPROVED("draft.pm_approved"),
		DRAFT_READY_FOR_CLIENT("draft.ready_for_client"),
		DRAFT_REVISION_REQUESTED("draft.revision_requested"),
		DRAFT_CLIENT_APPROVED("draft.client_approved"),
		EXPERT_SIGNED("expert.signed"),
		EXPERT_DECLINED("expert.declined"),
		QC_APPROVED("qc.approved"),
		CASE_DELIVERED("case.delivered"),
		CASE_CLOSED("case.closed"),
		CASE_ON_HOLD("case.on_hold"),
		CASE_RESUMED("case.resumed"),
		CASE_REFUND_REQUESTED("case.refund_requested"),
		CASE_REFUNDED("case.refunded"),
		CASE_REFUND_DENIED("case.refund_denied"),
		/**
		 * A Case Manager escalated a blocked case to the Project Managers on its brand (Unit 22,
		 * slice 3).
		 *
		 * <p><strong>Not a lifecycle transition</strong> — the case does not move and its stage
		 * clock is not restamped. It is here because it needs the same delivery the transitions
		 * get: {@code NotificationListeners.ROUTES} is what turns it into an in-app alert for the
		 * right people, and routing it any other way would be a second notification path.
		 */
		CASE_FLAGGED_TO_PM("case.flagged_to_pm");

		private final String wireName;

		Type(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}
	}

	/** One transition that happened, as the rest of the system sees it. */
	public record CaseEvent(
			Type type,
			UUID brandId,
			UUID caseId,
			UUID contactId,
			String campaignAttribution,
			Stage stage) {

		public static CaseEvent of(Type type, Case subject) {
			return new CaseEvent(type, subject.getBrandId(), subject.getId(), subject.getContactId(),
					subject.getCampaignAttribution(), subject.getCurrentStage());
		}
	}

	private CaseEvents() {
	}
}
