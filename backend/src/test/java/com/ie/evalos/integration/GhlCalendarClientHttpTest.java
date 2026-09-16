package com.ie.evalos.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.ie.evalos.service.AuditService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What the calendar calls put on the wire, and what they say when the grant is missing.
 *
 * <p>Two of these pin things that are invisible in review and expensive live:
 * {@link #bookingDoesNotSuppressGhlsNotifications} — because suppressing them books a meeting the
 * client never hears about — and {@link #bookingDoesNotSkipSlotValidation}, because skipping it
 * turns a double-booking into a silent success.
 */
class GhlCalendarClientHttpTest {

	private static final String LOCATION = "WY6bW2xUCI8Tz8gw7aLJ";

	private HttpServer server;
	private final List<String> paths = new ArrayList<>();
	private final List<String> methods = new ArrayList<>();
	private final List<String> bodies = new ArrayList<>();
	private volatile int status = 200;
	private volatile String body = "{}";

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::respond);
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private void respond(HttpExchange exchange) throws IOException {
		paths.add(exchange.getRequestURI().getPath()
				+ (exchange.getRequestURI().getQuery() == null ? "" : "?" + exchange.getRequestURI().getQuery()));
		methods.add(exchange.getRequestMethod());
		bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		byte[] payload = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, payload.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(payload);
		}
	}

	private final AuditService audit = mock(AuditService.class);

	private GhlCalendarClient client() {
		return new GhlCalendarClient(new GhlHttp("http://127.0.0.1:" + server.getAddress().getPort(),
				"2021-07-28", "pit-test-token", LOCATION, Duration.ofSeconds(5)), audit);
	}

	private static final String BOOKED = """
			{"id":"appt_1","calendarId":"cal_1","contactId":"c1","title":"Discovery call",
			 "startTime":"2026-10-01T14:00:00Z","endTime":"2026-10-01T14:30:00Z",
			 "appointmentStatus":"confirmed"}""";

	@Test
	void theCalendarListIsAddressedByLocationId() {
		body = """
				{"calendars":[{"id":"cal_1","name":"Sales calls"},{"id":"cal_2","name":"Onboarding"}]}""";

		assertThat(client().calendars())
				.extracting(GhlCalendarClient.CalendarOption::name)
				.containsExactly("Sales calls", "Onboarding");
		// `locationId`, unlike the invoice API's altId/altType. GHL is not consistent between
		// its own endpoints, so each one is pinned where it is used.
		assertThat(paths).singleElement().asString().contains("/calendars/").contains("locationId=" + LOCATION);
	}

	/**
	 * GHL returns the appointment's fields at the top level, not wrapped in an envelope like
	 * the task and opportunity endpoints. Assumed wrappers are how a client silently reads null.
	 */
	@Test
	void aBookingReadsTheAppointmentFromTheTopLevelOfTheResponse() {
		body = BOOKED;

		GhlCalendarClient.Meeting meeting =
				client().book("cal_1", "c1", "opp_1", "pipe_1", "Discovery call",
						"2026-10-01T14:00:00Z", "2026-10-01T14:30:00Z", null, null, null, null, false);

		assertThat(meeting.id()).isEqualTo("appt_1");
		assertThat(meeting.status()).isEqualTo("confirmed");
		assertThat(methods).containsExactly("POST");
		assertThat(paths).singleElement().asString().isEqualTo("/calendars/events/appointments");
	}

	/**
	 * <strong>{@code toNotify} must not be sent as false.</strong> GHL defaults it to true, and
	 * that default is what runs the automations that actually tell the client about the meeting.
	 * EvalOS has no outbound channel of its own (invariant 14), so suppressing GHL's is the
	 * difference between an invitation and a private note to nobody.
	 */
	@Test
	void bookingDoesNotSuppressGhlsNotifications() {
		body = BOOKED;

		client().book("cal_1", "c1", "opp_1", "pipe_1", "Discovery call",
				"2026-10-01T14:00:00Z", "2026-10-01T14:30:00Z", null, null, null, null, false);

		assertThat(bodies).singleElement().asString().doesNotContain("toNotify");
	}

	/**
	 * <strong>And it must not send {@code ignoreFreeSlotValidation}.</strong> With validation on,
	 * GHL refuses a collision and the salesperson sees it. With it off, a double-booking succeeds
	 * quietly and two people arrive for the same slot.
	 */
	@Test
	void bookingDoesNotSkipSlotValidation() {
		body = BOOKED;

		client().book("cal_1", "c1", "opp_1", "pipe_1", "Discovery call",
				"2026-10-01T14:00:00Z", "2026-10-01T14:30:00Z", null, null, null, null, false);

		assertThat(bodies).singleElement().asString()
				.doesNotContain("ignoreFreeSlotValidation")
				.doesNotContain("ignoreDateRange");
	}

	/**
	 * The meeting is audited against the DEAL, so it lands in the same history as that deal's
	 * stage moves and follow-ups rather than under an appointment id nothing else mentions.
	 */
	@Test
	void aBookingIsAuditedAgainstTheOpportunity() {
		body = BOOKED;

		client().book("cal_1", "c1", "opp_1", "pipe_1", "Discovery call",
				"2026-10-01T14:00:00Z", "2026-10-01T14:30:00Z", null, null, null, null, false);

		verify(audit).recordEvent(org.mockito.ArgumentMatchers.eq("GHL_OPPORTUNITY"), any(), any(),
				any(), any(), any());
	}

	@Test
	void aRescheduleIsAPutOnTheAppointment() {
		body = BOOKED;

		client().reschedule("appt_1", "opp_1", "pipe_1", "2026-10-02T14:00:00Z", "2026-10-02T14:30:00Z");

		assertThat(methods).containsExactly("PUT");
		assertThat(paths).singleElement().asString().isEqualTo("/calendars/events/appointments/appt_1");
	}

	/**
	 * The scope hint, which is the whole reason this client decorates its refusals: every other
	 * GHL call in EvalOS works on this token, so a bare 401 sends the reader to the wrong place.
	 */
	@Test
	void aRefusedBookingNamesTheMissingScope() {
		status = 401;
		body = "{\"message\":\"scope not authorized\"}";

		assertThatThrownBy(() -> client().book("cal_1", "c1", "opp_1", "pipe_1", "x",
				"2026-10-01T14:00:00Z", "2026-10-01T14:30:00Z", null, null, null, null, false))
				.isInstanceOf(GhlUnavailableException.class)
				.hasMessageContaining(GhlCalendarClient.WRITE_SCOPE);
	}

	/**
	 * And only 401/403 get the hint. A 500 means something else, and a scope hint on it would
	 * teach the reader to ignore the hint — the same rule {@code GhlInvoiceClient} follows.
	 */
	@Test
	void anUpstreamFaultIsNotBlamedOnTheScope() {
		status = 500;
		body = "{}";

		assertThatThrownBy(() -> client().book("cal_1", "c1", "opp_1", "pipe_1", "x",
				"2026-10-01T14:00:00Z", "2026-10-01T14:30:00Z", null, null, null, null, false))
				.isInstanceOf(GhlUnavailableException.class)
				.hasMessageNotContaining(GhlCalendarClient.WRITE_SCOPE);
	}

	/** A response with no id is a failure, not an empty meeting the caller discovers later. */
	@Test
	void aResponseWithNoIdIsRefusedRatherThanReturnedEmpty() {
		body = "{}";

		assertThatThrownBy(() -> client().book("cal_1", "c1", "opp_1", "pipe_1", "x",
				"2026-10-01T14:00:00Z", "2026-10-01T14:30:00Z", null, null, null, null, false))
				.isInstanceOf(GhlUnavailableException.class);
		verify(audit, never()).recordEvent(any(), any(), any(), any(), any(), any());
	}

	// --- the contact-scoped read the Client Portal uses ------------------------------

	/**
	 * The live payload, trimmed. Every oddity in it is real and was copied from GHL on
	 * 2026-09-11 — including the misspelled {@code appoinmentStatus}, which GHL sends
	 * <em>alongside</em> the correct spelling.
	 */
	private static final String CONTACT_EVENTS = """
			{"events":[
			 {"id":"appt_1","title":"RFE Meeting","startTime":"2026-09-13 12:30:00",
			  "endTime":"2026-09-13 13:00:00","appointmentStatus":"confirmed",
			  "appoinmentStatus":"confirmed","address":"https://meet.google.com/osk-fwdz-pty",
			  "assignedUserId":"UyzVHyoEYr3uTDjRBRUm","notes":"internal only",
			  "calendarId":"cal_1","contactId":"c1","deleted":false,
			  "appointmentMeta":{"defaultFormDetails":{"email":"someone@example.com"}}},
			 {"id":"appt_gone","title":"Cancelled and removed","startTime":"2026-09-14 09:00:00",
			  "endTime":"2026-09-14 09:30:00","appointmentStatus":"cancelled","deleted":true}
			]}""";

	@Test
	void aContactsMeetingsAreReadFromTheContactsEndpoint() {
		body = CONTACT_EVENTS;

		List<GhlCalendarClient.ClientMeeting> found = client().forContact("c1");

		assertThat(found).hasSize(1);
		assertThat(found.get(0).title()).isEqualTo("RFE Meeting");
		assertThat(found.get(0).location()).isEqualTo("https://meet.google.com/osk-fwdz-pty");
		// `/contacts/{id}/appointments`, not a calendars path — and it needs contacts.readonly,
		// not a calendar scope. Pinned because the class name suggests otherwise.
		assertThat(paths).singleElement().asString().isEqualTo("/contacts/c1/appointments");
	}

	/**
	 * <strong>A deleted appointment still comes back in the list.</strong> Showing a client a
	 * meeting that is not happening is worse than showing none, so it is filtered.
	 */
	@Test
	void aDeletedAppointmentIsNotShownToTheClient() {
		body = CONTACT_EVENTS;

		assertThat(client().forContact("c1"))
				.extracting(GhlCalendarClient.ClientMeeting::id)
				.containsExactly("appt_1")
				.doesNotContain("appt_gone");
	}

	/**
	 * <strong>GHL's times are not ISO-8601 and must survive unparsed.</strong> A space instead
	 * of a {@code T} and no offset at all — {@code Instant.parse} throws on them. Passing them
	 * through is deliberate: with no zone in the payload, any parse invents one, and inventing
	 * UTC would show a Pacific client a meeting seven hours out. The write side of the same API
	 * takes proper ISO with an offset, which is exactly how easy it is to assume symmetry.
	 */
	@Test
	void ghlsNonIsoTimesArePassedThroughRatherThanParsedIntoTheWrongInstant() {
		body = CONTACT_EVENTS;

		GhlCalendarClient.ClientMeeting meeting = client().forContact("c1").get(0);

		assertThat(meeting.startsAt()).isEqualTo("2026-09-13 12:30:00");
		assertThatThrownBy(() -> java.time.Instant.parse(meeting.startsAt()))
				.isInstanceOf(java.time.format.DateTimeParseException.class);
	}

	/**
	 * The correctly spelled {@code appointmentStatus} is read. GHL also sends
	 * {@code appoinmentStatus} — its own typo — and that is ignored rather than used as a
	 * fallback, because a fallback onto a typo is a dependency on GHL never fixing it.
	 */
	@Test
	void theStatusIsReadFromTheCorrectlySpelledField() {
		body = """
				{"events":[{"id":"a","appointmentStatus":"confirmed","appoinmentStatus":"WRONG",
				 "startTime":"2026-09-13 12:30:00","endTime":"2026-09-13 13:00:00"}]}""";

		assertThat(client().forContact("c1").get(0).status()).isEqualTo("confirmed");
	}

	/** No meetings is an empty list, not a failure — a client with none is the normal case. */
	@Test
	void aContactWithNoMeetingsIsAnEmptyList() {
		body = """
				{"events":[]}""";

		assertThat(client().forContact("c1")).isEmpty();
	}
}
