package com.ie.evalos.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.OpportunityNoteService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One note stream, reachable by both desks and by neither's URL.
 *
 * <p>{@link #aNoteCannotBeEditedOrDeleted} is the one worth reading: append-only is enforced by a
 * database trigger, but the absence of a route is what stops anyone writing the client code that
 * would find that out the hard way.
 */
@WebMvcTest(controllers = OpportunityNoteController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class OpportunityNoteControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final String OPPORTUNITY = "opp_1";

	private static final OpportunityNoteService.Note NOTE = new OpportunityNoteService.Note(
			UUID.randomUUID(), "Spoke to the client", UUID.randomUUID(),
			Instant.parse("2026-09-11T09:00:00Z"));

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	OpportunityNoteService notes;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Desk", role,
				role == Role.GM ? null : BRAND_IE, null, "pipe_mine", null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	/**
	 * <strong>Both desks, one route.</strong> A lead is nurtured by Marketing, promoted by GHL's
	 * automation and closed by Sales; the conversation belongs to the deal, not to whichever
	 * desk currently holds it.
	 */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.INCLUDE, names = { "SALES", "MARKETING" })
	void bothDesksReadAndWriteTheStream(Role role) throws Exception {
		given(notes.on(OPPORTUNITY)).willReturn(List.of(NOTE));
		given(notes.add(any(), any())).willReturn(NOTE);

		mockMvc.perform(get("/api/opportunities/{id}/notes", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].body").value("Spoke to the client"));

		mockMvc.perform(post("/api/opportunities/{id}/notes", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(role))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Spoke to the client\"}"))
				.andExpect(status().isOk());
	}

	/**
	 * <strong>There is no route to edit or delete a note.</strong>
	 *
	 * <p>The database refuses both with a trigger, and a 404 here is what stops anyone reaching
	 * for one. This is the client conversation rather than a record of it — a correction is a
	 * new note.
	 */
	@Test
	void aNoteCannotBeEditedOrDeleted() throws Exception {
		mockMvc.perform(put("/api/opportunities/{id}/notes/{noteId}", OPPORTUNITY, UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"rewritten\"}"))
				.andExpect(status().isNotFound());

		mockMvc.perform(delete("/api/opportunities/{id}/notes/{noteId}", OPPORTUNITY, UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES)))
				.andExpect(status().isNotFound());
	}

	/**
	 * Every role but the two desks — including the GM.
	 *
	 * <p>The GM reads every board (Unit 38's union) but owns no pipeline, so
	 * {@code PipelineScope} would refuse them anyway. The gate says so plainly rather than
	 * letting oversight walk into a 403. Widening it is a change to the scope model, not a
	 * role-list edit.
	 */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = { "SALES", "MARKETING" })
	void everyOtherRoleIsRefused(Role role) throws Exception {
		mockMvc.perform(get("/api/opportunities/{id}/notes", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden());

		then(notes).should(never()).on(any());
	}

	@Test
	void anonymousIsRefused() throws Exception {
		mockMvc.perform(get("/api/opportunities/{id}/notes", OPPORTUNITY))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void anotherDesksStreamIsForbidden() throws Exception {
		willThrow(new ForbiddenException("That opportunity is not in your pipeline"))
				.given(notes).on(any());

		mockMvc.perform(get("/api/opportunities/{id}/notes", "opp_theirs")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES)))
				.andExpect(status().isForbidden());
	}

	@Test
	void aBlankBodyIsRejectedBeforeTheServiceIsReached() throws Exception {
		mockMvc.perform(post("/api/opportunities/{id}/notes", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.MARKETING))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"   \"}"))
				.andExpect(status().isBadRequest());

		then(notes).should(never()).add(any(), any());
	}
}
