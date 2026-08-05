package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.PmApprovalStatus;
import com.ie.evalos.domain.PoolStatus;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
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
			UUID assignedCoordinator,
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
					subject.getAssignedPm(), subject.getAssignedCm(), subject.getAssignedCoordinator(),
					subject.getExpertId(), subject.getExpertSignStatus(), subject.getPmApprovalStatus(),
					subject.getClientApprovalStatus(), subject.getDraftVersionCount(), subject.getSlaStatus(),
					subject.isPaid(), note);
		}
	}

	private final CaseRepository cases;
	private final DocumentChecklistItemRepository checklistItems;
	private final ExpertRepository experts;
	private final ExpertCaseOfferRepository offers;
	private final TeamMemberRepository teamMembers;
	private final AuditService audit;
	private final SlaCalculator sla;
	private final ApplicationEventPublisher events;

	CaseLifecycleService(CaseRepository cases, DocumentChecklistItemRepository checklistItems, ExpertRepository experts,
			ExpertCaseOfferRepository offers, TeamMemberRepository teamMembers, AuditService audit, SlaCalculator sla,
			ApplicationEventPublisher events) {
		this.cases = cases;
		this.checklistItems = checklistItems;
		this.experts = experts;
		this.offers = offers;
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

	// There is no payment transition. Case Creation v2.0 fires Handoff A on the GHL
	// opportunity being marked Won, and GHL invoices and collects before that — so the
	// webhook is the proof of payment and `CaseIntakeService` is the only writer of
	// `paid`. A staff "record payment" action would be a second way to state a fact GHL
	// already owns, and a second thing that can disagree with it.

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

	/**
	 * Puts a Coordinator on the case. This is what makes their scope real: their tier is
	 * SELF, so until a case names them in {@code assigned_coordinator} they cannot read
	 * it — and the four transitions the design makes them the actor for would 403 on
	 * their own work.
	 *
	 * <p>Re-assignable, unlike {@code assignPm}, which refuses a case that has left the
	 * pool. There is no pool for coordination and staff change; the audit trail records
	 * each hand-over, so the column only ever needs to hold who has it now.
	 */
	@Transactional
	public Case assignCoordinator(UUID caseId, UUID coordinatorId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.ASSIGN_COORDINATOR);
		TeamMember coordinator = member(coordinatorId, Role.PROJECT_COORDINATOR, subject.getBrandId());

		return apply(subject, to, Action.ASSIGN_COORDINATOR, null,
				c -> c.setAssignedCoordinator(coordinator.getId()));
	}

	/**
	 * PM → CM, with the expert the CM will draft for.
	 *
	 * <p><strong>The name reads as staff-only and is not:</strong> this action assigns both, and
	 * it is where an expert offer comes from — which is why the offer row is written here rather
	 * than by some endpoint of its own. It commits inside this transaction, so an offer and the
	 * transition that caused it land together or not at all.
	 *
	 * <p>Nothing here consults the match engine. The expert given is the expert used, whether or
	 * not they were on any shortlist — the ranking is assistance, never a precondition.
	 */
	@Transactional
	public Case assignCaseManager(UUID caseId, UUID cmId, UUID expertId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.ASSIGN_CASE_MANAGER);
		TeamMember cm = member(cmId, Role.CASE_MANAGER, subject.getBrandId());
		requireState(cm.getTeamId() != null && cm.getTeamId().equals(subject.getTeamId()),
				"case manager is not on this case's team");
		Expert expert = availableExpert(expertId);

		Case saved = apply(subject, to, Action.ASSIGN_CASE_MANAGER, null, c -> {
			c.setAssignedCm(cm.getId());
			c.setExpertId(expert.getId());
			c.setExpertSignStatus(ExpertSignStatus.PENDING);
		});
		offers.save(new ExpertCaseOffer(saved.getBrandId(), saved.getId(), expert.getId()));
		return saved;
	}

	/** What a strategy-notes edit records. The text is the change, so the text is the snapshot. */
	public record StrategyNotesSnapshot(String pmStrategyNotes) {
	}

	/**
	 * The PM's guidance to the Case Manager working the draft.
	 *
	 * <p><strong>Deliberately not routed through {@link #apply}.</strong> This is not a
	 * transition: nothing about the case's state changes. Reusing {@code apply} would restamp
	 * {@code stage_entered_at} and so silently reset the SLA clock — editing a note would buy
	 * the case a fresh budget on whatever it is waiting for — and would publish a lifecycle
	 * event for something that did not happen in the lifecycle. It still writes an audit row,
	 * because invariant 13 is about every change, not every transition.
	 *
	 * <p>Legal at any stage, including a case in an exception state and a closed one: notes are
	 * a record of thinking, and the transition table has no business gating them.
	 */
	@Transactional
	public Case updateStrategyNotes(UUID caseId, String notes) {
		Case subject = load(caseId);
		StrategyNotesSnapshot before = new StrategyNotesSnapshot(subject.getPmStrategyNotes());

		subject.setPmStrategyNotes(notes);
		Case saved = cases.save(subject);

		audit.recordEvent(OBJECT_TYPE, saved.getId(), AuditAction.UPDATED, TenantContext.current().memberId(),
				before, new StrategyNotesSnapshot(notes));
		return saved;
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
		requireState(items.stream().allMatch(item -> item.getStatus().isComplete()),
				"not every checklist item is uploaded or approved");

		return apply(subject, to, Action.MARK_DOCS_COMPLETE, null, c -> {
		});
	}

	// --- the draft loops, all inside DRAFT_GENERATION -------------------------

	/**
	 * The Case Manager hands in a draft, and says where it is.
	 *
	 * <p>{@code draftLink} is where {@code draft_link} comes from — the column the client portal
	 * shows (Unit 14). Optional and only overwritten when given: a second version filed in the same
	 * place needs no new link, and blanking one by omission would take the draft away from a client
	 * mid-review. There is deliberately no fallback to {@code drive_link}: that is the client's own
	 * document folder, and a case with no draft link tells the portal "not ready".
	 */
	@Transactional
	public Case submitDraft(UUID caseId, String draftLink) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.SUBMIT_DRAFT);

		return apply(subject, to, Action.SUBMIT_DRAFT, null, c -> {
			c.setDraftVersionCount(c.getDraftVersionCount() + 1);
			c.setPmApprovalStatus(PmApprovalStatus.PENDING);
			// A new draft is not the draft the client already saw.
			c.setClientApprovalStatus(null);
			if (draftLink != null && !draftLink.isBlank()) {
				c.setDraftLink(draftLink.trim());
			}
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

	/** Staff recording an answer the client gave some other way — a phone call, an email. */
	@Transactional
	public Case clientRequestRevisions(UUID caseId, String notes) {
		return revisions(load(caseId), notes, null);
	}

	/**
	 * The same transition, driven by the client themselves through their portal (Unit 14).
	 *
	 * <p>Takes the case rather than an id because the portal has <strong>already</strong>
	 * authorized it: the token names exactly one case, so there is no id to scope and no
	 * {@code TenantContext} to scope it with. The guards, the state machine, the audit row and the
	 * event all stay in the one place below — the only difference is who the trail says did it.
	 */
	@Transactional
	public Case clientRequestRevisionsFromPortal(Case authorized, String notes) {
		return revisions(authorized, notes, PortalAudience.CLIENT);
	}

	private Case revisions(Case subject, String notes, PortalAudience actor) {
		Stage to = CaseTransitions.target(subject, Action.CLIENT_REQUEST_REVISIONS);
		requireState(subject.getClientApprovalStatus() == ClientApprovalStatus.PENDING,
				"no draft is with the client");

		return apply(subject, to, Action.CLIENT_REQUEST_REVISIONS, notes,
				c -> c.setClientApprovalStatus(ClientApprovalStatus.REVISION_REQUESTED), actor);
	}

	/** Handoff B, recorded by staff. See {@link #clientApproveDraftFromPortal} for the client's own act. */
	@Transactional
	public Case clientApproveDraft(UUID caseId) {
		return approve(load(caseId), null);
	}

	/**
	 * Handoff B, performed by the client: they approve and the case goes to the expert to sign.
	 *
	 * <p>The portal-safe entry, on an already-authorized case — see
	 * {@link #clientRequestRevisionsFromPortal}. The guard that refuses a case whose draft is not
	 * with the client is the existing one below, so a client hitting approve twice gets the same 409
	 * a staff member would, from the same line: the state machine is not duplicated for this
	 * surface.
	 */
	@Transactional
	public Case clientApproveDraftFromPortal(Case authorized) {
		return approve(authorized, PortalAudience.CLIENT);
	}

	private Case approve(Case subject, PortalAudience actor) {
		Stage to = CaseTransitions.target(subject, Action.CLIENT_APPROVE_DRAFT);
		requireState(subject.getClientApprovalStatus() == ClientApprovalStatus.PENDING,
				"no draft is with the client");
		requireState(subject.getExpertId() != null, "no expert is on this case");

		return apply(subject, to, Action.CLIENT_APPROVE_DRAFT, null, c -> {
			c.setClientApprovalStatus(ClientApprovalStatus.APPROVED);
			c.setExpertSignStatus(ExpertSignStatus.PENDING);
		}, actor);
	}

	// --- expert signing ------------------------------------------------------

	/**
	 * Driven by the Dropbox Sign callback (Unit 15), or recorded by staff.
	 *
	 * <p>This is where the offer becomes {@code ACCEPTED}. Unit 15 will fill it from the real
	 * callback instead of the staff-recorded stand-in; the column does not change, and the
	 * first-write-wins guard in {@link ExpertCaseOffer#resolve} is what makes both acts safe on
	 * the happy path where both fire.
	 */
	@Transactional
	public Case expertSigned(UUID caseId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.EXPERT_SIGNED);

		resolveOpenOffer(subject, OfferOutcome.ACCEPTED, null);
		return apply(subject, to, Action.EXPERT_SIGNED, null, c -> c.setExpertSignStatus(ExpertSignStatus.SIGNED));
	}

	@Transactional
	public Case expertDeclined(UUID caseId, String reason) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.EXPERT_DECLINED);

		// The reason goes to two places for two purposes: the audit trail, which is the history,
		// and the offer row, which is what the acceptance rate is aggregated from.
		resolveOpenOffer(subject, OfferOutcome.DECLINED, reason);
		return apply(subject, to, Action.EXPERT_DECLINED, reason,
				c -> c.setExceptionState(ExceptionState.EXPERT_DECLINED_REMATCHING));
	}

	/**
	 * The rematch. Two offer writes, in this order: the outgoing offer is closed
	 * {@code SUPERSEDED} and a fresh one opened for the replacement.
	 *
	 * <p>Superseding matters because an offer nobody will ever answer would otherwise sit
	 * {@code OFFERED} forever — an open row that no transition can reach, which is the shape of
	 * data that eventually gets counted as something. It is deliberately not a decline: the
	 * expert was never given the chance to answer, so it says nothing about them and
	 * {@code OfferOutcome.countsTowardAcceptanceRate} excludes it.
	 */
	@Transactional
	public Case reassignExpert(UUID caseId, UUID expertId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.REASSIGN_EXPERT);
		Expert replacement = availableExpert(expertId);
		requireState(!replacement.getId().equals(subject.getExpertId()),
				"that is the expert who declined");

		resolveOpenOffer(subject, OfferOutcome.SUPERSEDED, null);
		Case saved = apply(subject, to, Action.REASSIGN_EXPERT, null, c -> {
			c.setExpertId(replacement.getId());
			c.setExpertSignStatus(ExpertSignStatus.REASSIGNED);
			c.setExceptionState(ExceptionState.NONE);
		});
		offers.save(new ExpertCaseOffer(saved.getBrandId(), saved.getId(), replacement.getId()));
		return saved;
	}

	/**
	 * Stamps whatever offer on this case is still open, if any.
	 *
	 * <p>Tolerant on both edges, deliberately. A case with no open offer — one assigned before
	 * V19 existed, or one whose offer some earlier act already closed — is left alone rather than
	 * failing the transition: the offer table serves a ranking, and refusing a legitimate decline
	 * because its offer row is missing would let a reporting concern block the pipeline. And a
	 * row already resolved is a no-op, per {@link ExpertCaseOffer#resolve}.
	 *
	 * <p><strong>Only the case's own expert gets the real resolution.</strong> V19's partial index
	 * on the open row is not unique and {@link Case} carries no {@code @Version}, so two
	 * concurrent assignments can each read a case with no offer and each open one. Stamping every
	 * open row {@code ACCEPTED} would credit an acceptance to an expert who was never shown the
	 * case, and the acceptance rate is exactly what these rows feed. A stray is closed
	 * {@code SUPERSEDED} instead — the outcome that already means "never had the chance to
	 * answer", and which {@code OfferOutcome.countsTowardAcceptanceRate} excludes — rather than
	 * left {@code OFFERED} forever, which is the state {@link #reassignExpert} is written to avoid.
	 *
	 * <p>Called before the case's own expert is reassigned, so {@code subject.getExpertId()} is
	 * still the expert the open offer was made to in all four callers.
	 *
	 * <p>The case id has come out of {@link #load}, which is scoped, so the unscoped finder
	 * underneath is being called the only way its javadoc permits.
	 */
	private void resolveOpenOffer(Case subject, OfferOutcome resolution, String reason) {
		offers.findByCaseIdAndOutcome(subject.getId(), OfferOutcome.OFFERED).forEach(offer -> {
			boolean subjects = offer.getExpertId().equals(subject.getExpertId());
			if (offer.resolve(subjects ? resolution : OfferOutcome.SUPERSEDED, subjects ? reason : null)) {
				offers.save(offer);
			}
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
		return apply(subject, to, action, note, mutation, null);
	}

	/**
	 * The same, for a transition performed through a portal link rather than by staff.
	 *
	 * <p>{@code portalActor} is the <em>only</em> thing that differs, and it changes one line: the
	 * audit row is written by {@code recordPortalEvent}, which takes its brand from the case rather
	 * than from a {@code TenantContext} a client does not have, and names the client as the actor
	 * instead of leaving a null that reads as "the system". Everything else — the guards above, the
	 * stage write, the clock, the SLA, the event, the transaction — is shared, because a client
	 * approving a draft is the same transition however it was triggered.
	 */
	private Case apply(Case subject, Stage to, Action action, String note, Consumer<Case> mutation,
			PortalAudience portalActor) {
		CaseSnapshot before = CaseSnapshot.of(subject);
		mutation.accept(subject);
		subject.setCurrentStage(to);
		subject.setStageEnteredAt(Instant.now());
		subject.setSlaStatus(sla.statusOf(subject));

		Case saved = cases.save(subject);
		CaseSnapshot after = CaseSnapshot.of(saved, note);
		if (portalActor == null) {
			audit.recordEvent(OBJECT_TYPE, saved.getId(), action.auditAction(),
					TenantContext.current().memberId(), before, after);
		}
		else {
			audit.recordPortalEvent(saved.getBrandId(), portalActor, OBJECT_TYPE, saved.getId(),
					action.auditAction(), before, after);
		}
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

	/**
	 * A member may only be put on a case in their own brand, in the role the action
	 * needs. Brand and role are in the query rather than checked after the row is in
	 * hand, and the one message covers every way the lookup can fail: this exception
	 * reaches the caller as a 409 body, so "wrong brand", "wrong role" and "no such
	 * member" have to be indistinguishable from outside.
	 */
	private TeamMember member(UUID memberId, Role expected, UUID brandId) {
		// `active` is part of the query for the same reason brand and role are: somebody who
		// has left the company is not "available for this case", and a dialog left open across
		// a deactivation would otherwise still assign them. One place, so all three assignment
		// transitions get it rather than the one that happened to be reviewed.
		return teamMembers.findByIdAndBrandIdAndRoleAndActiveTrue(memberId, brandId, expected)
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
