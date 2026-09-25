package com.ie.evalos.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.GhlNote;
import com.ie.evalos.domain.GhlReference;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.OpportunityNote;
import com.ie.evalos.domain.OpportunityNoteGhlLink;
import com.ie.evalos.domain.SyncEntity;
import com.ie.evalos.domain.SyncOutboxEntry;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.GhlNoteRepository;
import com.ie.evalos.repository.GhlUserRepository;
import com.ie.evalos.repository.OpportunityNoteGhlLinkRepository;
import com.ie.evalos.repository.OpportunityNoteRepository;
import com.ie.evalos.integration.StubGhlWriteClient;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * <p><strong>Its author may overwrite or delete a note</strong> (Unit 54a, 2026-09-24 — the business
 * reversed D24's append-only rule for this table). Nobody else may, and the previous text is not
 * kept: the audit trail records who and when, never the words.
 *
 * <p><strong>Synced both ways since Unit 54, and stored apart.</strong> A note written here is queued
 * and pushed once to the deal's GHL contact by {@code SYNC_OUTBOX}; GHL's own notes, mirrored into
 * {@code ghl_note}, are read back beside these. The two tables never merge — this one is EvalOS's
 * record and that one is a mirror GHL owns — so the merge happens here, at read time.
 */
@Service
public class OpportunityNoteService {

	/** Where a note was written. */
	public enum Origin {
		EVALOS, GHL
	}

	/**
	 * One note, projected for a screen: the author's id and name, never their email or hash.
	 *
	 * @param authorId   a team member's id for an EvalOS note; null for a GHL one, whose author is a
	 *                   GHL user EvalOS holds no row for
	 * @param authorName the display name, or null when it cannot be resolved
	 * @param title      GHL's title; EvalOS notes have none
	 * @param inGhl      for an EvalOS note, whether its push has landed; null for a GHL note
	 * @param updatedAt  when its author last edited it; null if never, and always null for GHL
	 * @param onContact  a GHL note on the contact rather than on this deal — shown on every deal of
	 *                   that contact, and labelled so
	 */
	public record Note(UUID id, String body, UUID authorId, Instant createdAt, Origin origin,
			String authorName, String title, Boolean inGhl, boolean onContact, Instant updatedAt) {
	}

	private final OpportunityNoteRepository notes;
	private final PipelineScope scope;
	private final SyncOutboxService outbox;
	private final GhlNoteRepository ghlNotes;
	private final OpportunityNoteGhlLinkRepository links;
	private final TeamMemberRepository teamMembers;
	private final GhlUserRepository ghlUsers;
	private final AuditService audit;

	OpportunityNoteService(OpportunityNoteRepository notes, PipelineScope scope, SyncOutboxService outbox,
			GhlNoteRepository ghlNotes, OpportunityNoteGhlLinkRepository links,
			TeamMemberRepository teamMembers, GhlUserRepository ghlUsers, AuditService audit) {
		this.audit = audit;
		this.notes = notes;
		this.scope = scope;
		this.outbox = outbox;
		this.ghlNotes = ghlNotes;
		this.links = links;
		this.teamMembers = teamMembers;
		this.ghlUsers = ghlUsers;
	}

	/**
	 * One deal's timeline, newest first: EvalOS's notes and GHL's, together.
	 *
	 * <p><strong>{@code requireVisible}, not {@code requireMine}</strong>: reading a deal's notes is
	 * reading. A GM holds no pipelines by design (D19e), so the stricter check refused the one role
	 * that is meant to see everything — on a deal their own board had just listed. Writing a note
	 * is still {@code requireMine}, two methods down.
	 *
	 * <p><strong>Every read is brand-scoped from the deal</strong>, not from the caller, whose brand
	 * is null for a GM.
	 *
	 * <p>GHL's side is the notes filed under this deal plus the contact's own. A deleted one (stamped
	 * {@code missing_since}) is not drawn, and neither is the echo of a note EvalOS pushed: its EvalOS
	 * row is the one shown.
	 */
	@Transactional(readOnly = true)
	public List<Note> on(String opportunityId) {
		Opportunity deal = scope.requireVisible(opportunityId);
		UUID brand = deal.getBrandId();

		List<OpportunityNote> ours = notes.findByBrandIdAndGhlOpportunityIdOrderByCreatedAtDesc(brand,
				opportunityId);
		List<GhlNote> theirs = new ArrayList<>(
				ghlNotes.findByBrandIdAndGhlOpportunityIdOrderByDateAddedDesc(brand, opportunityId));
		if (deal.getGhlContactId() != null) {
			theirs.addAll(ghlNotes.findByBrandIdAndGhlContactIdAndGhlOpportunityIdIsNull(brand,
					deal.getGhlContactId()));
		}
		theirs.removeIf((note) -> !note.isLive());

		Set<UUID> pushed = ours.isEmpty() ? Set.of()
				: links.findByBrandIdAndNoteIdIn(brand, ours.stream().map(OpportunityNote::getId).toList())
						.stream()
						// A stub's fake id is not a delivery — see StubGhlWriteClient.addContactNote.
						.filter((link) -> link.getGhlNoteId() != null && !StubGhlWriteClient.isStubId(link.getGhlNoteId()))
						.map(OpportunityNoteGhlLink::getNoteId).collect(Collectors.toSet());
		Set<String> echoes = theirs.isEmpty() ? Set.of()
				: links.findByBrandIdAndGhlNoteIdIn(brand, theirs.stream().map(GhlNote::getGhlId).toList())
						.stream().map(OpportunityNoteGhlLink::getGhlNoteId).collect(Collectors.toSet());
		theirs.removeIf((note) -> echoes.contains(note.getGhlId()));

		Map<UUID, String> staff = teamMembers.findAllById(
				ours.stream().map(OpportunityNote::getAuthorId).distinct().toList()).stream()
				.collect(Collectors.toMap(TeamMember::getId, TeamMember::getDisplayName, (a, b) -> a));
		// ponytail: one lookup per distinct GHL author — a handful per deal. Add a findByGhlIdIn if a
		// timeline ever carries dozens of authors.
		Map<String, String> ghlAuthors = theirs.stream().map(GhlNote::getGhlUserId)
				.filter(java.util.Objects::nonNull).distinct()
				.collect(Collectors.toMap((id) -> id, (id) -> ghlUsers.findByBrandIdAndGhlId(brand, id)
						.map(GhlReference.User::getName).orElse("")));

		List<Note> timeline = new ArrayList<>();
		ours.forEach((note) -> timeline.add(new Note(note.getId(), note.getBody(), note.getAuthorId(),
				note.getCreatedAt(), Origin.EVALOS, staff.get(note.getAuthorId()), null,
				pushed.contains(note.getId()), false, note.getUpdatedAt())));
		theirs.forEach((note) -> timeline.add(new Note(note.getId(), note.getBody(), null,
				note.getDateAdded(), Origin.GHL, blankToNull(ghlAuthors.get(note.getGhlUserId())),
				note.getTitle(), null, note.getGhlOpportunityId() == null, null)));
		timeline.sort(Comparator.comparing(Note::createdAt,
				Comparator.nullsFirst(Comparator.<Instant>naturalOrder())).reversed());
		return timeline;
	}

	/**
	 * Adds a note, and queues it for GHL.
	 *
	 * <p>The brand and the pipeline are stamped from the caller's principal at the moment of
	 * writing, which is what lets a note be scoped later without joining the droppable cache —
	 * and what stops a caller writing into another desk's stream.
	 *
	 * <p><strong>The pipeline recorded is the one the deal is in now</strong>, which for a
	 * promoted lead is the sales pipeline even though the earlier notes on it carry marketing's.
	 * That is correct: the column says where the note was written from, and the stream is keyed
	 * on the opportunity, so the history stays whole either way.
	 *
	 * <p><strong>Queued, never sent inline</strong> (D44's rule, applied to notes): GHL being down
	 * costs the note nothing, and the author sees it at once.
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
		enqueueAfterCommit(caller.brandId(), saved.getId(), SyncOutboxEntry.Intent.UPSERT);
		String author = teamMembers.findById(caller.memberId()).map(TeamMember::getDisplayName).orElse(null);
		return new Note(saved.getId(), saved.getBody(), saved.getAuthorId(), saved.getCreatedAt(),
				Origin.EVALOS, author, null, false, false, null);
	}

	/**
	 * Overwrites a note — <strong>its author only</strong> (Unit 54a). The new text replaces the old,
	 * which is not kept; the edit is queued to GHL like the create was.
	 */
	@Transactional
	public Note edit(String opportunityId, UUID noteId, String body) {
		if (body == null || body.isBlank()) {
			throw new InvalidRequestException("A note needs a body");
		}
		OpportunityNote note = authorsOwn(opportunityId, noteId);
		note.edit(body.strip());
		notes.save(note);
		audit.recordEvent("OPPORTUNITY_NOTE", note.getId(), AuditAction.NOTE_EDITED,
				TenantContext.current().memberId(), null, Map.of("ghlOpportunityId", opportunityId));
		enqueueAfterCommit(note.getBrandId(), note.getId(), SyncOutboxEntry.Intent.UPSERT);
		boolean inGhl = links.findById(note.getId())
				.filter((link) -> !StubGhlWriteClient.isStubId(link.getGhlNoteId())).isPresent();
		return new Note(note.getId(), note.getBody(), note.getAuthorId(), note.getCreatedAt(), Origin.EVALOS,
				teamMembers.findById(note.getAuthorId()).map(TeamMember::getDisplayName).orElse(null), null,
				inGhl, false, note.getUpdatedAt());
	}

	/**
	 * Deletes a note outright — <strong>its author only</strong> (Unit 54a). The row is gone; the GHL
	 * copy is deleted by the drain, and until then its link keeps the echo off the timeline. The
	 * mirrored copy is also stamped missing now, so it cannot surface before the next sweep.
	 */
	@Transactional
	public void delete(String opportunityId, UUID noteId) {
		OpportunityNote note = authorsOwn(opportunityId, noteId);
		java.util.Optional<OpportunityNoteGhlLink> link = links.findById(note.getId());
		link.map(OpportunityNoteGhlLink::getGhlNoteId).filter(java.util.Objects::nonNull)
				.flatMap((ghlId) -> ghlNotes.findByBrandIdAndGhlId(note.getBrandId(), ghlId))
				.ifPresent((mirrored) -> {
					mirrored.markMissing(Instant.now());
					ghlNotes.save(mirrored);
				});
		String contact = link.isEmpty() ? scope.requireVisible(opportunityId).getGhlContactId() : null;
		if (link.isEmpty() && contact != null) {
			// **A delete marker** (V68): no GHL id is known, but a push that timed out may still have
			// landed, and once this row is gone nothing else records which contact to look on. The
			// drain searches that contact for the note's reference. A push still in flight overwrites
			// the null with the id it gets back, and the DELETE then finds it directly.
			// No contact means no push can have landed, so then there is no marker to leave.
			links.save(new OpportunityNoteGhlLink(note.getId(), note.getBrandId(), null, contact));
		}
		notes.delete(note);
		audit.recordEvent("OPPORTUNITY_NOTE", note.getId(), AuditAction.NOTE_DELETED,
				TenantContext.current().memberId(), null, Map.of("ghlOpportunityId", opportunityId));
		enqueueAfterCommit(note.getBrandId(), note.getId(), SyncOutboxEntry.Intent.DELETE);
	}

	/**
	 * The note, if the caller wrote it and may still write on this deal. Every other case — no such
	 * note, a note on another deal, someone else's note — is the same 403, so the answer says nothing
	 * about notes the caller cannot touch.
	 */
	private OpportunityNote authorsOwn(String opportunityId, UUID noteId) {
		scope.requireMine(opportunityId);
		UUID caller = TenantContext.current().memberId();
		return notes.findById(noteId)
				.filter((note) -> note.getGhlOpportunityId().equals(opportunityId))
				.filter((note) -> note.getAuthorId().equals(caller))
				.orElseThrow(() -> new ForbiddenException("Only a note's author can change it"));
	}

	/**
	 * Queues a note push <strong>after</strong> the note's transaction commits (Unit 54a).
	 *
	 * <p>That ordering is what lets the drain read a missing note as "deleted": the outbox row can
	 * never exist before the note does. {@code enqueue}'s own REQUIRES_NEW would otherwise commit it
	 * first. Outside a transaction (a unit test) it enqueues at once.
	 */
	private void enqueueAfterCommit(UUID brandId, UUID noteId, SyncOutboxEntry.Intent intent) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			outbox.enqueue(brandId, SyncEntity.OPPORTUNITY_NOTE, noteId, intent);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				outbox.enqueue(brandId, SyncEntity.OPPORTUNITY_NOTE, noteId, intent);
			}
		});
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
