package com.ie.evalos.web;

import java.math.BigDecimal;
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
import com.ie.evalos.service.MarketingLeadService;

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
 * Who may work the marketing desk, and the routes that deliberately do not exist.
 *
 * <p>{@link #aNoteCannotBeEditedOrDeleted} is the one worth reading: append-only is enforced by
 * a database trigger, but the absence of a route is what stops anyone reaching for it.
 */
@WebMvcTest(controllers = MarketingLeadController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class MarketingLeadControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final String OPPORTUNITY = "opp_1";
	private static final String OPEN_BODY = "{\"firstName\":\"Ada\",\"email\":\"ada@example.test\"}";

	private static final MarketingLeadService.Lead LEAD = new MarketingLeadService.Lead("c1", "o1",
			"Ada Lovelace", new BigDecimal("500"), true);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	MarketingLeadService leads;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Desk", role,
				role == Role.GM ? null : BRAND_IE, null, "pipe_mine", null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@Test
	void aMarketerOpensALead() throws Exception {
		given(leads.openLead(any(), any(), any(), any(), any(), any())).willReturn(LEAD);

		mockMvc.perform(post("/api/marketing/leads").header(HttpHeaders.AUTHORIZATION, bearer(Role.MARKETING))
				.contentType(MediaType.APPLICATION_JSON).content(OPEN_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.contactId").value("c1"))
				.andExpect(jsonPath("$.data.opportunityId").value("o1"))
				.andExpect(jsonPath("$.data.created").value(true));
	}

	@Test
	void aMarketerValuesTheirLead() throws Exception {
		given(leads.value(any(), any(), any())).willReturn(LEAD);

		mockMvc.perform(put("/api/marketing/leads/{id}", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.MARKETING))
				.contentType(MediaType.APPLICATION_JSON).content("{\"monetaryValue\":2500}"))
				.andExpect(status().isOk());
	}

	@Test
	void aMarketerAddsAndReadsNotes() throws Exception {
		given(leads.addNote(any(), any())).willReturn(new MarketingLeadService.Note(UUID.randomUUID(),
				"Spoke to Ada", UUID.randomUUID(), Instant.parse("2026-09-11T09:00:00Z")));
		given(leads.notesOn(OPPORTUNITY)).willReturn(List.of());

		mockMvc.perform(post("/api/marketing/leads/{id}/notes", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.MARKETING))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Spoke to Ada\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.body").value("Spoke to Ada"));

		mockMvc.perform(get("/api/marketing/leads/{id}/notes", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.MARKETING)))
				.andExpect(status().isOk());
	}

	/**
	 * <strong>There is no route to edit or delete a note.</strong>
	 *
	 * <p>The database refuses it with a trigger, but a 405 here is what stops anyone writing the
	 * client code that would have discovered that the hard way. This is the client conversation,
	 * not a record of it — a correction is a new note.
	 */
	@Test
	void aNoteCannotBeEditedOrDeleted() throws Exception {
		mockMvc.perform(put("/api/marketing/leads/{id}/notes/{noteId}", OPPORTUNITY, UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.MARKETING))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"rewritten\"}"))
				.andExpect(status().isNotFound());

		mockMvc.perform(delete("/api/marketing/leads/{id}/notes/{noteId}", OPPORTUNITY, UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.MARKETING)))
				.andExpect(status().isNotFound());
	}

	/**
	 * Every role but Marketing, derived by exclusion so a role added later is refused by default.
	 *
	 * <p><strong>SALES is refused too, and that is deliberate rather than an oversight.</strong>
	 * Sales works the same opportunities from Unit 40's desk and shares the note table — but
	 * *opening* a lead is a marketing act. Widening this route is Unit 40's argument to make,
	 * not a convenience to add because the two desks look similar.
	 */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "MARKETING")
	void everyOtherRoleIsRefused(Role role) throws Exception {
		mockMvc.perform(post("/api/marketing/leads").header(HttpHeaders.AUTHORIZATION, bearer(role))
				.contentType(MediaType.APPLICATION_JSON).content(OPEN_BODY))
				.andExpect(status().isForbidden());

		then(leads).should(never()).openLead(any(), any(), any(), any(), any(), any());
	}

	@Test
	void anonymousIsRefused() throws Exception {
		mockMvc.perform(post("/api/marketing/leads")
				.contentType(MediaType.APPLICATION_JSON).content(OPEN_BODY))
				.andExpect(status().isUnauthorized());
	}

	/** An opportunity outside the caller's pipeline is a 403, carrying no hint that it exists. */
	@Test
	void anotherDesksOpportunityIsForbidden() throws Exception {
		willThrow(new ForbiddenException("That opportunity is not in your pipeline"))
				.given(leads).addNote(any(), any());

		mockMvc.perform(post("/api/marketing/leads/{id}/notes", "opp_theirs")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.MARKETING))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hello\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void aBlankNoteBodyIsRejectedBeforeTheServiceIsReached() throws Exception {
		mockMvc.perform(post("/api/marketing/leads/{id}/notes", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.MARKETING))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"   \"}"))
				.andExpect(status().isBadRequest());

		then(leads).should(never()).addNote(any(), any());
	}
}
