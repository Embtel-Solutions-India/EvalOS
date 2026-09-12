package com.ie.evalos.web;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.integration.GhlCalendarClient;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.security.PortalSecurityConfig;
import com.ie.evalos.security.PortalTokenFilter;
import com.ie.evalos.service.PortalAccessService;
import com.ie.evalos.service.PortalCaseService;
import com.ie.evalos.service.PortalInvoiceService;
import com.ie.evalos.service.PortalMeetingService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The client's meetings: who may see them, and exactly what the payload carries.
 *
 * <p>{@link #thePayloadCarriesTheWhitelistAndNothingElse} is the one that matters and it asserts
 * on the <strong>serialized body</strong> rather than on a field list — the rule Units 14, 15, 35
 * and 41 all follow, because a nested DTO passes a field-name check and still leaks. GHL's
 * appointment payload carries an internal staff id, a staff-written note and the contact's own
 * email and phone; none of it may reach a portal page.
 */
@WebMvcTest(controllers = ClientPortalController.class)
@Import({ PortalSecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = {
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.portal.rate-limit-per-minute=200",
})
class ClientPortalMeetingTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final String CONTACT = "contact_ada";
	private static final String PARTY_TOKEN = "party-token";
	private static final String CASE_TOKEN = "case-token";

	/**
	 * Note the times: a space, no {@code T}, and no offset. That is what GHL actually sends,
	 * and it is why nothing on this path parses them.
	 */
	private static final GhlCalendarClient.ClientMeeting MEETING =
			new GhlCalendarClient.ClientMeeting("appt_1", "RFE Meeting", "2026-09-13 12:30:00",
					"2026-09-13 13:00:00", "confirmed", "https://meet.google.com/osk-fwdz-pty");

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	PortalAccessService portalAccess;

	@MockitoBean
	PortalCaseService portal;

	@MockitoBean
	PortalInvoiceService invoices;

	@MockitoBean
	PortalMeetingService meetings;

	private void givenAPartyLink() {
		given(portalAccess.resolve(PARTY_TOKEN)).willReturn(Optional.of(new PortalPrincipal(
				UUID.randomUUID(), BRAND_IE, null, PortalAudience.CLIENT, null, CONTACT)));
	}

	private void givenACaseLink() {
		given(portalAccess.resolve(CASE_TOKEN)).willReturn(Optional.of(new PortalPrincipal(
				UUID.randomUUID(), BRAND_IE, UUID.randomUUID(), PortalAudience.CLIENT, null)));
	}

	@Test
	void aPartyLinkReadsItsOwnMeetings() throws Exception {
		givenAPartyLink();
		given(meetings.forCaller(any())).willReturn(List.of(MEETING));

		mockMvc.perform(get("/api/portal/client/meetings").header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].title").value("RFE Meeting"))
				.andExpect(jsonPath("$.data[0].status").value("confirmed"))
				.andExpect(jsonPath("$.data[0].location").value("https://meet.google.com/osk-fwdz-pty"));
	}

	/**
	 * <strong>Six fields, and the body says so.</strong>
	 *
	 * <p>Asserted against the raw JSON because that is what leaks. The named absences are the
	 * three things GHL sends that a client must never be handed: {@code assignedUserId} is an
	 * internal staff id, {@code notes} is written by staff for staff (the same reason
	 * {@code opportunity_note} never reaches a client), and {@code appointmentMeta} carries the
	 * contact's own email and phone back at them for no reason.
	 *
	 * <p>{@code calendarId} and {@code contactId} are absent too — the desk's {@code Meeting}
	 * record carries them and this one deliberately does not, because a client has no use for
	 * either and an id on a page is an id somebody will try in a URL.
	 */
	@Test
	void thePayloadCarriesTheWhitelistAndNothingElse() throws Exception {
		givenAPartyLink();
		given(meetings.forCaller(any())).willReturn(List.of(MEETING));

		mockMvc.perform(get("/api/portal/client/meetings").header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("assignedUserId"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("appointmentMeta"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("notes"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("calendarId"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("contactId"))));
	}

	/**
	 * <strong>No stage, no deal value, no sales note — the decision of 2026-09-11.</strong>
	 *
	 * <p>The portal shows a client their invoices and their meetings and nothing else of the
	 * opportunity. Stage names are written for staff ("Warm", "Cold", "Hot") and showing a
	 * prospect that they are currently Cold is a leak no relabelling makes safe. This asserts
	 * the shape rather than trusting the record, so adding a field to
	 * {@code ClientMeeting} fails here first.
	 */
	@Test
	void nothingAboutTheDealItselfReachesTheClient() throws Exception {
		givenAPartyLink();
		given(meetings.forCaller(any())).willReturn(List.of(MEETING));

		mockMvc.perform(get("/api/portal/client/meetings").header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].stage").doesNotExist())
				.andExpect(jsonPath("$.data[0].stageName").doesNotExist())
				.andExpect(jsonPath("$.data[0].monetaryValue").doesNotExist())
				.andExpect(jsonPath("$.data[0].opportunityId").doesNotExist());
	}

	/**
	 * The contact comes off the credential and there is no way to name another — asserted by
	 * handing the route one anyway. The day somebody adds {@code @RequestParam String contactId}
	 * "so support can look one up", this fails.
	 */
	@Test
	void noParameterCanNameAnotherContact() throws Exception {
		givenAPartyLink();
		given(meetings.forCaller(any())).willReturn(List.of());

		mockMvc.perform(get("/api/portal/client/meetings")
				.param("contactId", "contact_someone_else")
				.header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isOk());

		then(meetings).should().forCaller(org.mockito.ArgumentMatchers.argThat(
				(principal) -> CONTACT.equals(principal.ghlContactId())));
	}

	/** A case-scoped credential is refused, exactly as it is for invoices. */
	@Test
	void aCaseScopedLinkIsRefused() throws Exception {
		givenACaseLink();
		willThrow(new ForbiddenException("This link opens one case rather than your account"))
				.given(meetings).forCaller(any());

		mockMvc.perform(get("/api/portal/client/meetings").header(PortalTokenFilter.HEADER, CASE_TOKEN))
				.andExpect(status().isForbidden());
	}

	@Test
	void anUnknownTokenIsRefusedWithoutReachingTheService() throws Exception {
		given(portalAccess.resolve(any())).willReturn(Optional.empty());

		mockMvc.perform(get("/api/portal/client/meetings").header(PortalTokenFilter.HEADER, "nonsense"))
				.andExpect(status().isUnauthorized());

		then(meetings).should(never()).forCaller(any());
	}

	/** GHL being down is a 502, not an empty list that reads as "you have no meetings". */
	@Test
	void ghlBeingUnavailableIsNotReportedAsNoMeetings() throws Exception {
		givenAPartyLink();
		willThrow(new GhlUnavailableException("GHL refused the request with HTTP 500"))
				.given(meetings).forCaller(any());

		mockMvc.perform(get("/api/portal/client/meetings").header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isBadGateway());
	}
}
