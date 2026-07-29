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
import com.ie.evalos.service.CaseLifecycleService;
import com.ie.evalos.service.RefundService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
 * <p>The client-portal (Unit 14) and expert-surface (Unit 15) routes will call the
 * same service methods behind their own filter chains. The four staff-recorded
 * equivalents below exist because somebody phones in an approval or a decline, and
 * a case must not be stuck until the portal is built.
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

	/** Who may see what a case was sold for. */
	private static final Set<Role> SEES_DEAL_VALUE = Set.of(Role.GM, Role.BRAND_MANAGER, Role.PROJECT_MANAGER);

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
					subject.getAssignedCm(), subject.getExpertId(), subject.getExpertSignStatus(),
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

	public record ExpertRequest(@NotNull UUID expertId) {
	}

	/** Return comments, hold reasons, decline reasons, revision notes — all free text. */
	public record ReasonRequest(@NotBlank String reason) {
	}

	/** What was actually taken, and the invoice it sits against. */
	public record MarkPaidRequest(@NotNull @Positive BigDecimal dealValue, String invoiceRef) {
	}

	private final CaseLifecycleService lifecycle;
	private final RefundService refunds;

	CaseController(CaseLifecycleService lifecycle, RefundService refunds) {
		this.lifecycle = lifecycle;
		this.refunds = refunds;
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
	public ApiResponse<CaseSummary> read(@PathVariable UUID id) {
		return summary(lifecycle.read(id));
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

	// --- document collection -------------------------------------------------

	@PostMapping("/{id}/docs-complete")
	@PreAuthorize(GM_OR + "hasAnyRole('PROJECT_COORDINATOR', 'PROJECT_MANAGER')")
	public ApiResponse<CaseSummary> docsComplete(@PathVariable UUID id) {
		return summary(lifecycle.markDocsComplete(id));
	}

	// --- the draft loops -----------------------------------------------------

	@PostMapping("/{id}/draft/submit")
	@PreAuthorize(GM_OR + "hasRole('CASE_MANAGER')")
	public ApiResponse<CaseSummary> submitDraft(@PathVariable UUID id) {
		return summary(lifecycle.submitDraft(id));
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
