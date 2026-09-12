package com.ie.evalos.job;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.notification.NotificationService;
import com.ie.evalos.notification.RecipientResolver;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.service.AuditService;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which chase is due, decided from the audit trail rather than from a job row.
 *
 * <p>The property under test is that the sweep is correct on its <em>first run after any
 * outage</em>: it counts what has actually been done to the case, so a week of missed ticks
 * produces one chase and not seven.
 */
class DocChaseSweepTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();

	private final JobLock lock = mock(JobLock.class);
	private final JobLedger ledger = mock(JobLedger.class);
	private final CaseRepository cases = mock(CaseRepository.class);
	private final DocumentChecklistItemRepository checklist = mock(DocumentChecklistItemRepository.class);
	private final AuditEventRepository auditTrail = mock(AuditEventRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final NotificationService notifications = mock(NotificationService.class);
	private final RecipientResolver recipients = mock(RecipientResolver.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private final DocChaseSweep sweep = new DocChaseSweep(new SweepRunner(lock, ledger), cases, checklist,
			auditTrail, audit, notifications, recipients, events);

	private final Case subject = mock(Case.class);

	DocChaseSweepTest() {
		when(lock.runExclusively(any(), any())).thenAnswer((call) -> {
			call.getArgument(1, Runnable.class).run();
			return true;
		});
		when(ledger.actOnOneItem(any())).thenAnswer((call) -> call.getArgument(0, Supplier.class).get());
		when(subject.getId()).thenReturn(CASE_ID);
		when(subject.getBrandId()).thenReturn(BRAND);
		when(cases.findAllAtStageForSweep(any())).thenReturn(List.of(subject));
		when(recipients.coordinators(BRAND)).thenReturn(List.of(UUID.randomUUID()));
		when(checklist.isChecklistComplete(CASE_ID)).thenReturn(false);
	}

	private void waitingFor(long hours) {
		when(subject.getStageEnteredAt()).thenReturn(Instant.now().minus(hours, ChronoUnit.HOURS));
	}

	private void chasesAlreadySent(int count) {
		AuditEvent chased = mock(AuditEvent.class);
		when(chased.getAction()).thenReturn(AuditAction.CHASED);
		when(auditTrail.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("CASE", CASE_ID))
				.thenReturn(java.util.Collections.nCopies(count, chased));
	}

	private void assertChased(int times) {
		verify(audit, org.mockito.Mockito.times(times)).recordSystemEvent(eq(BRAND), eq("CASE"), eq(CASE_ID),
				eq(AuditAction.CHASED), any(), any());
		verify(notifications, org.mockito.Mockito.times(times)).create(eq(BRAND), any(),
				eq(NotificationType.DOC_CHASE_DUE), eq(CASE_ID), any());
	}

	@Test
	void theFirstChaseFiresAtTwentyFourHours() {
		chasesAlreadySent(0);
		waitingFor(25);
		sweep.run();
		assertChased(1);
	}

	@Test
	void theFirstChaseDoesNotFireEarly() {
		chasesAlreadySent(0);
		waitingFor(23);
		sweep.run();
		assertChased(0);
	}

	@Test
	void theSecondChaseWaitsForFortyEightHoursNotTwentyFourMore() {
		chasesAlreadySent(1);
		waitingFor(30);
		sweep.run();
		// Both thresholds are measured from the stage entry, not from the previous chase.
		assertChased(0);

		waitingFor(49);
		sweep.run();
		assertChased(1);
	}

	@Test
	void aThirdChaseNeverFiresHoweverLongItHasBeen() {
		chasesAlreadySent(2);
		waitingFor(500);
		sweep.run();
		assertChased(0);
	}

	@Test
	void aCompleteChecklistIsNotChased() {
		when(checklist.isChecklistComplete(CASE_ID)).thenReturn(true);
		chasesAlreadySent(0);
		waitingFor(500);
		sweep.run();
		assertChased(0);
	}

	@Test
	void aCaseWithNoStageClockIsSkippedRatherThanChasedFromTheEpoch() {
		chasesAlreadySent(0);
		when(subject.getStageEnteredAt()).thenReturn(null);
		sweep.run();
		assertChased(0);
		verify(events, never()).publishEvent(any(Object.class));
	}
}
