package com.ie.evalos.job;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.notification.NotificationService;
import com.ie.evalos.notification.RecipientResolver;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.service.SlaCalculator;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The escalation asks the calculator whether the budget is spent; it does not own a number.
 *
 * <p>{@link #theThresholdComesFromTheCalculatorAndIsNotWrittenTwice} is the point: the day-3
 * escalation and the {@code DOC_COLLECTION} SLA budget are the <em>same</em> number, and a
 * second copy here is how this sweep and the board's rail come to disagree about one case.
 */
class DocEscalationSweepTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();

	private final JobLock lock = mock(JobLock.class);
	private final JobLedger ledger = mock(JobLedger.class);
	private final CaseRepository cases = mock(CaseRepository.class);
	private final DocumentChecklistItemRepository checklist = mock(DocumentChecklistItemRepository.class);
	private final SlaCalculator sla = mock(SlaCalculator.class);
	private final NotificationService notifications = mock(NotificationService.class);
	private final RecipientResolver recipients = mock(RecipientResolver.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private final DocEscalationSweep sweep = new DocEscalationSweep(new SweepRunner(lock, ledger), cases,
			checklist, sla, notifications, recipients, events);

	private final Case subject = mock(Case.class);

	DocEscalationSweepTest() {
		when(lock.runExclusively(any(), any())).thenAnswer((call) -> {
			call.getArgument(1, Runnable.class).run();
			return true;
		});
		when(ledger.actOnOneItem(any())).thenAnswer((call) -> call.getArgument(0, Supplier.class).get());
		when(subject.getId()).thenReturn(CASE_ID);
		when(subject.getBrandId()).thenReturn(BRAND);
		when(cases.findAllAtStageForSweep(any())).thenReturn(List.of(subject));
		when(checklist.isChecklistComplete(CASE_ID)).thenReturn(false);
		when(recipients.assignedPmAndBrandManagers(subject)).thenReturn(List.of(UUID.randomUUID()));
	}

	private void assertEscalated(boolean expected) {
		verify(notifications, expected ? org.mockito.Mockito.times(1) : never())
				.create(eq(BRAND), any(), eq(NotificationType.DOCS_ESCALATED), eq(CASE_ID), any());
		verify(events, expected ? org.mockito.Mockito.times(1) : never())
				.publishEvent(any(CaseEvents.CaseEvent.class));
	}

	@Test
	void theThresholdComesFromTheCalculatorAndIsNotWrittenTwice() {
		when(sla.statusOf(subject)).thenReturn(SlaStatus.AT_RISK);
		sweep.run();
		assertEscalated(false);

		when(sla.statusOf(subject)).thenReturn(SlaStatus.OVERDUE);
		sweep.run();
		assertEscalated(true);
	}

	@Test
	void oncePerCaseEver() {
		when(sla.statusOf(subject)).thenReturn(SlaStatus.OVERDUE);
		when(notifications.alreadyRaised(CASE_ID, NotificationType.DOCS_ESCALATED)).thenReturn(true);
		sweep.run();
		// Escalating hourly would train the PM to ignore the bell, and Unit 06's centre is the
		// only channel staff have.
		assertEscalated(false);
	}

	@Test
	void aCompleteChecklistIsNotEscalated() {
		when(checklist.isChecklistComplete(CASE_ID)).thenReturn(true);
		when(sla.statusOf(subject)).thenReturn(SlaStatus.OVERDUE);
		sweep.run();
		assertEscalated(false);
	}

	/** On hold is not late: the calculator returns null, and null is simply not OVERDUE. */
	@Test
	void aCaseInAnExceptionStateIsNotEscalated() {
		when(sla.statusOf(subject)).thenReturn(null);
		sweep.run();
		assertEscalated(false);
	}

	/**
	 * The escalation must not use {@code SLA_OVERDUE}: {@code StageSlaSweep} raises that value
	 * on the same case at the same moment, and sharing it would let whichever swept first
	 * suppress the other through {@code alreadyRaised}.
	 */
	@Test
	void itDoesNotCollideWithTheStageSweepsOwnOverdueAlert() {
		when(sla.statusOf(subject)).thenReturn(SlaStatus.OVERDUE);
		when(notifications.alreadyRaised(CASE_ID, NotificationType.SLA_OVERDUE)).thenReturn(true);
		sweep.run();
		assertEscalated(true);
	}
}
