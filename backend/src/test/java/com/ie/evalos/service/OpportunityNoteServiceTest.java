package com.ie.evalos.service;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.OpportunityNote;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.OpportunityNoteRepository;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
	private final OpportunityNoteService service =
			new OpportunityNoteService(notes, new PipelineScope(deals));

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

		assertThatThrownBy(() -> service.add(OPPORTUNITY, "   "))
				.isInstanceOf(InvalidRequestException.class);

		verify(notes, never()).save(any());
	}

	@Test
	void theStreamIsReadNewestFirstForOneDeal() {
		authenticate(Role.MARKETING, MINE);
		when(deals.isOnPipeline(OPPORTUNITY, MINE)).thenReturn(true);
		when(notes.findByGhlOpportunityIdOrderByCreatedAtDesc(OPPORTUNITY))
				.thenReturn(List.of(new OpportunityNote(OPPORTUNITY, BRAND, MINE, MEMBER, "second"),
						new OpportunityNote(OPPORTUNITY, BRAND, MINE, MEMBER, "first")));

		assertThat(service.on(OPPORTUNITY)).extracting(OpportunityNoteService.Note::body)
				.containsExactly("second", "first");
		verify(notes, never()).findAll();
	}
}
