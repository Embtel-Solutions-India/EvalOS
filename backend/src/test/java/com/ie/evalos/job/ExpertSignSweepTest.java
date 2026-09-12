package com.ie.evalos.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.notification.NotificationService;
import com.ie.evalos.notification.RecipientResolver;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.service.BusinessCalendar;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two thresholds, and the line this package does not cross.
 *
 * <p>{@link #theDeadlineNeverTimesTheExpertOut} is the one that matters most: the sweep prompts
 * a human and stops. A job that reassigned an expert at 3am would be making a production
 * decision nobody asked about, and the guard against that is a test, not a comment.
 */
class ExpertSignSweepTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final Instant ENTERED = Instant.parse("2026-09-01T09:00:00Z");

	private final JobLock lock = mock(JobLock.class);
	private final JobLedger ledger = mock(JobLedger.class);
	private final CaseRepository cases = mock(CaseRepository.class);
	private final BusinessCalendar calendar = mock(BusinessCalendar.class);
	private final NotificationService notifications = mock(NotificationService.class);
	private final RecipientResolver recipients = mock(RecipientResolver.class);

	private final ExpertSignSweep sweep = new ExpertSignSweep(new SweepRunner(lock, ledger), cases,
			calendar, notifications, recipients);

	private final Case subject = mock(Case.class);
	private final UUID cm = UUID.randomUUID();
	private final UUID pm = UUID.randomUUID();

	ExpertSignSweepTest() {
		when(lock.runExclusively(any(), any())).thenAnswer((call) -> {
			call.getArgument(1, Runnable.class).run();
			return true;
		});
		when(ledger.actOnOneItem(any())).thenAnswer((call) -> call.getArgument(0, Supplier.class).get());
		when(subject.getId()).thenReturn(CASE_ID);
		when(subject.getBrandId()).thenReturn(BRAND);
		when(subject.getStageEnteredAt()).thenReturn(ENTERED);
		when(cases.findAllAtStageForSweep(any())).thenReturn(List.of(subject));
		when(recipients.assignedCm(subject)).thenReturn(List.of(cm));
		when(recipients.assignedPm(subject)).thenReturn(List.of(pm));
	}

	private void businessHoursElapsed(long hours) {
		when(calendar.elapsedBusinessTime(eq(ENTERED), any())).thenReturn(Duration.ofHours(hours));
	}

	@Test
	void nothingIsSaidBeforeTheWarningPoint() {
		businessHoursElapsed(19);
		sweep.run();
		verify(notifications, never()).create(any(), any(), any(), any(), any());
	}

	@Test
	void theWarningFiresAtTwentyBusinessHours() {
		businessHoursElapsed(20);
		sweep.run();
		verify(notifications).create(eq(BRAND), eq(List.of(cm, pm)),
				eq(NotificationType.EXPERT_SIGN_AT_RISK), eq(CASE_ID), any());
	}

	@Test
	void pastTheDeadlineItIsThePromptThatFiresNotTheWarningItAlreadyOutran() {
		businessHoursElapsed(30);
		sweep.run();
		verify(notifications).create(eq(BRAND), any(), eq(NotificationType.EXPERT_SIGN_OVERDUE),
				eq(CASE_ID), any());
		verify(notifications, never()).create(any(), any(), eq(NotificationType.EXPERT_SIGN_AT_RISK),
				any(), any());
	}

	@Test
	void eachPromptIsRaisedOnce() {
		businessHoursElapsed(30);
		when(notifications.alreadyRaised(CASE_ID, NotificationType.EXPERT_SIGN_OVERDUE)).thenReturn(true);
		sweep.run();
		verify(notifications, never()).create(any(), any(), any(), any(), any());
	}

	/**
	 * The sweep raises a prompt and stops. It has no transition service to call, and that is
	 * the point — the absence of the dependency is the guarantee, so this asserts it.
	 */
	@Test
	void theDeadlineNeverTimesTheExpertOut() {
		businessHoursElapsed(500);
		sweep.run();
		verify(cases, never()).save(any());
		verify(notifications).create(any(), any(), eq(NotificationType.EXPERT_SIGN_OVERDUE), any(), any());
	}

	@Test
	void wallClockIsNeverConsulted() {
		// The expert who was sent a letter at 16:00 on Friday is not late on Saturday. Elapsed
		// business time is 1h even though 40 wall-clock hours have passed.
		businessHoursElapsed(1);
		sweep.run();
		verify(notifications, never()).create(any(), any(), any(), any(), any());
	}
}
