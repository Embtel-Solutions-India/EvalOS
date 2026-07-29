package com.ie.evalos.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.PmApprovalStatus;
import com.ie.evalos.domain.PoolStatus;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.CaseBoardService;
import com.ie.evalos.service.CaseBoardService.BoardRow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The production board, grouped the way it is drawn: five stage columns and three
 * exception lanes.
 *
 * <p>No {@code @PreAuthorize}. Every staff role has a board and none of them can widen
 * it — the scope is applied in the service and a role with nothing assigned simply gets
 * empty columns, which is a screen, not a refusal. What *is* role-dependent is one
 * field, {@code dealValue}, gated by the same list {@code CaseController} uses.
 *
 * <p>Mapped at {@code /api/cases/board}, which is a literal path and therefore matched
 * ahead of {@code CaseController}'s {@code /api/cases/{id}} template. If that ordering
 * ever changes, "board" starts arriving as a case id and this returns 400.
 */
@RestController
@RequestMapping("/api/cases/board")
public class CaseBoardController {

	/**
	 * One card. The deal value is the only role-restricted field; everything else is
	 * what a card draws — client, service, deadline + RAG, owners, and the sub-statuses
	 * the Draft / Report column shows as chips.
	 */
	public record BoardCard(
			UUID id,
			String caseCode,
			String clientName,
			ServiceType serviceType,
			Instant deadline,
			SlaStatus slaStatus,
			Stage currentStage,
			ExceptionState exceptionState,
			PoolStatus poolStatus,
			UUID assignedPm,
			UUID assignedCm,
			UUID assignedCoordinator,
			ExpertSignStatus expertSignStatus,
			PmApprovalStatus pmApprovalStatus,
			ClientApprovalStatus clientApprovalStatus,
			BigDecimal dealValue) {

		static BoardCard of(BoardRow row, TenantContext ctx) {
			Case subject = row.subject();
			return new BoardCard(subject.getId(), subject.getCaseCode(), row.clientName(),
					subject.getServiceType(), subject.getDeadline(), subject.getSlaStatus(),
					subject.getCurrentStage(), subject.getExceptionState(), subject.getPoolStatus(),
					subject.getAssignedPm(), subject.getAssignedCm(), subject.getAssignedCoordinator(),
					subject.getExpertSignStatus(), subject.getPmApprovalStatus(), subject.getClientApprovalStatus(),
					CaseController.SEES_DEAL_VALUE.contains(ctx.role()) ? subject.getDealValue() : null);
		}
	}

	/**
	 * Every stage column and every exception lane, always present so the client draws an
	 * empty column rather than dropping it. A case appears exactly once: in its exception
	 * lane if it holds one, in its stage column otherwise. A case on hold is not also
	 * sitting in Doc Collection — it is not being worked at all, which is the point of
	 * the lane.
	 */
	public record BoardView(
			Map<Stage, List<BoardCard>> stages,
			Map<ExceptionState, List<BoardCard>> exceptions) {
	}

	/** The five columns work moves through. CLOSED is not a column on a production board. */
	private static final List<Stage> COLUMNS = List.of(
			Stage.DOC_COLLECTION,
			Stage.EXPERT_ASSIGNMENT,
			Stage.DRAFT_GENERATION,
			Stage.EXPERT_SIGNING,
			Stage.FINAL_DELIVERY);

	/** The three lanes. {@code NONE} is not a lane — it is the absence of one. */
	private static final List<ExceptionState> LANES = List.of(
			ExceptionState.ON_HOLD_AWAITING_CLIENT,
			ExceptionState.EXPERT_DECLINED_REMATCHING,
			ExceptionState.REFUND_REQUESTED);

	private final CaseBoardService board;

	CaseBoardController(CaseBoardService board) {
		this.board = board;
	}

	/**
	 * @param dueBefore the shell's date filter, narrowing by deadline
	 * @param brandId   the GM's brand switcher. Narrowing only — see
	 *                  {@code CaseBoardService.forCaller}; naming a brand the caller
	 *                  cannot read yields an empty board, not that brand's cases.
	 */
	@GetMapping
	public ApiResponse<BoardView> board(
			@RequestParam(required = false) Instant dueBefore,
			@RequestParam(required = false) UUID brandId) {
		TenantContext ctx = TenantContext.current();
		List<BoardRow> rows = board.forCaller(dueBefore, brandId);

		Map<Stage, List<BoardCard>> stages = new LinkedHashMap<>();
		COLUMNS.forEach(stage -> stages.put(stage, cards(rows, ctx,
				row -> row.subject().getExceptionState() == ExceptionState.NONE
						&& row.subject().getCurrentStage() == stage)));

		Map<ExceptionState, List<BoardCard>> exceptions = new LinkedHashMap<>();
		LANES.forEach(lane -> exceptions.put(lane, cards(rows, ctx,
				row -> row.subject().getExceptionState() == lane)));

		return ApiResponse.ok(new BoardView(stages, exceptions));
	}

	/** Cards for one column or lane, ordered by deadline — soonest first, undated last. */
	private static List<BoardCard> cards(List<BoardRow> rows, TenantContext ctx, Predicate<BoardRow> belongs) {
		return rows.stream()
				.filter(belongs)
				.sorted(Comparator.comparing(row -> row.subject().getDeadline(),
						Comparator.nullsLast(Comparator.naturalOrder())))
				.map(row -> BoardCard.of(row, ctx))
				.toList();
	}
}
