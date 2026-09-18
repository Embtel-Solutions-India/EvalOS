package com.ie.evalos.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.integration.GhlCalendarClient;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The refusals, which are the only logic here — everything else is a pass-through to GHL.
 *
 * <p>Each one exists because GHL would <em>accept</em> the thing being refused: an appointment
 * with no end, an appointment that ends before it starts, an appointment last Tuesday. A booking
 * GHL accepts and nobody wanted is worse than a refusal, because it is already in someone's
 * calendar by the time it is noticed.
 */
class SalesMeetingServiceTest {

	private static final String OPPORTUNITY = "opp_1";
	private static final String MY_PIPELINE = "pipe_mine";

	private static final java.util.UUID BRAND = java.util.UUID.randomUUID();
	private static final java.util.UUID MEMBER = java.util.UUID.randomUUID();

	private final GhlCalendarClient calendars = mock(GhlCalendarClient.class);
	/** Unit 47: the calendar LIST is the mirror's; slots stay the client's. */
	private final ReferenceMirrorService reference = mock(ReferenceMirrorService.class);
	private final PipelineScope scope = mock(PipelineScope.class);
	private final com.ie.evalos.repository.MeetingRepository meetingRows =
			mock(com.ie.evalos.repository.MeetingRepository.class);
	private final SalesMeetingService meetings =
			new SalesMeetingService(calendars, reference, scope, meetingRows);

	private static String inDays(int days) {
		return Instant.now().plus(days, ChronoUnit.DAYS).toString();
	}

	SalesMeetingServiceTest() {
		when(scope.requireMine(OPPORTUNITY)).thenReturn(MY_PIPELINE);
		// The mirror is written from GHL's answer, so the success paths need one. A null response
		// here would fail as an NPE inside the service and read like a mirror bug rather than a
		// missing stub.
		when(calendars.book(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
				.thenReturn(new GhlCalendarClient.Meeting("appt_new", "cal_1", "c1", "Discovery call",
						null, null, "booked"));
		when(calendars.reschedule(any(), any(), any(), any(), any()))
				.thenReturn(new GhlCalendarClient.Meeting("appt_1", "cal_1", "c1", "Discovery call",
						null, null, "booked"));
		when(meetingRows.findByBrandIdAndGhlAppointmentId(any(), any()))
				.thenReturn(java.util.Optional.empty());
	}

	/**
	 * `TenantContext.current()` reads Spring's security context, and the mirror needs the caller's
	 * brand and member id. Set per test and cleared after, so one test's principal cannot leak
	 * into the next through the thread-local.
	 */
	@org.junit.jupiter.api.BeforeEach
	void authenticateAsASalesperson() {
		com.ie.evalos.security.StaffPrincipal principal = new com.ie.evalos.security.StaffPrincipal(
				MEMBER, "sales.ie@evalos.local", "Aditya", com.ie.evalos.domain.Role.SALES, BRAND,
				null, MY_PIPELINE, true);
		org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
				new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
						principal, null, principal.getAuthorities()));
	}

	@org.junit.jupiter.api.AfterEach
	void clearTheSecurityContext() {
		org.springframework.security.core.context.SecurityContextHolder.clearContext();
	}

	@Test
	void aBookingCarriesTheCallersOwnPipelineToTheAuditTrail() {
		meetings.book(OPPORTUNITY, "cal_1", "c1", "Discovery call", inDays(1),
				Instant.now().plus(1, ChronoUnit.DAYS).plus(30, ChronoUnit.MINUTES).toString(), null, null, null, null, false, null);

		// The pipeline is never taken from the request — it comes from the caller's own row, via
		// the same requireMine every other route on this desk starts at.
		verify(calendars).book(eq("cal_1"), eq("c1"), eq(OPPORTUNITY), eq(MY_PIPELINE),
				eq("Discovery call"), any(), any(), any(), any(), any(), any(), anyBoolean());
	}

	/**
	 * The access check runs BEFORE any validation, so a probe against somebody else's deal
	 * cannot be distinguished from a valid one by the error it returns.
	 */
	@Test
	void somebodyElsesDealIsRefusedBeforeAnythingIsValidated() {
		when(scope.requireMine("not_mine")).thenThrow(new ForbiddenException("Not your pipeline"));

		assertThatThrownBy(() -> meetings.book("not_mine", "", "", "", "nonsense", "nonsense", null, null, null, null, false, null))
				.isInstanceOf(ForbiddenException.class);

		verify(calendars, never()).book(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
	}

	@Test
	void aMeetingInThePastIsRefused() {
		assertThatThrownBy(() -> meetings.book(OPPORTUNITY, "cal_1", "c1", "Discovery call",
				inDays(-2), inDays(-1), null, null, null, null, false, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("in the past");

		verify(calendars, never()).book(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
	}

	@Test
	void aMeetingThatEndsBeforeItStartsIsRefused() {
		assertThatThrownBy(() -> meetings.book(OPPORTUNITY, "cal_1", "c1", "Discovery call",
				inDays(3), inDays(2), null, null, null, null, false, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("must end after it starts");
	}

	/**
	 * GHL treats {@code endTime} as optional and books a zero-length event. Nobody wants one,
	 * and it is invisible in a calendar until somebody misses the call.
	 */
	@Test
	void aMeetingWithNoEndIsRefusedEvenThoughGhlWouldAcceptIt() {
		assertThatThrownBy(() -> meetings.book(OPPORTUNITY, "cal_1", "c1", "Discovery call",
				inDays(1), null, null, null, null, null, false, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("end time");
	}

	@Test
	void aMeetingOfZeroLengthIsRefused() {
		String sameMoment = inDays(1);
		assertThatThrownBy(() -> meetings.book(OPPORTUNITY, "cal_1", "c1", "Discovery call",
				sameMoment, sameMoment, null, null, null, null, false, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("must end after it starts");
	}

	/**
	 * A malformed date is refused here rather than passed to GHL, whose own answer is a 422 the
	 * salesperson cannot act on.
	 */
	@Test
	void aDateGhlCannotReadIsRefusedHereWithSomethingReadable() {
		assertThatThrownBy(() -> meetings.book(OPPORTUNITY, "cal_1", "c1", "Discovery call",
				"next Tuesday", inDays(2), null, null, null, null, false, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("ISO-8601");
	}

	/**
	 * A reschedule may move a meeting into the past — deliberately unlike a booking. Correcting
	 * the record of a call that already happened is a real thing a salesperson does, and it is
	 * not the typo that a *new* meeting in the past always is.
	 */
	@Test
	void aRescheduleIntoThePastIsAllowedWhereABookingIsNot() {
		meetings.reschedule(OPPORTUNITY, "appt_1", inDays(-2), inDays(-1));

		verify(calendars).reschedule(eq("appt_1"), eq(OPPORTUNITY), eq(MY_PIPELINE), any(), any());
	}

	@Test
	void theCalendarPickerIsANarrowedList() {
		com.ie.evalos.domain.GhlReference.Calendar mirrored =
				new com.ie.evalos.domain.GhlReference.Calendar(java.util.UUID.randomUUID(), "cal_1",
						"Sales calls");
		mirrored.seen("Sales calls", true, 30, "{{contact.name}}");
		when(reference.bookableCalendars()).thenReturn(java.util.List.of(mirrored));

		assertThat(meetings.calendars()).singleElement()
				.satisfies((calendar) -> {
					assertThat(calendar.id()).isEqualTo("cal_1");
					assertThat(calendar.name()).isEqualTo("Sales calls");
				});
	}

	/**
	 * <strong>Unit 47: the list is mirrored, the slots are not.</strong> Availability is GHL's to
	 * compute — open hours, buffers, caps, the assignee's other appointments — and a mirrored slot
	 * is wrong within a minute of being written. This is the assertion that fails if somebody
	 * "finishes" the mirror by caching slots too.
	 */
	@Test
	void theCalendarListIsMirroredAndTheSlotsAreStillLive() {
		when(reference.bookableCalendars()).thenReturn(java.util.List.of());

		meetings.calendars();

		verify(calendars, never()).calendars();
	}
}
