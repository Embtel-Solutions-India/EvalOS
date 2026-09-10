package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.OpportunityNote;
import com.ie.evalos.integration.GhlLeadClient;
import com.ie.evalos.repository.OpportunityNoteRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The marketing desk: create a lead, value it, and write notes on it — without opening GHL.
 *
 * <p><strong>This is where the caller's pipeline is checked, and it is the only place.</strong>
 * {@link GhlLeadClient} takes a pipeline id as an argument and trusts it; the principal lives
 * here, so the check lives here. Every method below resolves the pipeline from
 * {@link TenantContext} and never from the request — a request that could name a pipeline would
 * make the whole access model advisory.
 */
@Service
public class MarketingLeadService {

	/** What the desk gets back after opening a lead. */
	public record Lead(String contactId, String opportunityId, String name, BigDecimal monetaryValue,
			boolean created) {
	}

	/** One note, projected for a screen: the author's id, never their password hash or email. */
	public record Note(UUID id, String body, UUID authorId, Instant createdAt) {
	}

	private final GhlLeadClient ghl;
	private final OpportunityNoteRepository notes;
	private final OpportunityCache cache;

	MarketingLeadService(GhlLeadClient ghl, OpportunityNoteRepository notes, OpportunityCache cache) {
		this.ghl = ghl;
		this.notes = notes;
		this.cache = cache;
	}

	/**
	 * Opens a lead: upsert the contact, then upsert an opportunity on the caller's own pipeline.
	 *
	 * <p><strong>Two writes, and the first one succeeding while the second fails is a real
	 * outcome.</strong> There is no transaction across GHL, and there cannot be. What makes that
	 * survivable is that both halves are upserts: the marketer retries, the contact is matched
	 * rather than duplicated, and only the opportunity is created. That is the whole reason
	 * §3a chose upsert over create — not tidiness, recoverability.
	 */
	public Lead openLead(String firstName, String lastName, String email, String phone, String name,
			BigDecimal monetaryValue) {
		String pipelineId = myPipeline();
		if ((email == null || email.isBlank()) && (phone == null || phone.isBlank())) {
			// GHL dedupes a contact on email then phone. With neither, upsert has nothing to
			// match on and every submission creates another contact — so the endpoint that was
			// chosen for its idempotency would quietly stop being idempotent.
			throw new InvalidRequestException(
					"A lead needs an email or a phone number: GHL matches an existing contact on "
							+ "those, and without either every save creates a new one.");
		}

		GhlLeadClient.UpsertedContact contact = ghl.upsertContact(firstName, lastName, email, phone);
		GhlLeadClient.UpsertedOpportunity opportunity = ghl.upsertOpportunity(pipelineId, contact.id(),
				name == null || name.isBlank() ? contact.name() : name, monetaryValue);

		return new Lead(contact.id(), opportunity.id(), opportunity.name(), opportunity.monetaryValue(),
				opportunity.isNew());
	}

	/**
	 * Records the marketer's valuation, and any renaming, on an opportunity they own.
	 *
	 * <p>The valuation is <strong>GHL's {@code monetaryValue}</strong>, not an EvalOS column: GHL
	 * has the field, so a parallel EvalOS estimate would be two numbers that disagree.
	 */
	public Lead value(String opportunityId, String name, BigDecimal monetaryValue) {
		String pipelineId = myPipeline();
		requireInMyPipeline(opportunityId, pipelineId);

		GhlLeadClient.UpsertedOpportunity updated = ghl.updateOpportunity(opportunityId, pipelineId, name,
				monetaryValue, null);
		return new Lead(updated.contactId(), updated.id(), updated.name(), updated.monetaryValue(), false);
	}

	/** One deal's note stream, newest first. */
	public List<Note> notesOn(String opportunityId) {
		requireInMyPipeline(opportunityId, myPipeline());

		return notes.findByGhlOpportunityIdOrderByCreatedAtDesc(opportunityId).stream()
				.map((note) -> new Note(note.getId(), note.getBody(), note.getAuthorId(),
						note.getCreatedAt()))
				.toList();
	}

	/**
	 * Adds a note. Append-only: there is no edit and no delete, here or in the database.
	 *
	 * <p>The note carries the pipeline and the brand at the moment it is written, which is what
	 * lets {@code ScopePredicate} scope it later without joining the droppable cache.
	 */
	@Transactional
	public Note addNote(String opportunityId, String body) {
		TenantContext caller = TenantContext.current();
		String pipelineId = myPipeline();
		requireInMyPipeline(opportunityId, pipelineId);
		if (body == null || body.isBlank()) {
			throw new InvalidRequestException("A note needs a body");
		}

		OpportunityNote saved = notes.save(new OpportunityNote(opportunityId, caller.brandId(), pipelineId,
				caller.memberId(), body.strip()));
		return new Note(saved.getId(), saved.getBody(), saved.getAuthorId(), saved.getCreatedAt());
	}

	/**
	 * The one pipeline this caller owns, or a refusal.
	 *
	 * <p>Read from the principal, never from a request. A {@code MARKETING} member minted before
	 * {@code V39} carries none and is refused rather than defaulted — the same fail-closed rule
	 * {@code ScopePredicate}'s PIPELINE arm applies, and the same safe direction.
	 */
	private String myPipeline() {
		String pipelineId = TenantContext.current().ghlPipelineId();
		if (pipelineId == null) {
			throw new ForbiddenException("You have no GHL pipeline assigned. A GM assigns one.");
		}
		return pipelineId;
	}

	/**
	 * Refuses an opportunity that is not in the caller's pipeline.
	 *
	 * <p><strong>Checked against the cache, and the failure mode is worth stating.</strong> The
	 * cache is droppable, so an opportunity that is real but not yet fetched would be refused —
	 * a false negative. That is the correct direction to be wrong in: a refusal is visible and
	 * recoverable (the board refreshes and the write succeeds), whereas trusting an unverified
	 * id would let a caller write to another desk's deal by guessing.
	 *
	 * <p>The alternative — asking GHL whether the opportunity is in the pipeline — is a second
	 * round trip on every write against a 100-per-10-seconds budget, to close a gap the board
	 * read has already closed for anything the caller can actually see on screen.
	 */
	private void requireInMyPipeline(String opportunityId, String pipelineId) {
		if (!cache.isInPipeline(opportunityId, pipelineId)) {
			// 403, not 404: "no such opportunity" and "not yours" must answer the same, or the
			// response becomes an oracle for which ids exist in the location.
			throw new ForbiddenException("That opportunity is not in your pipeline");
		}
	}
}
