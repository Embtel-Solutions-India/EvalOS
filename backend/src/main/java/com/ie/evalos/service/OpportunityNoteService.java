package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.OpportunityNote;
import com.ie.evalos.repository.OpportunityNoteRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The note stream on an opportunity, shared by both desks.
 *
 * <p><strong>One stream, not one per desk, and that is the point of the whole design.</strong>
 * A lead is nurtured by Marketing, promoted into a Sales pipeline by GHL's own automation, and
 * closed by Sales — and the conversation has to survive that handoff. It does, by construction:
 * notes are keyed on {@code ghl_opportunity_id}, and <strong>a pipeline move is an update in
 * place</strong> ({@code PUT /opportunities/{id}} takes a {@code pipelineId}), so the id does not
 * change and no migration step is needed. That was Unit 40's gating check and it passed.
 *
 * <p><strong>Extracted from {@code MarketingLeadService} in Unit 40.</strong> Unit 39 put these
 * two methods on the marketing desk with a note that Sales would share the table; sharing a
 * table through a class named for the other desk is how the second caller ends up with a copy.
 *
 * <p>Append-only. No update, no delete — not here, not on a route, not in the database. A
 * correction is a new note.
 */
@Service
public class OpportunityNoteService {

	/** One note, projected for a screen: the author's id, never their email or hash. */
	public record Note(UUID id, String body, UUID authorId, Instant createdAt) {
	}

	private final OpportunityNoteRepository notes;
	private final PipelineScope scope;

	OpportunityNoteService(OpportunityNoteRepository notes, PipelineScope scope) {
		this.notes = notes;
		this.scope = scope;
	}

	/**
	 * One deal's stream, newest first.
	 *
	 * <p><strong>{@code requireVisible}, not {@code requireMine}</strong>: reading a deal's notes is
	 * reading. A GM holds no pipelines by design (D19e), so the stricter check refused the one role
	 * that is meant to see everything — on a deal their own board had just listed. Writing a note
	 * is still {@code requireMine}, two methods down.
	 */
	public List<Note> on(String opportunityId) {
		scope.requireVisible(opportunityId);

		return notes.findByGhlOpportunityIdOrderByCreatedAtDesc(opportunityId).stream()
				.map((note) -> new Note(note.getId(), note.getBody(), note.getAuthorId(),
						note.getCreatedAt()))
				.toList();
	}

	/**
	 * Adds a note.
	 *
	 * <p>The brand and the pipeline are stamped from the caller's principal at the moment of
	 * writing, which is what lets a note be scoped later without joining the droppable cache —
	 * and what stops a caller writing into another desk's stream.
	 *
	 * <p><strong>The pipeline recorded is the one the deal is in now</strong>, which for a
	 * promoted lead is the sales pipeline even though the earlier notes on it carry marketing's.
	 * That is correct: the column says where the note was written from, and the stream is keyed
	 * on the opportunity, so the history stays whole either way.
	 */
	@Transactional
	public Note add(String opportunityId, String body) {
		String pipelineId = scope.requireMine(opportunityId);
		if (body == null || body.isBlank()) {
			throw new InvalidRequestException("A note needs a body");
		}

		TenantContext caller = TenantContext.current();
		OpportunityNote saved = notes.save(new OpportunityNote(opportunityId, caller.brandId(), pipelineId,
				caller.memberId(), body.strip()));
		return new Note(saved.getId(), saved.getBody(), saved.getAuthorId(), saved.getCreatedAt());
	}
}
