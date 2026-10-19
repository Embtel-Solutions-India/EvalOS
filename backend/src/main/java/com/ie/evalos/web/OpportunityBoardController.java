package com.ie.evalos.web;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.OpportunityBoardService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operational board: the opportunities in the pipeline the caller owns.
 *
 * <p><strong>One route for both desks.</strong> The spec describes a Marketing board and a Sales
 * board; they ask the same question of the same data and differ only in who is asking, so the
 * role gate is the difference rather than a second controller. What a caller sees is decided
 * entirely by their own principal — <strong>there is no pipeline parameter, and there must never
 * be one</strong>. A pipeline id in a query string would make the whole access model advisory.
 *
 * <p><strong>Not GM-only, unlike every other screen over this GHL location.</strong> That is the
 * change Unit 36 paid for: {@code architecture.md} invariant 1's exception used to rest on "EvalOS
 * cannot tell whose brand this location is, so only the cross-brand role may look", and
 * {@code evalos.ghl.sales-brand} now names the brand. A {@code SALES} or {@code MARKETING} caller
 * reaches this because their own row binds them to that brand and to one pipeline within it.
 *
 * <p>The GM still reaches it, and sees the union of that brand's pipelines.
 */
@RestController
@RequestMapping("/api/opportunities")
public class OpportunityBoardController {

	private final OpportunityBoardService board;

	OpportunityBoardController(OpportunityBoardService board) {
		this.board = board;
	}

	@GetMapping("/board")
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING', 'GM', 'ADMIN', 'EXPERT_NETWORK_MANAGER')")
	public ApiResponse<OpportunityBoardService.Board> board() {
		return ApiResponse.ok(board.forCaller());
	}

	/**
	 * One of the caller's pipelines — the board page's tabs and the GM's drop-down. {@code id} only
	 * narrows: a pipeline the caller does not hold is a 403 (see {@code forPipeline}).
	 */
	@GetMapping("/board/pipeline")
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING', 'GM', 'ADMIN', 'EXPERT_NETWORK_MANAGER')")
	public ApiResponse<OpportunityBoardService.Board> pipeline(
			@org.springframework.web.bind.annotation.RequestParam(required = false) String id) {
		return ApiResponse.ok(board.forPipeline(id));
	}

	/**
	 * Refresh for one pipeline: syncs that one only, then answers with it drawn.
	 *
	 * <p><strong>A reconciliation, not a live read</strong> (Unit 46): it brings the mirror's rows forward and
	 * the board is drawn from them as always. <strong>POST because it writes</strong> the mirror. The
	 * all-pipelines {@code POST /board/refresh} was removed on 2026-10-15 (Q25): nothing called it.
	 */
	@PostMapping("/board/pipeline/refresh")
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING', 'GM', 'EXPERT_NETWORK_MANAGER')")
	public ApiResponse<OpportunityBoardService.Board> refreshPipeline(
			@org.springframework.web.bind.annotation.RequestParam(required = false) String id) {
		return ApiResponse.ok(board.syncNow(id));
	}
}
