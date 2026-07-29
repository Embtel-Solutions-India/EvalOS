package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.PmApprovalStatus;
import com.ie.evalos.domain.PoolStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.CaseTransitions.Action;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The case state machine. Every stage change in EvalOS goes through one of these
 * methods, and each one does the same four things in one transaction: check the
 * transition is declared, check the stage guard, write the row, then record an
 * audit entry and publish a domain event. Nothing else moves a case.
 *
 * <p>Reads go through {@code findScoped}, so a case in another brand — or, for a
 * Case Manager, one that is not theirs — is simply not found. That answers 403
 * rather than 404 on purpose: whether a case id exists is itself another brand's
 * information.
 */
@Service
public class CaseLifecycleService {

	private static final String OBJECT_TYPE = "CASE";

	/**
	 * What the audit trail records either side of a transition: the state fields and
	 * the references, plus whatever reason the actor typed. Deliberately not the
	 * entity — a snapshot is a record of what the fields were, and the deal value and
	 * strategy notes are role-restricted, so they stay out of it.
	 */
	public record CaseSnapshot(
			Stage stage,
			ExceptionState exceptionState,
			PoolStatus poolStatus,
			UUID assignedPm,
			UUID assignedCm,
			UUID expertId,
			ExpertSignStatus expertSignStatus,
			PmApprovalStatus pmApprovalStatus,
			ClientApprovalStatus clientApprovalStatus,
			int draftVersionCount,
			SlaStatus slaStatus,
			boolean paid,
			String note) {

		static CaseSnapshot of(Case subject) {
			return of(subject, null);
		}

		static CaseSnapshot of(Case subject, String note) {
			return new CaseSnapshot(subject.getCurrentStage(), subject.getExceptionState(), subject.getPoolStatus(),
					subject.getAssignedPm(), subject.getAssignedCm(), subject.getExpertId(),
					subject.getExpertSignStatus(), subject.getPmApprovalStatus(), subject.getClientApprovalStatus(),
					subject.getDraftVersionCount(), subject.getSlaStatus(), subject.isPaid(), note);
		}
	}

	private final CaseRepository cases;
	private final DocumentChecklistItemRepository checklistItems;
	private final ExpertRepository experts;
	private final TeamMemberRepository teamMembers;
	private final AuditService audit;
	private final SlaCalculator sla;
	private final ApplicationEventPublisher events;

	CaseLifecycleService(CaseRepository cases, DocumentChecklistItemRepository checklistItems, ExpertRepository experts,
			TeamMemberRepository teamMembers, AuditService audit, SlaCalculator sla,
			ApplicationEventPublisher events) {
		this.cases = cases;
		this.checklistItems = checklistItems;
		this.experts = experts;
		this.teamMembers = teamMembers;
		this.audit = audit;
		this.sla = sla;
		this.events = events;
	}

	// --- reads ---------------------------------------------------------------

	/**
	 * The board read. SLA status is recomputed rather than read back from the row: the
	 * stored column is only as fresh as the last transition, and a case left sitting
	 * past its budget goes overdue without anything writing to it. The SLA filter is
	 * therefore applied after the refresh instead of in SQL.
	 */
	@Transactional(readOnly = true)
	public List<Case> list(Stage stage, SlaStatus slaStatus, Instant dueBefore) {
		List<Case> scoped = cases.findScoped(TenantContext.current(), stage, dueBefore);
		scoped.forEach(this::withCurrentSla);
		if (slaStatus == null) {
			return scoped;
		}
		return scoped.stream().filter(subject -> subject.getSlaStatus() == slaStatus).toList();
	}

	@Transactional(readOnly = true)
	public Case read(UUID caseId) {
		return withCurrentSla(load(caseId));
	}

	/**
	 * Restates the RAG status as of now on an in-memory row. The read transactions are
	 * readOnly, so this is not flushed back; if it ever were, the value written would
	 * be the same one the next transition computes, so it is harmless either way.
	 */
	private Case withCurrentSla(Case subject) {
		subject.setSlaStatus(sla.statusOf(subject));
		return subject;
	}

	// --- payment -------------------------------------------------------------

	/**
	 * Records what was actually taken. Handoff A no longer proves payment — a case is
	 * created from a GHL contact, before anyone has paid — so this is the fact that
	 * turns a lead into workable, recognisable business.
	 *
	 * <p>GM and Brand Manager only, re-checked here as well as at the endpoint. A method
	 * security annotation guards one route; this guards the operation, so a later caller
	 * — a job, a webhook handler, another service — cannot reach it as anyone else. The
	 * same reasoning as {@code RefundService}: this writes money.
	 *
	 * <p>**Callable on an already-paid case, deliberately.** {@code paid} and
	 * {@code paid_at} are write-once — the moment the money arrived does not change, and
	 * re-stamping it would lose it. The *amount* is a different matter: a case GHL
	 * reported as already paid carries the quote, because a quote is all the contact
	 * webhook knows, and somebody has to be able to replace it with the figure actually
	 * collected. Only ever one value, never a running total, so correcting it cannot
	 * double-count.
	 *
	 * <p>The pool alert is not raised here. Unit 06 listens for {@code case.paid} and
	 * announces the arrival once, however many times a correction re-publishes it.
	 */
	@Transactional
	public Case markPaid(UUID caseId, BigDecimal dealValue, String invoiceRef) {
		requirePaymentRole();
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.MARK_PAID);
		boolean firstPayment = !subject.isPaid();

		return apply(subject, to, Action.MARK_PAID, invoiceRef, c -> {
			c.setDealValue(dealValue);
			if (invoiceRef != null && !invoiceRef.isBlank()) {
				c.setInvoiceRef(invoiceRef);
			}
			if (firstPayment) {
				c.setPaid(true);
				c.setPaidAt(Instant.now());
			}
		});
	}

	private static void requirePaymentRole() {
		Role role = TenantContext.current().role();
		if (role != Role.GM && role != Role.BRAND_MANAGER) {
			throw new ForbiddenException("Only the GM or a Brand Manager may record a payment");
		}
	}

	// --- assignment ----------------------------------------------------------

	/** Pool → PM. Stamps the case with the PM's team, which is what opens it to that team. */
	@Transactional
	public Case assignPm(UUID caseId, UUID pmId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.ASSIGN_PM);
		requireState(subject.getPoolStatus() == PoolStatus.IN_POOL, "case has already left the pool");
		TeamMember pm = member(pmId, Role.PROJECT_MANAGER, subject.getBrandId());

		return apply(subject, to, Action.ASSIGN_PM, null, c -> {
			c.setAssignedPm(pm.getId());
			c.setTeamId(pm.getTeamId());
			c.setPoolStatus(PoolStatus.ASSIGNED);
		});
	}

	/** PM → CM, with the expert the CM will draft for. */
	@Transactional
	public Case assignCaseManager(UUID caseId, UUID cmId, UUID expertId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.ASSIGN_CASE_MANAGER);
		TeamMember cm = member(cmId, Role.CASE_MANAGER, subject.getBrandId());
		requireState(cm.getTeamId() != null && cm.getTeamId().equals(subject.getTeamId()),
				"case manager is not on this case's team");
		Expert expert = availableExpert(expertId);

		return apply(subject, to, Action.ASSIGN_CASE_MANAGER, null, c -> {
			c.setAssignedCm(cm.getId());
			c.setExpertId(expert.getId());
			c.setExpertSignStatus(ExpertSignStatus.PENDING);
		});
	}

	// --- document collection -------------------------------------------------

	@Transactional
	public Case markDocsComplete(UUID caseId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.MARK_DOCS_COMPLETE);
		// The one place unpaid work stops. Documents may be gathered from a lead — that
		// costs EvalOS nothing — but everything past here engages an expert, so it waits
		// for the money. Guarding here covers every later stage, because none of them is
		// reachable without passing through this transition.
		requireState(subject.isPaid(), "the case has not been paid");
		requireState(subject.getAssignedPm() != null, "no project manager is assigned yet");

		List<DocumentChecklistItem> items = checklistItems.findByCaseId(subject.getId());
		requireState(!items.isEmpty(), "the document checklist is empty");
		requireState(items.stream().allMatch(CaseLifecycleService::isComplete),
				"not every checklist item is uploaded or approved");

		return apply(subject, to, Action.MARK_DOCS_COMPLETE, null, c -> {
		});
	}

	// --- the draft loops, all inside DRAFT_GENERATION -------------------------

	@Transactional
	public Case submitDraft(UUID caseId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.SUBMIT_DRAFT);

		return apply(subject, to, Action.SUBMIT_DRAFT, null, c -> {
			c.setDraftVersionCount(c.getDraftVersionCount() + 1);
			c.setPmApprovalStatus(PmApprovalStatus.PENDING);
			// A new draft is not the draft the client already saw.
			c.setClientApprovalStatus(null);
		});
	}

	@Transactional
	public Case pmReturnDraft(UUID caseId, String comments) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.PM_RETURN_DRAFT);
		requireState(subject.getPmApprovalStatus() == PmApprovalStatus.PENDING, "no draft is awaiting PM review");

		return apply(subject, to, Action.PM_RETURN_DRAFT, comments,
				c -> c.setPmApprovalStatus(PmApprovalStatus.RETURNED));
	}

	@Transactional
	public Case pmApproveDraft(UUID caseId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.PM_APPROVE_DRAFT);
		requireState(subject.getPmApprovalStatus() == PmApprovalStatus.PENDING, "no draft is awaiting PM review");

		return apply(subject, to, Action.PM_APPROVE_DRAFT, null,
				c -> c.setPmApprovalStatus(PmApprovalStatus.APPROVED));
	}

	@Transactional
	public Case sendDraftToClient(UUID caseId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.SEND_DRAFT_TO_CLIENT);
		requireState(subject.getPmApprovalStatus() == PmApprovalStatus.APPROVED,
				"the draft has not been PM-approved");

		return apply(subject, to, Action.SEND_DRAFT_TO_CLIENT, null,
				c -> c.setClientApprovalStatus(ClientApprovalStatus.PENDING));
	}

	/** Called by the client portal (Unit 14) and by staff recording the answer. */
	@Transactional
	public Case clientRequestRevisions(UUID caseId, String notes) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.CLIENT_REQUEST_REVISIONS);
		requireState(subject.getClientApprovalStatus() == ClientApprovalStatus.PENDING,
				"no draft is with the client");

		return apply(subject, to, Action.CLIENT_REQUEST_REVISIONS, notes,
				c -> c.setClientApprovalStatus(ClientApprovalStatus.REVISION_REQUESTED));
	}

	/** Handoff B: the client approves and the case goes to the expert to sign. */
	@Transactional
	public Case clientApproveDraft(UUID caseId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.CLIENT_APPROVE_DRAFT);
		requireState(subject.getClientApprovalStatus() == ClientApprovalStatus.PENDING,
				"no draft is with the client");
		requireState(subject.getExpertId() != null, "no expert is on this case");

		return apply(subject, to, Action.CLIENT_APPROVE_DRAFT, null, c -> {
			c.setClientApprovalStatus(ClientApprovalStatus.APPROVED);
			c.setExpertSignStatus(ExpertSignStatus.PENDING);
		});
	}

	// --- expert signing ------------------------------------------------------

	/** Driven by the Dropbox Sign callback (Unit 15), or recorded by staff. */
	@Transactional
	public Case expertSigned(UUID caseId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.EXPERT_SIGNED);

		return apply(subject, to, Action.EXPERT_SIGNED, null, c -> c.setExpertSignStatus(ExpertSignStatus.SIGNED));
	}

	@Transactional
	public Case expertDeclined(UUID caseId, String reason) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.EXPERT_DECLINED);

		// The reason is the point of this transition, so it goes in the audit trail.
		return apply(subject, to, Action.EXPERT_DECLINED, reason,
				c -> c.setExceptionState(ExceptionState.EXPERT_DECLINED_REMATCHING));
	}

	@Transactional
	public Case reassignExpert(UUID caseId, UUID expertId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.REASSIGN_EXPERT);
		Expert replacement = availableExpert(expertId);
		requireState(!replacement.getId().equals(subject.getExpertId()),
				"that is the expert who declined");

		return apply(subject, to, Action.REASSIGN_EXPERT, null, c -> {
			c.setExpertId(replacement.getId());
			c.setExpertSignStatus(ExpertSignStatus.REASSIGNED);
			c.setExceptionState(ExceptionState.NONE);
		});
	}

	@Transactional
	public Case pmQcApprove(UUID caseId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.PM_QC_APPROVE);
		requireState(subject.getExpertSignStatus() == ExpertSignStatus.SIGNED, "the expert has not signed");

		return apply(subject, to, Action.PM_QC_APPROVE, null, c -> {
		});
	}

	// --- delivery and close --------------------------------------------------

	/**
	 * Handoff C. Delivery is one of the two facts revenue recognition needs — paid
	 * <em>and</em> delivered, per invariant 5 as restated when Handoff A moved to contact
	 * intake. Delivering an unpaid case recognizes nothing; see
	 * {@code RefundService.isRevenueRecognized}, which is the only place that reads the
	 * pair.
	 */
	@Transactional
	public Case deliverToClient(UUID caseId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.DELIVER_TO_CLIENT);
		requireState(subject.getDeliveryDate() == null, "the case has already been delivered");

		return apply(subject, to, Action.DELIVER_TO_CLIENT, null, c -> c.setDeliveryDate(Instant.now()));
	}

	@Transactional
	public Case confirmReceiptAndClose(UUID caseId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.CONFIRM_RECEIPT_AND_CLOSE);
		requireState(subject.getDeliveryDate() != null, "the case has not been delivered");

		return apply(subject, to, Action.CONFIRM_RECEIPT_AND_CLOSE, null,
				c -> c.setCaseClosedDate(Instant.now()));
	}

	// --- exception states ----------------------------------------------------

	@Transactional
	public Case putOnHold(UUID caseId, String reason) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.PUT_ON_HOLD);

		return apply(subject, to, Action.PUT_ON_HOLD, reason,
				c -> c.setExceptionState(ExceptionState.ON_HOLD_AWAITING_CLIENT));
	}

	@Transactional
	public Case resumeFromHold(UUID caseId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.RESUME_FROM_HOLD);

		return apply(subject, to, Action.RESUME_FROM_HOLD, null, c -> c.setExceptionState(ExceptionState.NONE));
	}

	@Transactional
	public Case requestRefund(UUID caseId, String reason) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.REQUEST_REFUND);

		return apply(subject, to, Action.REQUEST_REFUND, reason,
				c -> c.setExceptionState(ExceptionState.REFUND_REQUESTED));
	}

	// --- shared plumbing -----------------------------------------------------

	/**
	 * The one place a case is written. Sets the stage, restamps the clock, refreshes
	 * the SLA status, then records the audit entry and publishes the event inside the
	 * caller's transaction — so a transition, its trail, and its event commit
	 * together or not at all.
	 */
	Case apply(Case subject, Stage to, Action action, String note, Consumer<Case> mutation) {
		CaseSnapshot before = CaseSnapshot.of(subject);
		mutation.accept(subject);
		subject.setCurrentStage(to);
		subject.setStageEnteredAt(Instant.now());
		subject.setSlaStatus(sla.statusOf(subject));

		Case saved = cases.save(subject);
		audit.recordEvent(OBJECT_TYPE, saved.getId(), action.auditAction(), TenantContext.current().memberId(),
				before, CaseSnapshot.of(saved, note));
		events.publishEvent(CaseEvents.CaseEvent.of(action.event(), saved));
		return saved;
	}

	/** Scoped load. Out of the caller's brand, team or assignment means not found. */
	Case load(UUID caseId) {
		return cases.findScoped(TenantContext.current(), caseId)
				.orElseThrow(() -> new ForbiddenException("No case " + caseId + " in this caller's scope"));
	}

	private static void requireState(boolean condition, String why) {
		if (!condition) {
			throw new IllegalTransitionException(why);
		}
	}

	private static boolean isComplete(DocumentChecklistItem item) {
		return item.getStatus() == ChecklistItemStatus.APPROVED || item.getStatus() == ChecklistItemStatus.UPLOADED;
	}

	/**
	 * A member may only be put on a case in their own brand, in the role the action
	 * needs. Brand and role are in the query rather than checked after the row is in
	 * hand, and the one message covers every way the lookup can fail: this exception
	 * reaches the caller as a 409 body, so "wrong brand", "wrong role" and "no such
	 * member" have to be indistinguishable from outside.
	 */
	private TeamMember member(UUID memberId, Role expected, UUID brandId) {
		return teamMembers.findByIdAndBrandIdAndRole(memberId, brandId, expected)
				.orElseThrow(() -> new IllegalTransitionException("No %s available for this case".formatted(expected)));
	}

	/** The scoped read is what keeps one brand's roster out of another brand's case. */
	private Expert availableExpert(UUID expertId) {
		Expert expert = experts.findScoped(TenantContext.current(), expertId)
				.orElseThrow(() -> new IllegalTransitionException("No such expert in this brand: " + expertId));
		if (expert.getAvailability() != Availability.AVAILABLE) {
			throw new IllegalTransitionException("Expert %s is %s".formatted(expertId, expert.getAvailability()));
		}
		return expert;
	}
}
