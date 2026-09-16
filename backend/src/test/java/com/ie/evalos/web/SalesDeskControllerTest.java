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
import com.ie.evalos.integration.GhlCalendarClient;
import com.ie.evalos.service.SalesDeskService;
import com.ie.evalos.service.SalesMeetingService;

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
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 * <p>{@link #thereIsNoRouteToMoveADealBetweenPipelines} asserts an absence, and it is a design
 * boundary rather than a gap: promotion between pipelines is GHL's workflow, not a button here.
 *
 * <p><strong>{@code thereIsNoRouteToBookAMeeting} was the other one, and it is gone because the
 * route now exists.</strong> It asserted a 404 on the grounds that the calendar scopes were
 * ungranted — which was never re-probed and turned out to be false for the read scopes. Deleting
 * a deliberate-absence test is the correct move once the absence ends; leaving it would have
 * failed the build, which is exactly what such a test is for.
 */
@WebMvcTest(controllers = SalesDeskController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class SalesDeskControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final String OPPORTUNITY = "opp_1";

	private static final SalesDeskService.Deal DEAL = new SalesDeskService.Deal(OPPORTUNITY, "c1",
			"Acme Corp", "s2", "open", new BigDecimal("1200"));

	private static final GhlCalendarClient.Meeting MEETING = new GhlCalendarClient.Meeting("appt_1",
			"cal_1", "c1", "Discovery call", "2026-10-01T14:00:00Z", "2026-10-01T14:30:00Z",
			"confirmed");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	SalesDeskService desk;

	@MockitoBean
	SalesMeetingService meetings;

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
		given(desk.followUp(any(), any(), any(), any(), any(), any())).willReturn("task_1");

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

	@Test
	void aSalespersonBooksAMeeting() throws Exception {
		given(meetings.book(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any())).willReturn(MEETING);

		mockMvc.perform(post("/api/sales/opportunities/{id}/meetings", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"calendarId":"cal_1","contactId":"c1","title":"Discovery call",
						 "startTime":"2026-10-01T14:00:00Z","endTime":"2026-10-01T14:30:00Z"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value("appt_1"))
				.andExpect(jsonPath("$.data.status").value("confirmed"));
	}

	/**
	 * Bean validation refuses an incomplete booking before the service is reached — so a request
	 * missing a time never becomes a GHL call, and never becomes a zero-length appointment in
	 * somebody's calendar.
	 */
	@Test
	void aBookingWithNoTimesIsRejectedBeforeTheServiceIsReached() throws Exception {
		mockMvc.perform(post("/api/sales/opportunities/{id}/meetings", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"calendarId":"cal_1","contactId":"c1","title":"Discovery call"}"""))
				.andExpect(status().isBadRequest());

		then(meetings).should(never()).book(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
	}

	@Test
	void aSalespersonReschedulesAMeeting() throws Exception {
		given(meetings.reschedule(any(), any(), any(), any())).willReturn(MEETING);

		mockMvc.perform(put("/api/sales/opportunities/{id}/meetings/{appt}", OPPORTUNITY, "appt_1")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"startTime":"2026-10-02T14:00:00Z","endTime":"2026-10-02T14:30:00Z"}"""))
				.andExpect(status().isOk());
	}

	/**
	 * A deal in somebody else's pipeline is refused, and the refusal comes from the same
	 * {@code PipelineScope.requireMine} every other route on this desk starts at — so meetings
	 * inherit the access model rather than restating it.
	 */
	@Test
	void aSalespersonCannotBookAgainstSomebodyElsesDeal() throws Exception {
		willThrow(new ForbiddenException("Not your pipeline"))
				.given(meetings).book(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

		mockMvc.perform(post("/api/sales/opportunities/{id}/meetings", OPPORTUNITY)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.SALES))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"calendarId":"cal_1","contactId":"c1","title":"Discovery call",
						 "startTime":"2026-10-01T14:00:00Z","endTime":"2026-10-01T14:30:00Z"}"""))
				.andExpect(status().isForbidden());
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

		then(desk).should(never()).followUp(any(), any(), any(), any(), any(), any());
	}
}
