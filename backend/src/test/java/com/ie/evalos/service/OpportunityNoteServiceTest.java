package com.ie.evalos.service;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.OpportunityNote;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.OpportunityNoteRepository;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One note stream, two desks.
 *
 * <p>{@link #bothDesksWriteToTheSameStream} is the test this class exists for: a lead nurtured by
 * Marketing and closed by Sales must have one conversation, not two. It works because notes are
 * keyed on the opportunity and a pipeline move keeps the id.
 */
class OpportunityNoteServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID MEMBER = UUID.randomUUID();
	private static final String MINE = "pipe_mine";
	private static final String OPPORTUNITY = "opp_1";

	private final OpportunityNoteRepository notes = mock(OpportunityNoteRepository.class);
	private final OpportunityMirrorService deals = mock(OpportunityMirrorService.class);
	private final SyncOutboxService outbox = mock(SyncOutboxService.class);
	private final com.ie.evalos.repository.GhlNoteRepository ghlNotes =
			mock(com.ie.evalos.repository.GhlNoteRepository.class);
	private final com.ie.evalos.repository.OpportunityNoteGhlLinkRepository links =
			mock(com.ie.evalos.repository.OpportunityNoteGhlLinkRepository.class);
	private final com.ie.evalos.repository.TeamMemberRepository teamMembers =
			mock(com.ie.evalos.repository.TeamMemberRepository.class);
	private final com.ie.evalos.repository.GhlUserRepository ghlUsers =
			mock(com.ie.evalos.repository.GhlUserRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final OpportunityNoteService service = new OpportunityNoteService(notes,
			new PipelineScope(deals), outbox, ghlNotes, links, teamMembers, ghlUsers, audit);

	/**
	 * The mirror row the scope check reads before it decides.
	 *
	 * <p>Needed since {@code PipelineScope.requireVisible} landed: the read path now resolves the
	 * deal first — it has to, because a GM's answer depends on the deal's brand rather than on the
	 * caller's, which is null for them. Without this stub every read here refuses on a deal that
	 * does not exist rather than on the scope it is meant to be testing.
	 */
	private void givenTheDealExists() {
		Opportunity deal = new Opportunity(BRAND, OPPORTUNITY, UUID.randomUUID());
		ReflectionTestUtils.setField(deal, "id", UUID.randomUUID());
		when(deals.byGhlId(OPPORTUNITY)).thenReturn(java.util.Optional.of(deal));
	}

	private void authenticate(Role role, String pipelineId) {
		StaffPrincipal principal = new StaffPrincipal(MEMBER, "desk@ie.test", "Desk", role, BRAND, null,
				pipelineId, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	/**
	 * <strong>The handoff works because the stream is keyed on the deal, not the desk.</strong>
	 *
	 * <p>GHL's automation moves a qualified lead into the sales pipeline with
	 * {@code PUT /opportunities/{id}}, which is an update in place — the id survives. So a
	 * salesperson opening a promoted deal reads the marketer's notes without any migration step,
	 * and this asserts both desks reach the same key.
	 */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.INCLUDE, names = { "SALES", "MARKETING" })
	void bothDesksWriteToTheSameStream(Role role) {
		authenticate(role, MINE);
		when(deals.isOnPipeline(OPPORTUNITY, MINE)).thenReturn(true);
		givenTheDealExists();
		when(notes.save(any())).thenAnswer((call) -> call.getArgument(0));

		service.add(OPPORTUNITY, "from " + role);

		ArgumentCaptor<OpportunityNote> saved = ArgumentCaptor.forClass(OpportunityNote.class);
		verify(notes).save(saved.capture());
		assertThat(saved.getValue().getGhlOpportunityId()).isEqualTo(OPPORTUNITY);
	}

	/**
	 * A note is labelled from the principal, never from the request.
	 *
	 * <p>The brand and pipeline on the row are what a scoped read filters by later. If either
	 * could come from the caller, a desk could write into another's stream and it would read
	 * back as legitimately theirs.
	 */
	@Test
	void aNoteCarriesTheCallersBrandAndPipeline() {
		authenticate(Role.SALES, MINE);
		when(deals.isOnPipeline(OPPORTUNITY, MINE)).thenReturn(true);
		givenTheDealExists();
		when(notes.save(any())).thenAnswer((call) -> call.getArgument(0));

		service.add(OPPORTUNITY, "  Client wants expedited  ");

		ArgumentCaptor<OpportunityNote> saved = ArgumentCaptor.forClass(OpportunityNote.class);
		verify(notes).save(saved.capture());
		assertThat(saved.getValue().getBrandId()).isEqualTo(BRAND);
		assertThat(saved.getValue().getGhlPipelineId()).isEqualTo(MINE);
		assertThat(saved.getValue().getAuthorId()).isEqualTo(MEMBER);
		// Trimmed, so whitespace cannot slip past the blank check into an empty-looking note.
		assertThat(saved.getValue().getBody()).isEqualTo("Client wants expedited");
	}

	@Test
	void anotherDesksStreamIsRefused() {
		authenticate(Role.SALES, MINE);
		when(deals.isOnPipeline("opp_theirs", MINE)).thenReturn(false);

		assertThatThrownBy(() -> service.on("opp_theirs")).isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> service.add("opp_theirs", "hello"))
				.isInstanceOf(ForbiddenException.class);

		verify(notes, never()).save(any());
	}

	@Test
	void aCallerWithNoPipelineIsRefused() {
		authenticate(Role.SALES, null);

		assertThatThrownBy(() -> service.on(OPPORTUNITY)).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void aBlankNoteIsRefused() {
		authenticate(Role.MARKETING, MINE);
		when(deals.isOnPipeline(OPPORTUNITY, MINE)).thenReturn(true);
		givenTheDealExists();

		assertThatThrownBy(() -> service.add(OPPORTUNITY, "   "))
				.isInstanceOf(InvalidRequestException.class);

		verify(notes, never()).save(any());
	}

	@Test
	void theStreamIsReadNewestFirstForOneDeal() {
		authenticate(Role.MARKETING, MINE);
		when(deals.isOnPipeline(OPPORTUNITY, MINE)).thenReturn(true);
		givenTheDealExists();
		when(notes.findByBrandIdAndGhlOpportunityIdOrderByCreatedAtDesc(BRAND, OPPORTUNITY))
				.thenReturn(List.of(note("second", "2026-09-02T09:00:00Z"), note("first", "2026-09-01T09:00:00Z")));

		assertThat(service.on(OPPORTUNITY)).extracting(OpportunityNoteService.Note::body)
				.containsExactly("second", "first");
		verify(notes, never()).findAll();
	}

	// --- Unit 54: two-way ---------------------------------------------------------------

	private static OpportunityNote note(String body, String at) {
		OpportunityNote note = new OpportunityNote(OPPORTUNITY, BRAND, MINE, MEMBER, body);
		ReflectionTestUtils.setField(note, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(note, "createdAt", java.time.Instant.parse(at));
		return note;
	}

	private static com.ie.evalos.domain.GhlNote ghlNote(String ghlId, String opportunityId, String body,
			String at) {
		com.ie.evalos.domain.GhlNote note = new com.ie.evalos.domain.GhlNote(BRAND, ghlId, "contact-1",
				opportunityId);
		ReflectionTestUtils.setField(note, "id", UUID.randomUUID());
		note.syncFromGhl(null, body, null, java.time.Instant.parse(at), opportunityId);
		return note;
	}

	/** A written note is queued for GHL — never sent inline. */
	@Test
	void aNewNoteIsQueuedForGhl() {
		authenticate(Role.SALES, MINE);
		when(deals.isOnPipeline(OPPORTUNITY, MINE)).thenReturn(true);
		givenTheDealExists();
		when(notes.save(any())).thenAnswer((call) -> {
			OpportunityNote saved = call.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
			return saved;
		});

		OpportunityNoteService.Note added = service.add(OPPORTUNITY, "call went well");

		verify(outbox).enqueue(BRAND, com.ie.evalos.domain.SyncEntity.OPPORTUNITY_NOTE, added.id(),
				com.ie.evalos.domain.SyncOutboxEntry.Intent.UPSERT);
		assertThat(added.inGhl()).isFalse();
	}

	/**
	 * <strong>One timeline, stored apart.</strong> GHL's notes on this deal and on the contact show
	 * beside EvalOS's, newest first; a deleted GHL note does not, and neither does the echo of a note
	 * EvalOS pushed — its EvalOS row is already there.
	 */
	@Test
	void theTimelineMergesGhlNotesAndDropsEchoesAndDeletions() {
		authenticate(Role.SALES, MINE);
		Opportunity deal = new Opportunity(BRAND, OPPORTUNITY, UUID.randomUUID());
		ReflectionTestUtils.setField(deal, "id", UUID.randomUUID());
		deal.syncFromGhl("contact-1", deal.getPipelineId(), "s1", "Deal", null, "open", null, null, null,
				null, null, null);
		when(deals.byGhlId(OPPORTUNITY)).thenReturn(java.util.Optional.of(deal));
		when(deals.isOnPipeline(OPPORTUNITY, MINE)).thenReturn(true);

		OpportunityNote ours = note("ours", "2026-09-03T09:00:00Z");
		when(notes.findByBrandIdAndGhlOpportunityIdOrderByCreatedAtDesc(BRAND, OPPORTUNITY)).thenReturn(List.of(ours));
		com.ie.evalos.domain.GhlNote onDeal = ghlNote("g-deal", OPPORTUNITY, "from GHL", "2026-09-04T09:00:00Z");
		com.ie.evalos.domain.GhlNote echo = ghlNote("g-echo", OPPORTUNITY, "ours (pushed)", "2026-09-03T09:01:00Z");
		com.ie.evalos.domain.GhlNote deleted = ghlNote("g-gone", OPPORTUNITY, "deleted in GHL", "2026-09-02T09:00:00Z");
		deleted.markMissing(java.time.Instant.now());
		com.ie.evalos.domain.GhlNote onContact = ghlNote("g-contact", null, "about the person", "2026-09-01T09:00:00Z");
		when(ghlNotes.findByBrandIdAndGhlOpportunityIdOrderByDateAddedDesc(BRAND, OPPORTUNITY))
				.thenReturn(List.of(onDeal, echo, deleted));
		when(ghlNotes.findByBrandIdAndGhlContactIdAndGhlOpportunityIdIsNull(BRAND, "contact-1"))
				.thenReturn(List.of(onContact));
		when(links.findByBrandIdAndNoteIdIn(org.mockito.ArgumentMatchers.eq(BRAND), any()))
				.thenReturn(List.of(new com.ie.evalos.domain.OpportunityNoteGhlLink(ours.getId(), BRAND, "g-echo", "contact-1")));
		when(links.findByBrandIdAndGhlNoteIdIn(org.mockito.ArgumentMatchers.eq(BRAND), any()))
				.thenReturn(List.of(new com.ie.evalos.domain.OpportunityNoteGhlLink(ours.getId(), BRAND, "g-echo", "contact-1")));

		List<OpportunityNoteService.Note> timeline = service.on(OPPORTUNITY);

		assertThat(timeline).extracting(OpportunityNoteService.Note::body)
				.containsExactly("from GHL", "ours", "about the person");
		assertThat(timeline).extracting(OpportunityNoteService.Note::origin).containsExactly(
				OpportunityNoteService.Origin.GHL, OpportunityNoteService.Origin.EVALOS,
				OpportunityNoteService.Origin.GHL);
		assertThat(timeline.get(1).inGhl()).isTrue();
		assertThat(timeline.get(2).onContact()).isTrue();
	}

	// --- Unit 54a: the author may edit or delete ------------------------------------------

	private OpportunityNote mineOnThisDeal(UUID author) {
		OpportunityNote note = new OpportunityNote(OPPORTUNITY, BRAND, MINE, author, "before");
		ReflectionTestUtils.setField(note, "id", UUID.randomUUID());
		when(notes.findById(note.getId())).thenReturn(java.util.Optional.of(note));
		return note;
	}

	/** The author overwrites it, the edit is audited without the text, and it is queued for GHL. */
	@Test
	void theAuthorCanEditANote() {
		authenticate(Role.SALES, MINE);
		when(deals.isOnPipeline(OPPORTUNITY, MINE)).thenReturn(true);
		givenTheDealExists();
		OpportunityNote note = mineOnThisDeal(MEMBER);

		OpportunityNoteService.Note edited = service.edit(OPPORTUNITY, note.getId(), "  after  ");

		assertThat(note.getBody()).isEqualTo("after");
		assertThat(edited.updatedAt()).isNotNull();
		verify(notes).save(note);
		verify(outbox).enqueue(BRAND, com.ie.evalos.domain.SyncEntity.OPPORTUNITY_NOTE, note.getId(),
				com.ie.evalos.domain.SyncOutboxEntry.Intent.UPSERT);
		verify(audit).recordEvent(org.mockito.ArgumentMatchers.eq("OPPORTUNITY_NOTE"),
				org.mockito.ArgumentMatchers.eq(note.getId()),
				org.mockito.ArgumentMatchers.eq(com.ie.evalos.domain.AuditAction.NOTE_EDITED),
				org.mockito.ArgumentMatchers.eq(MEMBER), org.mockito.ArgumentMatchers.isNull(),
				org.mockito.ArgumentMatchers.argThat((after) -> !after.toString().contains("after")));
	}

	/** Someone else's note is the same 403 as a note that does not exist — and nothing changes. */
	@Test
	void onlyTheAuthorCanEditOrDelete() {
		authenticate(Role.SALES, MINE);
		when(deals.isOnPipeline(OPPORTUNITY, MINE)).thenReturn(true);
		givenTheDealExists();
		OpportunityNote theirs = mineOnThisDeal(UUID.randomUUID());

		assertThatThrownBy(() -> service.edit(OPPORTUNITY, theirs.getId(), "hijack"))
				.isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> service.delete(OPPORTUNITY, theirs.getId()))
				.isInstanceOf(ForbiddenException.class);
		assertThatThrownBy(() -> service.delete(OPPORTUNITY, UUID.randomUUID()))
				.isInstanceOf(ForbiddenException.class);
		assertThat(theirs.getBody()).isEqualTo("before");
		verify(notes, never()).delete(any(OpportunityNote.class));
		verify(outbox, never()).enqueue(any(), any(com.ie.evalos.domain.SyncEntity.class), any(), any());
	}

	/**
	 * A note with no link gets a delete marker carrying the deal's contact (V68), so the drain can
	 * still find a copy that landed during a timed-out push.
	 */
	@Test
	void deletingAnUnlinkedNoteLeavesAMarkerWithTheContact() {
		authenticate(Role.SALES, MINE);
		when(deals.isOnPipeline(OPPORTUNITY, MINE)).thenReturn(true);
		Opportunity deal = new Opportunity(BRAND, OPPORTUNITY, UUID.randomUUID());
		ReflectionTestUtils.setField(deal, "id", UUID.randomUUID());
		deal.syncFromGhl("contact-1", deal.getPipelineId(), "s1", "Deal", null, "open", null, null, null,
				null, null, null);
		when(deals.byGhlId(OPPORTUNITY)).thenReturn(java.util.Optional.of(deal));
		OpportunityNote note = mineOnThisDeal(MEMBER);

		service.delete(OPPORTUNITY, note.getId());

		ArgumentCaptor<com.ie.evalos.domain.OpportunityNoteGhlLink> marker =
				ArgumentCaptor.forClass(com.ie.evalos.domain.OpportunityNoteGhlLink.class);
		verify(links).save(marker.capture());
		assertThat(marker.getValue().getGhlNoteId()).isNull();
		assertThat(marker.getValue().getGhlContactId()).isEqualTo("contact-1");
	}

	/** Deleted outright; the GHL copy is queued for deletion and hidden from the timeline now. */
	@Test
	void theAuthorCanDeleteANote() {
		authenticate(Role.SALES, MINE);
		when(deals.isOnPipeline(OPPORTUNITY, MINE)).thenReturn(true);
		givenTheDealExists();
		OpportunityNote note = mineOnThisDeal(MEMBER);
		when(links.findById(note.getId())).thenReturn(java.util.Optional.of(
				new com.ie.evalos.domain.OpportunityNoteGhlLink(note.getId(), BRAND, "g-1", "contact-1")));
		com.ie.evalos.domain.GhlNote mirrored = ghlNote("g-1", OPPORTUNITY, "before", "2026-09-01T09:00:00Z");
		when(ghlNotes.findByBrandIdAndGhlId(BRAND, "g-1")).thenReturn(java.util.Optional.of(mirrored));

		service.delete(OPPORTUNITY, note.getId());

		verify(notes).delete(note);
		assertThat(mirrored.isLive()).isFalse();
		verify(links, never()).save(any());
		verify(outbox).enqueue(BRAND, com.ie.evalos.domain.SyncEntity.OPPORTUNITY_NOTE, note.getId(),
				com.ie.evalos.domain.SyncOutboxEntry.Intent.DELETE);
	}
}
