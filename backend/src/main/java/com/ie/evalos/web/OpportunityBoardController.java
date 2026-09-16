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
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING', 'GM')")
	public ApiResponse<OpportunityBoardService.Board> board() {
		return ApiResponse.ok(board.forCaller());
	}

	/**
	 * Sync the mirror for this caller's pipelines, then answer with the board.
	 *
	 * <p><strong>A reconciliation, not a live read</strong>, and the difference is the point of
	 * Unit 46: an ordinary load draws EvalOS rows and this brings those rows forward first. The
	 * board never reads GHL on a render, with or without this route.
	 *
	 * <p><strong>POST because it writes.</strong> It updates the mirror — that is a side effect, and
	 * a GET that changes rows is one a browser or a proxy will happily repeat.
	 *
	 * <p>Returns the drawn board rather than an acknowledgement, so the screen replaces its state in
	 * one round trip instead of refreshing and then re-reading.
	 */
	@PostMapping("/board/refresh")
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING', 'GM')")
	public ApiResponse<OpportunityBoardService.Board> refresh() {
		return ApiResponse.ok(board.syncNow());
	}
}
