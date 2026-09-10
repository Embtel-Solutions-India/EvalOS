package com.ie.evalos.web;

import java.math.BigDecimal;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.SalesDeskService;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may work the sales desk, and the routes that deliberately do not exist.
 *
 * <p>{@link #thereIsNoRouteToMoveADealBetweenPipelines} and {@link #thereIsNoRouteToBookAMeeting}
 * both assert an absence. The first is a design boundary — promotion is GHL's workflow; the
 * second is an ungranted scope, and saying so in a test is how the gap stays visible instead of
 * being rediscovered as "why can't I book a meeting".
 */
@WebMvcTest(controllers = SalesDeskController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class SalesDeskControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final String OPPORTUNITY = "opp_1";

	private static final SalesDeskService.Deal DEAL = new SalesDeskService.Deal(OPPORTUNITY, "c1",
			"Acme Corp", "s2", "open", new BigDecimal("1200"));

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	SalesDeskService desk;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Desk", role,
				role == Role.GM ? null : BRAND_IE, null, "pipe_mine", null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@Test
	void aSalespersonUpdatesTheirDeal() throws Exception {
		given(desk.update(any(), any(), any())).willReturn(DEAL);

		mockMvc.perform(put("/api/sales/opportunities/{id}", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON).content("{\"monetaryValue\":1200}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.monetaryValue").value(1200));
	}

	@Test
	void aSalespersonMovesADealToAnotherStage() throws Exception {
		given(desk.moveToStage(any(), any())).willReturn(DEAL);

		mockMvc.perform(put("/api/sales/opportunities/{id}/stage", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON).content("{\"stageId\":\"s2\"}"))
				.andExpect(status().isOk());
	}

	@Test
	void aSalespersonClosesADeal() throws Exception {
		given(desk.close(any(), any())).willReturn(DEAL);

		mockMvc.perform(put("/api/sales/opportunities/{id}/status", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"won\"}"))
				.andExpect(status().isOk());
	}

	@Test
	void aSalespersonSetsAFollowUp() throws Exception {
		given(desk.followUp(any(), any(), any(), any())).willReturn("task_1");

		mockMvc.perform(post("/api/sales/opportunities/{id}/follow-ups", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"contactId\":\"c1\",\"title\":\"Call back\",\"dueAt\":\"2026-09-18T09:00:00Z\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.ghlTaskId").value("task_1"));
	}

	/**
	 * <strong>Promotion between pipelines is GHL's workflow, not a route here.</strong>
	 *
	 * <p>A button for it would be a second promotion path racing automation the business already
	 * owns — and the two would disagree about which pipeline a deal is in. Asserted as a 404 so
	 * the absence is deliberate rather than merely unimplemented.
	 */
	@Test
	void thereIsNoRouteToMoveADealBetweenPipelines() throws Exception {
		mockMvc.perform(put("/api/sales/opportunities/{id}/pipeline", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON).content("{\"pipelineId\":\"pipe_theirs\"}"))
				.andExpect(status().isNotFound());
	}

	/**
	 * <strong>Meetings are the one thing this unit could not build.</strong>
	 *
	 * <p>{@code POST /calendars/events/appointments} needs {@code calendars/events.write} and
	 * {@code calendars.readonly}, neither of which is granted. Follow-ups shipped because a GHL
	 * task needs only {@code contacts.write}. This test is here so the gap is a stated absence
	 * rather than something discovered by a salesperson looking for the button.
	 */
	@Test
	void thereIsNoRouteToBookAMeeting() throws Exception {
		mockMvc.perform(post("/api/sales/opportunities/{id}/meetings", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound());
	}

	/**
	 * Every role but Sales, by exclusion so a role added later is refused by default.
	 *
	 * <p>MARKETING is refused here and SALES is refused on the marketing desk — the two share a
	 * note stream and a board, and nothing else.
	 */
	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "SALES")
	void everyOtherRoleIsRefused(Role role) throws Exception {
		mockMvc.perform(put("/api/sales/opportunities/{id}/status", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(role))
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"won\"}"))
				.andExpect(status().isForbidden());

		then(desk).should(never()).close(any(), any());
	}

	@Test
	void anonymousIsRefused() throws Exception {
		mockMvc.perform(put("/api/sales/opportunities/{id}/status", OPPORTUNITY)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"won\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void anotherDesksDealIsForbidden() throws Exception {
		willThrow(new ForbiddenException("That opportunity is not in your pipeline"))
				.given(desk).close(any(), any());

		mockMvc.perform(put("/api/sales/opportunities/{id}/status", "opp_theirs")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"won\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void aFollowUpWithNoTitleIsRejectedBeforeTheServiceIsReached() throws Exception {
		mockMvc.perform(post("/api/sales/opportunities/{id}/follow-ups", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"contactId\":\"c1\",\"title\":\"  \",\"dueAt\":\"2026-09-18T09:00:00Z\"}"))
				.andExpect(status().isBadRequest());

		then(desk).should(never()).followUp(any(), any(), any(), any());
	}
}
