package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.OpportunityNoteService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * <p><strong>PUT and DELETE exist since Unit 54a</strong> (2026-09-24): the business chose to let a
 * note's author overwrite or delete it, and {@code V67} dropped the trigger that refused both. The
 * author rule lives in the service, because a role list cannot say "the person who wrote it".
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
	 * <strong>Oversight reads the conversation; only the desk adds to it.</strong>
	 *
	 * <p>This route excluded the GM until 2026-09-23, and the note that did so named its own
	 * expiry: <em>"they would be refused by the scope check anyway… if oversight ever needs to read
	 * the conversation, that is a widening of {@code PipelineScope} and an argument to have on its
	 * own."</em> That widening is {@code PipelineScope.requireVisible}, and the argument was
	 * settled by the rule the business gave: a GM sees everything, a Brand Manager sees their
	 * brand, a desk sees its own pipelines.
	 *
	 * <p><strong>It was never really a policy.</strong> {@code 00d} row 73 calls it "an
	 * implementation consequence hardened into policy" and marks it P0, and §3.1 lists "no role on
	 * earth can read both note streams" as the <em>mechanism</em> behind a communication breakdown
	 * three separate audits found — not as a property worth keeping.
	 *
	 * <p><strong>The POST below is deliberately not widened.</strong> This is the sales
	 * conversation with a client; a GM reading it is oversight, a GM writing into it is a second
	 * voice in a thread the desk owns and the client's answers come back to.
	 */
	@GetMapping
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING', 'GM', 'BRAND_MANAGER')")
	public ApiResponse<List<OpportunityNoteService.Note>> list(@PathVariable String opportunityId) {
		return ApiResponse.ok(notes.on(opportunityId));
	}

	@PostMapping
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING')")
	public ApiResponse<OpportunityNoteService.Note> add(@PathVariable String opportunityId,
			@RequestBody @Valid AddNoteRequest request) {
		return ApiResponse.ok(notes.add(opportunityId, request.body()));
	}

	/**
	 * Overwrites a note (Unit 54a). The same two roles as the POST, and narrower still in the
	 * service: <strong>only the note's author</strong>, which no role list can express.
	 */
	@PutMapping("/{noteId}")
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING')")
	public ApiResponse<OpportunityNoteService.Note> edit(@PathVariable String opportunityId,
			@PathVariable UUID noteId, @RequestBody @Valid AddNoteRequest request) {
		return ApiResponse.ok(notes.edit(opportunityId, noteId, request.body()));
	}

	/** Deletes a note outright (Unit 54a) — its author only, as the edit. */
	@DeleteMapping("/{noteId}")
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING')")
	public ApiResponse<Void> delete(@PathVariable String opportunityId, @PathVariable UUID noteId) {
		notes.delete(opportunityId, noteId);
		return ApiResponse.ok(null);
	}
}
