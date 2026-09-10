package com.ie.evalos.job;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.notification.NotificationService;
import com.ie.evalos.notification.RecipientResolver;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.service.SlaCalculator;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Notify on the transition, never on the state.
 *
 * <p>{@link #aCaseAlreadyOverdueIsNotAnnouncedAgain} is the one that keeps the bell worth
 * hearing: at a fifteen-minute interval, alerting on the state would put ninety-six identical
 * notifications a day on one case, and Unit 06's centre is the only channel staff have.
 */
class StageSlaSweepTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();

	private final JobLock lock = mock(JobLock.class);
	private final JobLedger ledger = mock(JobLedger.class);
	private final CaseRepository cases = mock(CaseRepository.class);
	private final SlaCalculator sla = mock(SlaCalculator.class);
	private final NotificationService notifications = mock(NotificationService.class);
	private final RecipientResolver recipients = mock(RecipientResolver.class);

	private final StageSlaSweep sweep = new StageSlaSweep(new SweepRunner(lock, ledger), cases, sla,
			notifications, recipients);

	private final Case subject = mock(Case.class);

	StageSlaSweepTest() {
		when(lock.runExclusively(any(), any())).thenAnswer((call) -> {
			call.getArgument(1, Runnable.class).run();
			return true;
		});
		when(ledger.actOnOneItem(any())).thenAnswer((call) -> call.getArgument(0, Supplier.class).get());
		when(subject.getId()).thenReturn(CASE_ID);
		when(subject.getBrandId()).thenReturn(BRAND);
		when(cases.findActiveForSweep(any())).thenReturn(List.of(subject));
		when(recipients.assignedPmAndBrandManagers(subject)).thenReturn(List.of(UUID.randomUUID()));
	}

	private void storedAndComputed(SlaStatus stored, SlaStatus computed) {
		when(subject.getSlaStatus()).thenReturn(stored);
		when(sla.statusOf(subject)).thenReturn(computed);
	}

	@Test
	void tippingIntoBreachRefreshesTheColumnAndRingsOnce() {
		storedAndComputed(SlaStatus.ON_TRACK, SlaStatus.OVERDUE);
		sweep.run();
		verify(subject).setSlaStatus(SlaStatus.OVERDUE);
		verify(cases).save(subject);
		verify(notifications).create(eq(BRAND), any(), eq(NotificationType.SLA_OVERDUE), eq(CASE_ID), any());
	}

	@Test
	void aCaseAlreadyOverdueIsNotAnnouncedAgain() {
		storedAndComputed(SlaStatus.OVERDUE, SlaStatus.OVERDUE);
		sweep.run();
		// Nothing changed, so nothing is written and nothing is said.
		verify(cases, never()).save(any());
		verify(notifications, never()).create(any(), any(), any(), any(), any());
	}

	@Test
	void recoveringToOnTrackIsWrittenButNotAnnounced() {
		storedAndComputed(SlaStatus.AT_RISK, SlaStatus.ON_TRACK);
		sweep.run();
		verify(cases).save(subject);
		verify(notifications, never()).create(any(), any(), any(), any(), any());
	}

	/**
	 * A case cannot un-breach into a lesser breach and ring again. Without this, a stage clock
	 * that wobbles across the boundary produces an alternating stream of alerts.
	 */
	@Test
	void goingFromOverdueBackToAtRiskDoesNotRing() {
		storedAndComputed(SlaStatus.OVERDUE, SlaStatus.AT_RISK);
		sweep.run();
		verify(cases).save(subject);
		verify(notifications, never()).create(any(), any(), any(), any(), any());
	}

	/** On hold is not late: the calculator returns null and there is nothing to refresh. */
	@Test
	void aCaseInAnExceptionStateIsLeftAlone() {
		storedAndComputed(SlaStatus.ON_TRACK, null);
		sweep.run();
		verify(cases, never()).save(any());
		verify(notifications, never()).create(any(), any(), any(), any(), any());
	}
}
