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
		EXPERT_TIMED_OUT(CaseEvents.Type.EXPERT_TIMED_OUT, AuditAction.UPDATED),
		REASSIGN_EXPERT(CaseEvents.Type.EXPERT_ASSIGNED, AuditAction.ASSIGNED),
		PM_QC_APPROVE(CaseEvents.Type.QC_APPROVED, AuditAction.STAGE_CHANGED),
		/**
		 * The counterpart {@code PM_QC_APPROVE} never had (Unit 31).
		 *
		 * <p>Before this, a failed final QC had nowhere to go: the table declared approval and
		 * nothing else, so a signed letter the PM judged unacceptable was handled by conversation
		 * and the case sat in QC. This is the transition that catches a bad letter before a client
		 * sees it, which makes it the most valuable one in the table.
		 */
		PM_QC_FAIL(CaseEvents.Type.QC_FAILED, AuditAction.STAGE_CHANGED),
		/**
		 * The Case Manager sends the client-approved letter to the expert (Unit 31).
		 *
		 * <p><strong>This is what starts the signing SLA</strong>, and before it existed nobody
		 * sent anything: the case entered {@code EXPERT_SIGNING} automatically on client approval,
		 * so the 24-hour clock ran from the approval and an expert sent the letter two hours later
		 * was charged for those two hours. Making the send the transition fixes it with no column:
		 * {@code stage_entered_at} is the send time.
		 */
		SEND_TO_EXPERT(CaseEvents.Type.EXPERT_SENT_FOR_SIGNING, AuditAction.UPDATED),
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
		declare(Stage.DOC_COLLECTION, Action.MARK_DOCS_COMPLETE, Stage.PM_REVIEW);
		declare(Stage.PM_REVIEW, Action.ASSIGN_CASE_MANAGER, Stage.DRAFT_IN_PROGRESS);

		// The draft loop. Every revision lands back on DRAFT_IN_PROGRESS because that is the Case
		// Manager's stage and a revision is always CM work — from a PM return, from a client's
		// revision request, and from a failed QC.
		declare(Stage.DRAFT_IN_PROGRESS, Action.SUBMIT_DRAFT, Stage.DRAFT_REVIEW);
		declare(Stage.DRAFT_REVIEW, Action.PM_RETURN_DRAFT, Stage.DRAFT_IN_PROGRESS);
		declare(Stage.DRAFT_REVIEW, Action.PM_APPROVE_DRAFT, Stage.READY_TO_SEND);

		// **The send is the boundary.** READY_TO_SEND is the Coordinator holding an approved draft;
		// CLIENT_REVIEW begins when they send it, so the client's 48-hour budget cannot start
		// while the draft is still sitting with us.
		declare(Stage.READY_TO_SEND, Action.SEND_DRAFT_TO_CLIENT, Stage.CLIENT_REVIEW);
		declare(Stage.CLIENT_REVIEW, Action.CLIENT_REQUEST_REVISIONS, Stage.DRAFT_IN_PROGRESS);
		declare(Stage.CLIENT_REVIEW, Action.CLIENT_APPROVE_DRAFT, Stage.CLIENT_APPROVAL);

		// Same shape one stage later: CLIENT_APPROVAL is the CM holding a locked, client-approved
		// letter, and EXPERT_SIGNING begins when they send it to the expert.
		declare(Stage.CLIENT_APPROVAL, Action.SEND_TO_EXPERT, Stage.EXPERT_SIGNING);

		// Signed moves the case on; declined and timed out hold the stage and raise the exception
		// that opens a rematch.
		declare(Stage.EXPERT_SIGNING, Action.EXPERT_SIGNED, Stage.FINAL_QC);
		declare(Stage.EXPERT_SIGNING, Action.EXPERT_DECLINED, Stage.EXPERT_SIGNING);
		declare(Stage.EXPERT_SIGNING, Action.EXPERT_TIMED_OUT, Stage.EXPERT_SIGNING);

		declare(Stage.FINAL_QC, Action.PM_QC_APPROVE, Stage.READY_TO_DELIVER);
		declare(Stage.FINAL_QC, Action.PM_QC_FAIL, Stage.DRAFT_IN_PROGRESS);

		declare(Stage.READY_TO_DELIVER, Action.DELIVER_TO_CLIENT, Stage.DELIVERED);
		declare(Stage.DELIVERED, Action.CONFIRM_RECEIPT_AND_CLOSE, Stage.CLOSED);

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
				// **A rematch returns to CLIENT_APPROVAL, not to assignment.** The letter is
				// already written, already approved by the client and already locked — nothing
				// about the draft changes because an expert walked away. What has to happen next
				// is the CM sending it to the replacement, and that send is the transition into
				// EXPERT_SIGNING, which restarts the signing clock for free. Sending it back to
				// PM_REVIEW would ask a PM to re-approve work nobody touched.
				case REASSIGN_EXPERT -> Stage.CLIENT_APPROVAL;
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
