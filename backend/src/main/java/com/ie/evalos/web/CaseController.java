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
	 * The GM may perform almost any transition, on top of the role the spec's actor column
	 * names. Prefixed onto the gates as a constant rather than spelled out in eighteen role
	 * lists, so "and the GM" cannot be forgotten on a new route.
	 *
	 * <p><strong>Four routes do not use it, in two different directions.</strong> The two refund
	 * rulings are GM-<em>only</em> rather than GM-also. The two draft-review rulings
	 * ({@code draft/pm-approve}, {@code draft/pm-return}) are GM-<em>excluded</em>: reviewing a
	 * Case Manager's draft belongs to the PM who assigned it, and a superuser path around the
	 * reviewer makes "who approved this" ambiguous on the artefact the client pays for. Adding
	 * {@code GM_OR} back to either pair is a decision, not a tidy-up.
	 */
	private static final String GM_OR = "hasRole('GM') or ";

	/**
	 * Who may see what a case was sold for. Package-private rather than private because
	 * {@code CaseBoardController} projects the same field and this must stay one list —
	 * two copies is how a Case Manager ends up seeing the deal value on one screen.
	 */
	static final Set<Role> SEES_DEAL_VALUE = Set.of(Role.GM, Role.BRAND_MANAGER, Role.PROJECT_MANAGER);

	/**
	 * Whether this caller may see who the client is and what the case says.
	 *
	 * <p>Gates {@code clientName}, {@code driveLink} and {@code draftLink} on both the board
	 * projection and the detail one.
	 *
	 * <p><strong>Derived from the scope tier rather than written as a role set</strong>, unlike
	 * {@link #SEES_DEAL_VALUE} above. {@link Role.Tier#SUPPLY} already means precisely this —
	 * "own brand's expert/roster supply side, not case content" — and {@code Role}'s own javadoc
	 * states the tier is the single source of truth for scoping. A role list here would be a
	 * second copy of that fact, and the copy is what goes stale when a seventh role arrives.
	 *
	 * <p>This is a <em>field</em> projection and deliberately not a row filter: the Expert
	 * Network Manager has three case transitions (expert signed / declined / reassign) that must
	 * load the case to act on it. They need the row; they must not receive the client on it.
	 */
	static boolean seesCaseContent(Role role) {
		return role.tier() != Role.Tier.SUPPLY;
	}

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
			boolean mayEditStrategyNotes,
			/**
			 * Whether this caller may see {@code clientName}, {@code driveLink} and
			 * {@code draftLink}, stated rather than inferred from their absence.
			 *
			 * <p>Same reasoning as {@link #maySeeStrategyNotes}, and here it is load-bearing:
			 * {@code clientName} is <em>already</em> legitimately null when a case has no linked
			 * contact, and the UI renders that as "Unnamed contact". Without this flag a withheld
			 * client would be drawn as a claim about the client that is not true.
			 */
			boolean maySeeCaseContent) {

		static CaseDetail of(CaseDetailService.CaseWithContext context, TenantContext ctx) {
			Case subject = context.subject();
			boolean seesNotes = SEES_STRATEGY_NOTES.contains(ctx.role());
			boolean seesContent = seesCaseContent(ctx.role());
			return new CaseDetail(
					// CaseSummary carries no client identity of its own — checked, not assumed.
					CaseSummary.of(subject, ctx),
					seesContent ? context.clientName() : null,
					seesContent ? subject.getDriveLink() : null,
					seesContent ? subject.getDraftLink() : null,
					// expertName and expertTier are deliberately NOT projected: the supply-side
					// role's whole job is the expert, and the roster is already theirs to read.
					context.expertName(),
					context.expertTier(),
					context.checklist().total(),
					context.checklist().complete(),
					seesNotes ? subject.getPmStrategyNotes() : null,
					seesNotes,
					MAY_EDIT_STRATEGY_NOTES.contains(ctx.role()),
					seesContent);
		}
	}

	/** The PM owns the notes; the GM is a superuser here as on every other write. */
	private static final Set<Role> MAY_EDIT_STRATEGY_NOTES = Set.of(Role.GM, Role.PROJECT_MANAGER);

	public record ExpertRequest(@NotNull UUID expertId) {
	}

	/** Return comments, hold reasons, decline reasons, revision notes — all free text. */
	/** Free text, and the whole content of the entry — so blank is refused at the edge. */
	public record NoteRequest(@NotBlank String note) {
	}

	public record ReasonRequest(@NotBlank String reason) {
	}

	/**
	 * Where the drafted letter is (Unit 14). Optional: null or blank leaves whatever link the case
	 * already carries, so re-submitting a revision filed in the same place needs nothing typed.
	 */
	public record SubmitDraftRequest(String draftLink) {
	}

	public record CaseManagerRequest(@NotNull UUID cmId) {
	}

	/**
	 * The new promised date.
	 *
	 * <p>{@code @NotNull} because clearing a deadline is a different act from changing one, and a
	 * case with no date silently drops off every risk tile that exists to surface it.
	 */
	public record DeadlineRequest(@NotNull Instant deadline) {
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

	/**
	 * Moves a case to another Case Manager, mid-draft, without moving the case.
	 *
	 * <p>{@code PATCH} and not {@code POST} for the same reason as strategy notes: this is not a
	 * transition. See {@code CaseLifecycleService.reassignCaseManager} for why it is not the
	 * {@code assign-cm} route widened — that one would mint an expert offer as a side effect.
	 */
	@PatchMapping("/{id}/case-manager")
	@PreAuthorize(GM_OR + "hasRole('PROJECT_MANAGER')")
	public ApiResponse<CaseSummary> reassignCaseManager(@PathVariable UUID id,
			@Valid @RequestBody CaseManagerRequest request) {
		return summary(lifecycle.reassignCaseManager(id, request.cmId()));
	}

	/**
	 * Changes the date the client was promised.
	 *
	 * <p>Also not a transition. The deadline drives {@code DeadlineRisk}, which is computed on
	 * read, so the risk tiles reclassify on their next query with nothing to invalidate.
	 */
	@PatchMapping("/{id}/deadline")
	@PreAuthorize(GM_OR + "hasRole('PROJECT_MANAGER')")
	public ApiResponse<CaseSummary> changeDeadline(@PathVariable UUID id,
			@Valid @RequestBody DeadlineRequest request) {
		return summary(lifecycle.changeDeadline(id, request.deadline()));
	}

	/**
	 * A Case Manager raising a blocked case to its Project Manager.
	 *
	 * <p>{@code POST} because it has an outward effect — somebody is notified — but it is not a
	 * transition and moves nothing. The reason is required: a flag with no reason asks the PM to
	 * guess what is wrong, which is the whole thing the flag was supposed to save them.
	 */
	@PostMapping("/{id}/flag")
	@PreAuthorize(GM_OR + "hasRole('CASE_MANAGER')")
	public ApiResponse<CaseSummary> flag(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
		return summary(lifecycle.flagToPm(id, request.reason()));
	}

	/**
	 * A note on the case, for whoever works it next (Unit 23).
	 *
	 * <p><strong>The missing {@code @PreAuthorize} is the design, not an omission.</strong> Every
	 * other route here names the roles the spec's actor column gives that transition; a note is
	 * not a transition and has no actor column. "Everybody related to the case" is not a role
	 * list — it is exactly the set the scoped load in {@code CaseLifecycleService.addNote}
	 * admits, and spelling it out as six roles here would be a second copy of the scope that
	 * goes stale the first time the scope changes. A caller who cannot read the case gets 403
	 * from the load, before anything is written.
	 *
	 * <p>{@code POST} because it appends a permanent row. There is no PUT or DELETE beside it
	 * and there cannot be: the trail is append-only (invariant 13).
	 */
	@PostMapping("/{id}/notes")
	public ApiResponse<CaseSummary> addNote(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
		return summary(lifecycle.addNote(id, request.note()));
	}

	// There is no payment route. Handoff A now fires on the GHL opportunity being marked
	// Won, which GHL only does after it has invoiced and collected, so the case arrives
	// paid and GHL is the sole source of that fact (invariant 8).

	// --- assignment ----------------------------------------------------------

	/**
	 * Takes the case out of the pool and stamps the PM's team, which is what opens it to that
	 * team at all.
	 *
	 * <p>The Project Manager is on this gate as of Unit 23, and it is the point of that unit:
	 * a paid case lands in the PM inbox and the PM <em>claims</em> it, rather than waiting for
	 * a commercial role to hand it over. They can read a pooled case because
	 * {@code CaseRepository.SCOPE} sets {@code unteamedVisible} — the two changes are one
	 * decision and neither works alone.
	 */
	@PostMapping("/{id}/assign-pm")
	@PreAuthorize(GM_OR + "hasAnyRole('BRAND_MANAGER', 'PROJECT_MANAGER')")
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

	/**
	 * Draft review is the Project Manager's, and the GM is <strong>not</strong> on this gate
	 * (Unit 23a) — one of only two places `GM_OR` is deliberately absent besides the refund
	 * rulings, which are GM-<em>only</em> rather than GM-excluded.
	 *
	 * <p>Approving a draft is a judgement about a Case Manager's work, made by the person who
	 * assigned it to them and who answers for what goes to the client. A superuser path around
	 * that reviewer is not oversight — it is a second reviewer with none of the context, and it
	 * makes "who approved this" ambiguous on the one artefact the business is paid for. The GM's
	 * lever here is reassigning the PM, not overriding them.
	 *
	 * <p>`boardRules.QUICK_ACTIONS` marks both actions `gm: 'never'` and `/drafts` is PM-only in
	 * the nav table, so no screen offers a button this refuses.
	 */
	@PostMapping("/{id}/draft/pm-approve")
	@PreAuthorize("hasRole('PROJECT_MANAGER')")
	public ApiResponse<CaseSummary> pmApproveDraft(@PathVariable UUID id) {
		return summary(lifecycle.pmApproveDraft(id));
	}

	/** The other half of draft review, and GM-excluded for the same reason. */
	@PostMapping("/{id}/draft/pm-return")
	@PreAuthorize("hasRole('PROJECT_MANAGER')")
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
