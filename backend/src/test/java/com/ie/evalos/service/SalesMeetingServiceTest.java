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

	private final GhlCalendarClient calendars = mock(GhlCalendarClient.class);
	private final PipelineScope scope = mock(PipelineScope.class);
	private final SalesMeetingService meetings = new SalesMeetingService(calendars, scope);

	private static String inDays(int days) {
		return Instant.now().plus(days, ChronoUnit.DAYS).toString();
	}

	SalesMeetingServiceTest() {
		when(scope.requireMine(OPPORTUNITY)).thenReturn(MY_PIPELINE);
	}

	@Test
	void aBookingCarriesTheCallersOwnPipelineToTheAuditTrail() {
		meetings.book(OPPORTUNITY, "cal_1", "c1", "Discovery call", inDays(1),
				Instant.now().plus(1, ChronoUnit.DAYS).plus(30, ChronoUnit.MINUTES).toString());

		// The pipeline is never taken from the request — it comes from the caller's own row, via
		// the same requireMine every other route on this desk starts at.
		verify(calendars).book(eq("cal_1"), eq("c1"), eq(OPPORTUNITY), eq(MY_PIPELINE),
				eq("Discovery call"), any(), any());
	}

	/**
	 * The access check runs BEFORE any validation, so a probe against somebody else's deal
	 * cannot be distinguished from a valid one by the error it returns.
	 */
	@Test
	void somebodyElsesDealIsRefusedBeforeAnythingIsValidated() {
		when(scope.requireMine("not_mine")).thenThrow(new ForbiddenException("Not your pipeline"));

		assertThatThrownBy(() -> meetings.book("not_mine", "", "", "", "nonsense", "nonsense"))
				.isInstanceOf(ForbiddenException.class);

		verify(calendars, never()).book(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void aMeetingInThePastIsRefused() {
		assertThatThrownBy(() -> meetings.book(OPPORTUNITY, "cal_1", "c1", "Discovery call",
				inDays(-2), inDays(-1)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("in the past");

		verify(calendars, never()).book(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void aMeetingThatEndsBeforeItStartsIsRefused() {
		assertThatThrownBy(() -> meetings.book(OPPORTUNITY, "cal_1", "c1", "Discovery call",
				inDays(3), inDays(2)))
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
				inDays(1), null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("end time");
	}

	@Test
	void aMeetingOfZeroLengthIsRefused() {
		String sameMoment = inDays(1);
		assertThatThrownBy(() -> meetings.book(OPPORTUNITY, "cal_1", "c1", "Discovery call",
				sameMoment, sameMoment))
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
				"next Tuesday", inDays(2)))
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
		when(calendars.calendars()).thenReturn(java.util.List.of(
				new GhlCalendarClient.CalendarOption("cal_1", "Sales calls")));

		assertThat(meetings.calendars()).singleElement()
				.satisfies((calendar) -> {
					assertThat(calendar.id()).isEqualTo("cal_1");
					assertThat(calendar.name()).isEqualTo("Sales calls");
				});
	}
}
