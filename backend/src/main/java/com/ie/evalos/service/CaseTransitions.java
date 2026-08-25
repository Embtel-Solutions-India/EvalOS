package com.ie.evalos.service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.event.CaseEvents;

/**
 * The declared transition table, and the only place that decides whether a case
 * may move. A {@code (from, action)} pair absent from here is illegal — the table
 * is a whitelist, so a new action is unreachable until it is declared, which is
 * the safe direction to fail.
 *
 * <p>Exception states are not extra stages. A case in one holds its stage and
 * accepts nothing but the action that clears it, which is how "exception ↔ prior
 * stage" works without a column remembering the prior stage: it never left it.
 */
public final class CaseTransitions {

	/**
	 * One action, one service method, one domain event, one audit action. The event
	 * and audit action live here rather than at the call site so a transition cannot
	 * be recorded as one thing and published as another.
	 */
	public enum Action {

		ASSIGN_PM(CaseEvents.Type.PM_ASSIGNED, AuditAction.ASSIGNED),
		ASSIGN_COORDINATOR(CaseEvents.Type.COORDINATOR_ASSIGNED, AuditAction.ASSIGNED),
		MARK_DOCS_COMPLETE(CaseEvents.Type.DOCUMENTS_COMPLETED, AuditAction.STAGE_CHANGED),
		ASSIGN_CASE_MANAGER(CaseEvents.Type.EXPERT_ASSIGNED, AuditAction.ASSIGNED),
		SUBMIT_DRAFT(CaseEvents.Type.DRAFT_SUBMITTED, AuditAction.UPDATED),
		PM_RETURN_DRAFT(CaseEvents.Type.DRAFT_RETURNED, AuditAction.UPDATED),
		PM_APPROVE_DRAFT(CaseEvents.Type.DRAFT_PM_APPROVED, AuditAction.UPDATED),
		SEND_DRAFT_TO_CLIENT(CaseEvents.Type.DRAFT_READY_FOR_CLIENT, AuditAction.UPDATED),
		// Its own audit action rather than UPDATED, so the Case Manager's client-revision rate has
		// something to count. See AuditAction.CLIENT_REVISION_REQUESTED for why the figure is
		// forward-looking rather than retrospective.
		CLIENT_REQUEST_REVISIONS(CaseEvents.Type.DRAFT_REVISION_REQUESTED,
				AuditAction.CLIENT_REVISION_REQUESTED),
		CLIENT_APPROVE_DRAFT(CaseEvents.Type.DRAFT_CLIENT_APPROVED, AuditAction.STAGE_CHANGED),
		EXPERT_SIGNED(CaseEvents.Type.EXPERT_SIGNED, AuditAction.UPDATED),
		EXPERT_DECLINED(CaseEvents.Type.EXPERT_DECLINED, AuditAction.UPDATED),
		REASSIGN_EXPERT(CaseEvents.Type.EXPERT_ASSIGNED, AuditAction.ASSIGNED),
		PM_QC_APPROVE(CaseEvents.Type.QC_APPROVED, AuditAction.STAGE_CHANGED),
		DELIVER_TO_CLIENT(CaseEvents.Type.CASE_DELIVERED, AuditAction.UPDATED),
		CONFIRM_RECEIPT_AND_CLOSE(CaseEvents.Type.CASE_CLOSED, AuditAction.STAGE_CHANGED),
		PUT_ON_HOLD(CaseEvents.Type.CASE_ON_HOLD, AuditAction.UPDATED),
		RESUME_FROM_HOLD(CaseEvents.Type.CASE_RESUMED, AuditAction.UPDATED),
		REQUEST_REFUND(CaseEvents.Type.CASE_REFUND_REQUESTED, AuditAction.UPDATED),
		APPROVE_REFUND(CaseEvents.Type.CASE_REFUNDED, AuditAction.STAGE_CHANGED),
		DENY_REFUND(CaseEvents.Type.CASE_REFUND_DENIED, AuditAction.UPDATED);

		private final CaseEvents.Type event;
		private final AuditAction auditAction;

		Action(CaseEvents.Type event, AuditAction auditAction) {
			this.event = event;
			this.auditAction = auditAction;
		}

		public CaseEvents.Type event() {
			return event;
		}

		public AuditAction auditAction() {
			return auditAction;
		}
	}

	/** Every stage a case is still being worked in. */
	private static final EnumSet<Stage> ACTIVE = EnumSet.complementOf(EnumSet.of(Stage.CLOSED));

	/** Actions legal only while the case holds a particular exception state. */
	private static final Map<Action, ExceptionState> REQUIRES_EXCEPTION = Map.of(
			Action.RESUME_FROM_HOLD, ExceptionState.ON_HOLD_AWAITING_CLIENT,
			Action.REASSIGN_EXPERT, ExceptionState.EXPERT_DECLINED_REMATCHING,
			Action.APPROVE_REFUND, ExceptionState.REFUND_REQUESTED,
			Action.DENY_REFUND, ExceptionState.REFUND_REQUESTED);

	/** from stage → action → stage it lands on. */
	private static final Map<Stage, Map<Action, Stage>> TABLE = new EnumMap<>(Stage.class);

	static {
		declare(Stage.DOC_COLLECTION, Action.MARK_DOCS_COMPLETE, Stage.EXPERT_ASSIGNMENT);
		declare(Stage.EXPERT_ASSIGNMENT, Action.ASSIGN_CASE_MANAGER, Stage.DRAFT_GENERATION);

		// The draft/PM/client loops turn inside DRAFT_GENERATION and do not move the case.
		for (Action loop : List.of(Action.SUBMIT_DRAFT, Action.PM_RETURN_DRAFT, Action.PM_APPROVE_DRAFT,
				Action.SEND_DRAFT_TO_CLIENT, Action.CLIENT_REQUEST_REVISIONS)) {
			declare(Stage.DRAFT_GENERATION, loop, Stage.DRAFT_GENERATION);
		}
		declare(Stage.DRAFT_GENERATION, Action.CLIENT_APPROVE_DRAFT, Stage.EXPERT_SIGNING);

		declare(Stage.EXPERT_SIGNING, Action.EXPERT_SIGNED, Stage.EXPERT_SIGNING);
		declare(Stage.EXPERT_SIGNING, Action.EXPERT_DECLINED, Stage.EXPERT_SIGNING);
		declare(Stage.EXPERT_SIGNING, Action.PM_QC_APPROVE, Stage.FINAL_DELIVERY);

		declare(Stage.FINAL_DELIVERY, Action.DELIVER_TO_CLIENT, Stage.FINAL_DELIVERY);
		declare(Stage.FINAL_DELIVERY, Action.CONFIRM_RECEIPT_AND_CLOSE, Stage.CLOSED);

		// Legal wherever the case is still being worked, and stage-preserving. MARK_PAID
		// used to be declared here, on the grounds that payment clearing late is a
		// bookkeeping reality; v2.0 removed the action entirely — a case cannot exist
		// before the money any more, so there is no late payment to record.
		for (Stage active : ACTIVE) {
			declare(active, Action.ASSIGN_PM, active);
			// A Coordinator can be put on a case at any point it is still being worked —
			// they chase documents early and drive delivery late, and a case that changed
			// hands mid-pipeline must not need a stage rewind to be re-staffed.
			declare(active, Action.ASSIGN_COORDINATOR, active);
			declare(active, Action.PUT_ON_HOLD, active);
			declare(active, Action.REQUEST_REFUND, active);
		}
	}

	private CaseTransitions() {
	}

	private static void declare(Stage from, Action action, Stage to) {
		TABLE.computeIfAbsent(from, stage -> new EnumMap<>(Action.class)).put(action, to);
	}

	/**
	 * The stage this action lands the case on — the same stage for the ones that
	 * turn in place.
	 *
	 * @throws IllegalTransitionException if the pair is not declared for the state
	 *                                    the case is actually in
	 */
	public static Stage target(Case subject, Action action) {
		Stage from = subject.getCurrentStage();
		ExceptionState exception = subject.getExceptionState();

		ExceptionState required = REQUIRES_EXCEPTION.get(action);
		if (required != null) {
			if (exception != required) {
				throw illegal(from, exception, action);
			}
			return switch (action) {
				// Rematching starts over at assignment; an approved refund ends the case.
				case REASSIGN_EXPERT -> Stage.EXPERT_ASSIGNMENT;
				case APPROVE_REFUND -> Stage.CLOSED;
				// Resume and deny put the case back where it never stopped being.
				default -> from;
			};
		}

		// A case sitting in an exception state accepts nothing but its way out. One
		// exception at a time: a case on hold is resumed before a refund is asked for.
		if (exception != ExceptionState.NONE) {
			throw illegal(from, exception, action);
		}

		Stage to = TABLE.getOrDefault(from, Map.of()).get(action);
		if (to == null) {
			throw illegal(from, exception, action);
		}
		return to;
	}

	private static IllegalTransitionException illegal(Stage from, ExceptionState exception, Action action) {
		return new IllegalTransitionException("%s is not declared from %s (exception state %s)"
				.formatted(action, from, exception));
	}
}
