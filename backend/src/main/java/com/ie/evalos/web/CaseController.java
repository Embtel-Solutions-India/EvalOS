package com.ie.evalos.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.PmApprovalStatus;
import com.ie.evalos.domain.PoolStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.CaseDetailService;
import com.ie.evalos.service.CaseLifecycleService;
import com.ie.evalos.service.RefundService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One endpoint per transition, and nothing else. The role gate is the
 * {@code @PreAuthorize} here, the brand/team/assignment gate is the scoped load in
 * the service, and the state gate is the declared transition table — three
 * separate checks, none of which this class implements.
 *
 * <p>The client portal is built (Unit 14) and does <strong>not</strong> live here: it is
 * {@code web/ClientPortalController} behind its own filter chain, and it reaches the state machine
 * through {@code CaseLifecycleService.clientApproveDraftFromPortal} /
 * {@code clientRequestRevisionsFromPortal} — the same transitions and the same guards, entered with
 * an already-authorized case instead of an id. The expert surface (Unit 15) will join it there.
 *
 * <p>The four staff-recorded equivalents below therefore still exist, and not as a stopgap: somebody
 * phones in an approval or a decline, or a client cannot use the link at all, and the case must not
 * be stuck. What differs is the trail — a staff-recorded answer names the staff member, while the
 * portal names the client ({@code actor_type = CLIENT}).
 */
@RestController
@RequestMapping("/api/cases")
public class CaseController {

	/**
	 * The GM may perform any transition, on top of the role the spec's actor column
	 * names. Prefixed onto every gate as a constant rather than spelled out in
	 * eighteen role lists, so "and the GM" cannot be forgotten on a new route. The
	 * two refund rulings are the exception: they are GM-*only*, not GM-also.
	 */
	private static final String GM_OR = "hasRole('GM') or ";

	/**
	 * Who may see what a case was sold for. Package-private rather than private because
	 * {@code CaseBoardController} projects the same field and this must stay one list —
	 * two copies is how a Case Manager ends up seeing the deal value on one screen.
	 */
	static final Set<Role> SEES_DEAL_VALUE = Set.of(Role.GM, Role.BRAND_MANAGER, Role.PROJECT_MANAGER);

	/**
	 * The case as staff read it. No {@code pm_strategy_notes} and no drive link: this
	 * is the board/list projection, and the detail view is Unit 09.
	 */
	public record CaseSummary(
			UUID id,
			String caseCode,
			Stage currentStage,
			ExceptionState exceptionState,
			PoolStatus poolStatus,
			SlaStatus slaStatus,
			ServiceType serviceType,
			Instant deadline,
			Instant stageEnteredAt,
			Instant deliveryDate,
			Instant caseClosedDate,
			UUID assignedPm,
			UUID assignedCm,
			UUID assignedCoordinator,
			UUID expertId,
			ExpertSignStatus expertSignStatus,
			PmApprovalStatus pmApprovalStatus,
			ClientApprovalStatus clientApprovalStatus,
			int draftVersionCount,
			boolean paid,
			Instant paidAt,
			boolean revenueRecognized,
			BigDecimal dealValue) {

		static CaseSummary of(Case subject, TenantContext ctx) {
			return new CaseSummary(subject.getId(), subject.getCaseCode(), subject.getCurrentStage(),
					subject.getExceptionState(), subject.getPoolStatus(), subject.getSlaStatus(),
					subject.getServiceType(), subject.getDeadline(), subject.getStageEnteredAt(),
					subject.getDeliveryDate(), subject.getCaseClosedDate(), subject.getAssignedPm(),
					subject.getAssignedCm(), subject.getAssignedCoordinator(),
					subject.getExpertId(), subject.getExpertSignStatus(),
					subject.getPmApprovalStatus(), subject.getClientApprovalStatus(),
					subject.getDraftVersionCount(), subject.isPaid(), subject.getPaidAt(),
					RefundService.isRevenueRecognized(subject),
					SEES_DEAL_VALUE.contains(ctx.role()) ? subject.getDealValue() : null);
		}
	}

	public record AssignPmRequest(@NotNull UUID pmId) {
	}

	public record AssignCmRequest(@NotNull UUID cmId, @NotNull UUID expertId) {
	}

	public record AssignCoordinatorRequest(@NotNull UUID coordinatorId) {
	}

	/**
	 * Blank clears the notes, so this is {@code @NotNull} rather than {@code @NotBlank} —
	 * deleting guidance that no longer applies is a legitimate edit.
	 */
	public record StrategyNotesRequest(@NotNull String pmStrategyNotes) {
	}

	/**
	 * Who may read the PM's strategy notes: the PM who writes them, the Case Manager they are
	 * written for, and the GM. Narrower than {@code deal_value} on purpose — this is working
	 * guidance between two named people on one case, not a commercial figure the brand's
	 * management needs.
	 *
	 * <p><strong>The Brand Manager is deliberately not here, and that is confirmed</strong> rather
	 * than an oversight: they keep {@code deal_value}, which is the field their role turns on.
	 * Note the asymmetry — a Brand Manager sees what the case is worth and not how it will be
	 * argued, while a Case Manager sees the reverse.
	 */
	private static final Set<Role> SEES_STRATEGY_NOTES = Set.of(Role.GM, Role.PROJECT_MANAGER, Role.CASE_MANAGER);

	/**
	 * The case as the detail page reads it: everything in the summary, plus the joined context
	 * a single case needs and the two role-restricted fields.
	 *
	 * <p>Both restrictions are applied here, in the projection, rather than hidden by the
	 * client: a field the caller may not see is absent from the payload, so there is nothing to
	 * reveal with dev tools (spec deliverable 5, invariant 3).
	 */
	public record CaseDetail(
			CaseSummary summary,
			String clientName,
			/** The client's own document folder. Staff-only, and never sent to the client portal. */
			String driveLink,
			/**
			 * The drafted letter (Unit 14). What {@code DraftPanel} links to, and the only link the
			 * client portal shows — {@code driveLink} above is a different thing and is not a
			 * fallback for it.
			 */
			String draftLink,
			String expertName,
			String expertTier,
			int checklistTotal,
			int checklistComplete,
			/** Null for every role outside GM / PM / CM. */
			String pmStrategyNotes,
			/**
			 * Whether this caller may READ the notes, stated rather than inferred.
			 *
			 * <p>Without this the client had to guess, and the only signal available was
			 * {@code mayEditStrategyNotes} — which is wrong for the Case Manager, the one role that
			 * reads without writing. A null {@code pmStrategyNotes} means "withheld" or "not written
			 * yet" and nothing can tell those apart from the value alone.
			 */
			boolean maySeeStrategyNotes,
			/** Whether this caller may write the notes, so the client need not re-derive the rule. */
			boolean mayEditStrategyNotes) {

		static CaseDetail of(CaseDetailService.CaseWithContext context, TenantContext ctx) {
			Case subject = context.subject();
			boolean seesNotes = SEES_STRATEGY_NOTES.contains(ctx.role());
			return new CaseDetail(
					CaseSummary.of(subject, ctx),
					context.clientName(),
					subject.getDriveLink(),
					subject.getDraftLink(),
					context.expertName(),
					context.expertTier(),
					context.checklist().total(),
					context.checklist().complete(),
					seesNotes ? subject.getPmStrategyNotes() : null,
					seesNotes,
					MAY_EDIT_STRATEGY_NOTES.contains(ctx.role()));
		}
	}

	/** The PM owns the notes; the GM is a superuser here as on every other write. */
	private static final Set<Role> MAY_EDIT_STRATEGY_NOTES = Set.of(Role.GM, Role.PROJECT_MANAGER);

	public record ExpertRequest(@NotNull UUID expertId) {
	}

	/** Return comments, hold reasons, decline reasons, revision notes — all free text. */
	public record ReasonRequest(@NotBlank String reason) {
	}

	/**
	 * Where the drafted letter is (Unit 14). Optional: null or blank leaves whatever link the case
	 * already carries, so re-submitting a revision filed in the same place needs nothing typed.
	 */
	public record SubmitDraftRequest(String draftLink) {
	}

	/** What was actually taken, and the invoice it sits against. */
	public record MarkPaidRequest(@NotNull @Positive BigDecimal dealValue, String invoiceRef) {
	}

	private final CaseLifecycleService lifecycle;
	private final RefundService refunds;
	private final CaseDetailService details;

	CaseController(CaseLifecycleService lifecycle, RefundService refunds, CaseDetailService details) {
		this.lifecycle = lifecycle;
		this.refunds = refunds;
		this.details = details;
	}

	private static ApiResponse<CaseSummary> summary(Case subject) {
		return ApiResponse.ok(CaseSummary.of(subject, TenantContext.current()));
	}

	// --- reads ---------------------------------------------------------------

	@GetMapping
	public ApiResponse<List<CaseSummary>> list(
			@RequestParam(required = false) Stage stage,
			@RequestParam(required = false) SlaStatus sla,
			@RequestParam(required = false) Instant dueBefore) {
		TenantContext ctx = TenantContext.current();
		return ApiResponse.ok(lifecycle.list(stage, sla, dueBefore).stream()
				.map(subject -> CaseSummary.of(subject, ctx))
				.toList());
	}

	@GetMapping("/{id}")
	public ApiResponse<CaseDetail> read(@PathVariable UUID id) {
		return ApiResponse.ok(CaseDetail.of(details.detail(id), TenantContext.current()));
	}

	/**
	 * Not a transition, so not a POST alongside the eighteen below: nothing about the case's
	 * state changes, and the service deliberately leaves the stage clock alone. It still writes
	 * an audit row, so the edit appears on the timeline.
	 */
	@PatchMapping("/{id}/strategy-notes")
	@PreAuthorize(GM_OR + "hasRole('PROJECT_MANAGER')")
	public ApiResponse<CaseDetail> updateStrategyNotes(@PathVariable UUID id,
			@Valid @RequestBody StrategyNotesRequest request) {
		lifecycle.updateStrategyNotes(id, request.pmStrategyNotes());
		return read(id);
	}

	// --- payment -------------------------------------------------------------

	/**
	 * Records the money. Handoff A creates a case from a GHL contact, before payment, so
	 * this is what makes it workable — nothing reaches an expert until it is called.
	 * Same gate as assigning a PM, because both are the brand's commercial decisions.
	 */
	@PostMapping("/{id}/mark-paid")
	@PreAuthorize(GM_OR + "hasRole('BRAND_MANAGER')")
	public ApiResponse<CaseSummary> markPaid(@PathVariable UUID id, @Valid @RequestBody MarkPaidRequest request) {
		return summary(lifecycle.markPaid(id, request.dealValue(), request.invoiceRef()));
	}

	// --- assignment ----------------------------------------------------------

	@PostMapping("/{id}/assign-pm")
	@PreAuthorize(GM_OR + "hasRole('BRAND_MANAGER')")
	public ApiResponse<CaseSummary> assignPm(@PathVariable UUID id, @Valid @RequestBody AssignPmRequest request) {
		return summary(lifecycle.assignPm(id, request.pmId()));
	}

	@PostMapping("/{id}/assign-cm")
	@PreAuthorize(GM_OR + "hasRole('PROJECT_MANAGER')")
	public ApiResponse<CaseSummary> assignCm(@PathVariable UUID id, @Valid @RequestBody AssignCmRequest request) {
		return summary(lifecycle.assignCaseManager(id, request.cmId(), request.expertId()));
	}

	/**
	 * Staffs the Coordinator. Same gate as assigning a PM plus the PM themselves, because
	 * all three are decisions about who works the case rather than about the case's state.
	 */
	@PostMapping("/{id}/assign-coordinator")
	@PreAuthorize(GM_OR + "hasAnyRole('BRAND_MANAGER', 'PROJECT_MANAGER')")
	public ApiResponse<CaseSummary> assignCoordinator(@PathVariable UUID id,
			@Valid @RequestBody AssignCoordinatorRequest request) {
		return summary(lifecycle.assignCoordinator(id, request.coordinatorId()));
	}

	// --- document collection -------------------------------------------------

	/**
	 * The Brand Manager is on this gate because Unit 10 gave them the checklist screen and its
	 * three writes. A role that can add a required document, mark one approved, and chase the
	 * client, but not say the collection is finished, would be offered the one button the screen
	 * builds towards and refused it — which is the client-offers-what-the-server-refuses failure
	 * {@code ChecklistController.COORDINATION} exists to avoid, one layer down. The Project
	 * Manager stays because they act on the outcome even though they do not run the chase.
	 */
	@PostMapping("/{id}/docs-complete")
	@PreAuthorize(GM_OR + "hasAnyRole('BRAND_MANAGER', 'PROJECT_COORDINATOR', 'PROJECT_MANAGER')")
	public ApiResponse<CaseSummary> docsComplete(@PathVariable UUID id) {
		return summary(lifecycle.markDocsComplete(id));
	}

	// --- the draft loops -----------------------------------------------------

	/**
	 * The body is optional and its one field is too: a second version filed in the same place needs
	 * no new link, and omitting it leaves the existing one alone rather than taking the draft away
	 * from a client mid-review.
	 */
	@PostMapping("/{id}/draft/submit")
	@PreAuthorize(GM_OR + "hasRole('CASE_MANAGER')")
	public ApiResponse<CaseSummary> submitDraft(@PathVariable UUID id,
			@RequestBody(required = false) SubmitDraftRequest request) {
		return summary(lifecycle.submitDraft(id, request == null ? null : request.draftLink()));
	}

	@PostMapping("/{id}/draft/pm-approve")
	@PreAuthorize(GM_OR + "hasRole('PROJECT_MANAGER')")
	public ApiResponse<CaseSummary> pmApproveDraft(@PathVariable UUID id) {
		return summary(lifecycle.pmApproveDraft(id));
	}

	@PostMapping("/{id}/draft/pm-return")
	@PreAuthorize(GM_OR + "hasRole('PROJECT_MANAGER')")
	public ApiResponse<CaseSummary> pmReturnDraft(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
		return summary(lifecycle.pmReturnDraft(id, request.reason()));
	}

	@PostMapping("/{id}/draft/send-to-client")
	@PreAuthorize(GM_OR + "hasRole('PROJECT_COORDINATOR')")
	public ApiResponse<CaseSummary> sendDraftToClient(@PathVariable UUID id) {
		return summary(lifecycle.sendDraftToClient(id));
	}

	/** Staff-recorded stand-in for the client portal's approve (Unit 14). */
	@PostMapping("/{id}/draft/client-approve")
	@PreAuthorize(GM_OR + "hasRole('PROJECT_COORDINATOR')")
	public ApiResponse<CaseSummary> clientApproveDraft(@PathVariable UUID id) {
		return summary(lifecycle.clientApproveDraft(id));
	}

	/** Staff-recorded stand-in for the client portal's revision request (Unit 14). */
	@PostMapping("/{id}/draft/client-revisions")
	@PreAuthorize(GM_OR + "hasRole('PROJECT_COORDINATOR')")
	public ApiResponse<CaseSummary> clientRequestRevisions(@PathVariable UUID id,
			@Valid @RequestBody ReasonRequest request) {
		return summary(lifecycle.clientRequestRevisions(id, request.reason()));
	}

	// --- expert signing ------------------------------------------------------

	/** Staff-recorded stand-in for the Dropbox Sign callback (Unit 15). */
	@PostMapping("/{id}/expert/signed")
	@PreAuthorize(GM_OR + "hasAnyRole('PROJECT_MANAGER', 'EXPERT_NETWORK_MANAGER')")
	public ApiResponse<CaseSummary> expertSigned(@PathVariable UUID id) {
		return summary(lifecycle.expertSigned(id));
	}

	/** Staff-recorded stand-in for the Dropbox Sign decline callback (Unit 15). */
	@PostMapping("/{id}/expert/declined")
	@PreAuthorize(GM_OR + "hasAnyRole('PROJECT_MANAGER', 'EXPERT_NETWORK_MANAGER')")
	public ApiResponse<CaseSummary> expertDeclined(@PathVariable UUID id,
			@Valid @RequestBody ReasonRequest request) {
		return summary(lifecycle.expertDeclined(id, request.reason()));
	}

	@PostMapping("/{id}/reassign-expert")
	@PreAuthorize(GM_OR + "hasAnyRole('PROJECT_MANAGER', 'EXPERT_NETWORK_MANAGER')")
	public ApiResponse<CaseSummary> reassignExpert(@PathVariable UUID id, @Valid @RequestBody ExpertRequest request) {
		return summary(lifecycle.reassignExpert(id, request.expertId()));
	}

	@PostMapping("/{id}/qc-approve")
	@PreAuthorize(GM_OR + "hasRole('PROJECT_MANAGER')")
	public ApiResponse<CaseSummary> qcApprove(@PathVariable UUID id) {
		return summary(lifecycle.pmQcApprove(id));
	}

	// --- delivery and close --------------------------------------------------

	@PostMapping("/{id}/deliver")
	@PreAuthorize(GM_OR + "hasRole('PROJECT_COORDINATOR')")
	public ApiResponse<CaseSummary> deliver(@PathVariable UUID id) {
		return summary(lifecycle.deliverToClient(id));
	}

	@PostMapping("/{id}/close")
	@PreAuthorize(GM_OR + "hasRole('PROJECT_COORDINATOR')")
	public ApiResponse<CaseSummary> close(@PathVariable UUID id) {
		return summary(lifecycle.confirmReceiptAndClose(id));
	}

	// --- exception states ----------------------------------------------------

	@PostMapping("/{id}/hold")
	@PreAuthorize(GM_OR + "hasAnyRole('PROJECT_COORDINATOR', 'PROJECT_MANAGER')")
	public ApiResponse<CaseSummary> hold(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
		return summary(lifecycle.putOnHold(id, request.reason()));
	}

	@PostMapping("/{id}/resume")
	@PreAuthorize(GM_OR + "hasAnyRole('PROJECT_COORDINATOR', 'PROJECT_MANAGER')")
	public ApiResponse<CaseSummary> resume(@PathVariable UUID id) {
		return summary(lifecycle.resumeFromHold(id));
	}

	/** Any authenticated staff member — and, from Unit 18, GHL's refund webhook. */
	@PostMapping("/{id}/refund/request")
	public ApiResponse<CaseSummary> requestRefund(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
		return summary(lifecycle.requestRefund(id, request.reason()));
	}

	@PostMapping("/{id}/refund/approve")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<CaseSummary> approveRefund(@PathVariable UUID id) {
		return summary(refunds.approveRefund(id));
	}

	@PostMapping("/{id}/refund/deny")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<CaseSummary> denyRefund(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
		return summary(refunds.denyRefund(id, request.reason()));
	}
}
