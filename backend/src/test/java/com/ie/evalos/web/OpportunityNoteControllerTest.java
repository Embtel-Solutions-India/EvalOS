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
 * <p>{@link #theDesksCanEditAndDeleteButOversightCannot} covers Unit 54a's two routes: open to the two
 * desks by role, narrowed to the note's author in the service.
 */
@WebMvcTest(controllers = OpportunityNoteController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class OpportunityNoteControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final String OPPORTUNITY = "opp_1";

	private static final OpportunityNoteService.Note NOTE = new OpportunityNoteService.Note(
			UUID.randomUUID(), "Spoke to the client", UUID.randomUUID(),
			Instant.parse("2026-09-11T09:00:00Z"), OpportunityNoteService.Origin.EVALOS, "Desk", null,
			false, false, null);

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
	 * <strong>Edit and delete exist since Unit 54a</strong> — this was the test that they did not,
	 * inverted when the business chose overwrite and hard delete (2026-09-24). A desk reaches both
	 * routes, and the service decides whether it is the author; the GM, who reads but never writes
	 * the conversation, is refused by role before it gets there.
	 */
	@Test
	void theDesksCanEditAndDeleteButOversightCannot() throws Exception {
		UUID noteId = UUID.randomUUID();
		given(notes.edit(OPPORTUNITY, noteId, "rewritten")).willReturn(NOTE);

		mockMvc.perform(put("/api/opportunities/{id}/notes/{noteId}", OPPORTUNITY, noteId)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"rewritten\"}"))
				.andExpect(status().isOk());
		mockMvc.perform(delete("/api/opportunities/{id}/notes/{noteId}", OPPORTUNITY, noteId)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.MARKETING)))
				.andExpect(status().isOk());
		then(notes).should().delete(OPPORTUNITY, noteId);

		mockMvc.perform(delete("/api/opportunities/{id}/notes/{noteId}", OPPORTUNITY, noteId)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isForbidden());
		then(notes).should(org.mockito.Mockito.times(1)).delete(OPPORTUNITY, noteId);
	}

	/**
	 * Production roles are refused: they work cases, and this is the sales conversation.
	 *
	 * <p><strong>The GM and the Brand Manager came off this list on 2026-09-23.</strong> They were
	 * on it because {@code PipelineScope} could only ask "is this <em>my</em> pipeline" and a GM
	 * owns none — {@code 00d} row 73's "implementation consequence hardened into policy", P0.
	 * {@code requireVisible} answers the question properly now, so the gate no longer has to stand
	 * in for a scope model that could not express oversight.
	 */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE,
			names = { "SALES", "MARKETING", "GM", "BRAND_MANAGER" })
	void everyProductionRoleIsRefused(Role role) throws Exception {
		mockMvc.perform(get("/api/opportunities/{id}/notes", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden());

		then(notes).should(never()).on(any());
	}

	/** Oversight reads the conversation — the half of row 73 that was the bug. */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.INCLUDE, names = { "GM", "BRAND_MANAGER" })
	void oversightReadsTheStream(Role role) throws Exception {
		mockMvc.perform(get("/api/opportunities/{id}/notes", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isOk());
	}

	/**
	 * <strong>And does not write into it.</strong> A GM reading the thread is oversight; a GM
	 * posting to it is a second voice in a conversation the desk owns and the client answers back
	 * into. This is the assertion that stops the read widening being copied onto the write.
	 */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.INCLUDE, names = { "GM", "BRAND_MANAGER" })
	void oversightDoesNotWriteIntoIt(Role role) throws Exception {
		mockMvc.perform(post("/api/opportunities/{id}/notes", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(role))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\":\"oversight should not be able to say this\"}"))
				.andExpect(status().isForbidden());

		then(notes).should(never()).add(any(), any());
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
