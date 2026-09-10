package com.ie.evalos.job;

import java.util.List;
import java.util.function.Supplier;

import com.ie.evalos.domain.ScheduledJob;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two failure boundaries, which are the whole reason this class exists.
 *
 * <p>Both are the kind of bug that shows up as absence: a sweep that stopped a month ago because
 * one exception escaped, or ninety-nine cases that went unchased because the first one threw.
 * Neither produces an error anybody sees, so each gets a test.
 */
class SweepRunnerTest {

	private final JobLock lock = mock(JobLock.class);
	private final JobLedger ledger = mock(JobLedger.class);
	private final SweepRunner runner = new SweepRunner(lock, ledger);
	private final ScheduledJob row = mock(ScheduledJob.class);

	/** The lock is free and the work runs. */
	private void lockIsFree() {
		when(lock.runExclusively(any(), any())).thenAnswer((call) -> {
			call.getArgument(1, Runnable.class).run();
			return true;
		});
		when(ledger.start(any())).thenReturn(row);
	}

	/** Real per-item behaviour: call the action, and let a throw out, as JobLedger does. */
	private void itemsRunForReal() {
		when(ledger.actOnOneItem(any())).thenAnswer(
				(call) -> call.getArgument(0, Supplier.class).get());
	}

	@Test
	void oneBadItemDoesNotStopTheRest() {
		lockIsFree();
		itemsRunForReal();

		boolean ran = runner.sweep("T", () -> List.of("a", "boom", "c"), (item) -> {
			if (item.equals("boom")) {
				throw new IllegalStateException("this one is broken");
			}
			return true;
		});

		assertThat(ran).isTrue();
		// Three seen, two acted, and the run is OK: a skipped item is not a failed sweep.
		verify(ledger).finish(eq(row), eq(ScheduledJob.Status.OK), eq(3), eq(2), isNull());
	}

	@Test
	void aSweepThatThrowsIsRecordedFailedRatherThanEscapingTheScheduler() {
		lockIsFree();

		boolean ran = runner.sweep("T", () -> {
			throw new IllegalStateException("the finder blew up");
		}, (item) -> true);

		// It returns normally. An exception reaching Spring's scheduler silently cancels that
		// sweep for the life of the process — which is the outage nobody notices for a month.
		assertThat(ran).isTrue();
		verify(ledger).finish(eq(row), eq(ScheduledJob.Status.FAILED), eq(0), eq(0), any());
	}

	@Test
	void aHeldLockSkipsTheTickWithoutWritingALedgerRow() {
		when(lock.runExclusively(any(), any())).thenReturn(false);

		assertThat(runner.sweep("T", List::of, (item) -> true)).isFalse();

		// No row: the sweep did not run, and a ledger full of "another instance had it" rows is
		// a ledger a GM stops reading.
		verify(ledger, never()).start(any());
		verify(ledger, never()).finish(any(), any(), anyInt(), anyInt(), any());
	}
}
