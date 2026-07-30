package com.ie.evalos.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.service.ChecklistService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The document checklist: the Coordinator's board over it, and the four things they do to
 * one case's items.
 *
 * <p>No class-level {@code @RequestMapping}, because these paths live in two places by
 * design — the board is its own screen at {@code /api/checklists/board} while the items
 * belong to a case at {@code /api/cases/{id}/checklist}. One controller rather than two,
 * since it is one concern with one service behind it.
 *
 * <p><strong>{@code docs-complete} is not here.</strong> It is a transition and stays on
 * {@code CaseController} with the other nineteen; this unit only surfaces it. The
 * completeness guard it enforces is Unit 04's and is not restated here — see
 * {@link ChecklistView#checklistSatisfied()} for what this screen does and does not claim.
 */
@RestController
public class ChecklistController {

	/**
	 * Who runs document collection.
	 *
	 * <p>The Coordinator owns the stage; the GM and Brand Manager are here because oversight
	 * is brand-wide everywhere else in EvalOS and a GM who can perform every transition but
	 * cannot open the screen that drives one is an inconsistency, not a safeguard. All three
	 * get the writes as well as the read: a screen a Brand Manager can watch but not touch
	 * would need a second permission concept for no stated need, and every write here names
	 * its actor in the trail.
	 *
	 * <p>The Project Manager is deliberately not on this list even though they may call
	 * {@code docs-complete}. They act on the outcome, not on the chase; the per-case read
	 * below is open to them, as it is to every role that can open the case.
	 */
	private static final String COORDINATION = "hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_COORDINATOR')";

	/** One item, as the checklist draws it. */
	public record ChecklistItemView(UUID id, String label, ChecklistItemStatus status, Instant updatedAt) {

		static ChecklistItemView of(DocumentChecklistItem item) {
			return new ChecklistItemView(item.getId(), item.getLabel(), item.getStatus(), item.getUpdatedAt());
		}
	}

	/**
	 * One case's checklist.
	 *
	 * @param checklistSatisfied whether the <em>documents</em> are all in. Deliberately not
	 *                           "may mark complete": {@code markDocsComplete} also requires
	 *                           the case to be paid and to have a PM, and restating those
	 *                           here would be a second copy of a rule that already exists —
	 *                           the copy that goes stale. The client enables the button on
	 *                           this, the server refuses with the real reason, and the reason
	 *                           is shown.
	 * @param lastChasedAt       when the client was last chased, read out of the append-only
	 *                           trail. Sent so the panel can show it and the board can retire
	 *                           the row from the pending-docs queue without stamping a clock of
	 *                           its own — which is what it was doing, and it showed the
	 *                           browser's time rather than the recorded one.
	 */
	public record ChecklistView(
			UUID caseId,
			String driveLink,
			List<ChecklistItemView> items,
			int total,
			int complete,
			boolean checklistSatisfied,
			Instant lastChasedAt) {
	}

	/**
	 * One card on the Coordinator's board.
	 *
	 * <p>No deal value: this screen is about documents, and the field is role-restricted
	 * (invariant 3). Aging is not a field either — {@code stageEnteredAt} is sent and the
	 * client derives the hours, so the amber/red bands stay live between reloads instead of
	 * being frozen at whatever the server computed.
	 */
	public record ChecklistCard(
			UUID id,
			String caseCode,
			String clientName,
			ServiceType serviceType,
			Instant deadline,
			SlaStatus slaStatus,
			ExceptionState exceptionState,
			Instant stageEnteredAt,
			UUID assignedCoordinator,
			boolean paid,
			int total,
			int complete,
			boolean checklistSatisfied,
			Instant lastChasedAt) {

		static ChecklistCard of(ChecklistService.BoardRow row) {
			Case subject = row.subject();
			return new ChecklistCard(subject.getId(), subject.getCaseCode(), row.clientName(),
					subject.getServiceType(), subject.getDeadline(), subject.getSlaStatus(),
					subject.getExceptionState(), subject.getStageEnteredAt(), subject.getAssignedCoordinator(),
					subject.isPaid(), row.total(), row.complete(), row.satisfied(), row.lastChasedAt());
		}
	}

	/** A document the service-type template did not know this case would need. */
	public record AddItemRequest(@NotBlank @Size(max = 200) String label) {
	}

	public record SetStatusRequest(@NotNull ChecklistItemStatus status) {
	}

	private final ChecklistService checklists;

	ChecklistController(ChecklistService checklists) {
		this.checklists = checklists;
	}

	/**
	 * @param brandId the GM's brand switcher. Narrowing only, exactly as on the production
	 *                board: it is applied after the scoped read, so naming a brand the caller
	 *                cannot read yields an empty board rather than that brand's cases.
	 */
	@GetMapping("/api/checklists/board")
	@PreAuthorize(COORDINATION)
	public ApiResponse<List<ChecklistCard>> board(@RequestParam(required = false) UUID brandId) {
		return ApiResponse.ok(checklists.board(brandId).stream().map(ChecklistCard::of).toList());
	}

	/**
	 * No {@code @PreAuthorize}: every role that can open a case can see what it is waiting
	 * for, and the scoped load in the service is what decides whether they can open it. The
	 * same reasoning as the timeline and the notification centre — a role gate here would
	 * refuse the PM whose case it is.
	 */
	@GetMapping("/api/cases/{id}/checklist")
	public ApiResponse<ChecklistView> checklist(@PathVariable UUID id) {
		return ApiResponse.ok(view(id));
	}

	@PatchMapping("/api/cases/{id}/checklist/{itemId}")
	@PreAuthorize(COORDINATION)
	public ApiResponse<ChecklistView> setStatus(@PathVariable UUID id, @PathVariable UUID itemId,
			@Valid @RequestBody SetStatusRequest request) {
		checklists.setStatus(id, itemId, request.status());
		return ApiResponse.ok(view(id));
	}

	@PostMapping("/api/cases/{id}/checklist/items")
	@PreAuthorize(COORDINATION)
	public ApiResponse<ChecklistView> addItem(@PathVariable UUID id, @Valid @RequestBody AddItemRequest request) {
		checklists.addItem(id, request.label().trim());
		return ApiResponse.ok(view(id));
	}

	/**
	 * Sends the client a chase — via GHL, which is the only thing that talks to clients.
	 * EvalOS emits {@code checklist.reminder} and nothing else (invariant 14).
	 *
	 * <p>Answers the refreshed checklist rather than a timestamp, so the caller re-reads the
	 * one authoritative view instead of holding a value the trail would have to agree with.
	 * The view carries {@code lastChasedAt} for exactly that reason — it is the trail's answer,
	 * and the client showing its own clock instead was the drift this sentence claimed to have
	 * prevented.
	 */
	@PostMapping("/api/cases/{id}/chase")
	@PreAuthorize(COORDINATION)
	public ApiResponse<ChecklistView> chase(@PathVariable UUID id) {
		checklists.chase(id);
		return ApiResponse.ok(view(id));
	}

	/**
	 * Every write answers the whole checklist back.
	 *
	 * <p>A status change can flip {@code checklistSatisfied}, and adding an item always
	 * un-flips it, so returning the single row that changed would leave the client to
	 * recompute the state of the screen — which is the recomputation this endpoint exists to
	 * avoid. The re-read goes through the same scoped load, so it also proves the caller
	 * could still see the case after the write.
	 */
	private ChecklistView view(UUID caseId) {
		ChecklistService.CaseChecklist checklist = checklists.forCase(caseId);
		return new ChecklistView(
				checklist.subject().getId(),
				checklist.subject().getDriveLink(),
				checklist.items().stream().map(ChecklistItemView::of).toList(),
				checklist.items().size(),
				checklist.complete(),
				checklist.satisfied(),
				checklist.lastChasedAt());
	}
}
