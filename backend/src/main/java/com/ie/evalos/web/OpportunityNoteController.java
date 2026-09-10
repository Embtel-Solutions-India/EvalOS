package com.ie.evalos.web;

import java.util.List;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.OpportunityNoteService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The note stream on an opportunity — one route, both desks.
 *
 * <p><strong>Not under {@code /marketing} or {@code /sales}, and that is the point.</strong> A
 * lead is nurtured by Marketing, promoted by GHL's automation, and closed by Sales; the
 * conversation belongs to the <em>deal</em>, not to whichever desk currently holds it. Filing
 * these routes under one desk would have meant the other reading its own history through a URL
 * naming somebody else's job — or, worse, getting a second table.
 *
 * <p><strong>There is no PUT and no DELETE, deliberately.</strong> The database refuses both
 * with a trigger, but the absence of a route is what stops anyone writing the client code that
 * would discover that the hard way. This is the client conversation rather than a record of it:
 * a correction is a new note.
 */
@RestController
@RequestMapping("/api/opportunities/{opportunityId}/notes")
public class OpportunityNoteController {

	public record AddNoteRequest(@NotBlank String body) {
	}

	private final OpportunityNoteService notes;

	OpportunityNoteController(OpportunityNoteService notes) {
		this.notes = notes;
	}

	/**
	 * The GM is absent from both routes, and that is a decision rather than an oversight.
	 *
	 * <p>The GM reads every pipeline's board (Unit 38's union), so admitting them here would be
	 * consistent — but {@code PipelineScope} answers "is this opportunity in <em>my</em>
	 * pipeline", and a GM owns none. They would be refused by the scope check anyway, so the
	 * role gate says so plainly instead of letting them reach a 403. If oversight ever needs to
	 * read the conversation, that is a widening of {@code PipelineScope} and an argument to have
	 * on its own.
	 */
	@GetMapping
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING')")
	public ApiResponse<List<OpportunityNoteService.Note>> list(@PathVariable String opportunityId) {
		return ApiResponse.ok(notes.on(opportunityId));
	}

	@PostMapping
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING')")
	public ApiResponse<OpportunityNoteService.Note> add(@PathVariable String opportunityId,
			@RequestBody @Valid AddNoteRequest request) {
		return ApiResponse.ok(notes.add(opportunityId, request.body()));
	}
}
