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

		PM_ASSIGNED("case.pm_assigned"),
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
		CASE_REFUND_DENIED("case.refund_denied");

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
