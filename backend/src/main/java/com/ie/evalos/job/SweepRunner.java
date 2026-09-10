package com.ie.evalos.job;

import java.util.List;
import java.util.function.Supplier;

import com.ie.evalos.domain.ScheduledJob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The part every sweep shares: take the lock, write a ledger row, run one transaction per item,
 * and close the row whatever happens.
 *
 * <p><strong>Two failure boundaries, and they are different on purpose.</strong>
 *
 * <ul>
 * <li><strong>A sweep that throws does not stop the schedule.</strong> The throw is caught, the
 * ledger row is closed {@code FAILED} with the error, and the next tick tries again. The
 * alternative — an exception escaping into Spring's scheduler — silently stops that sweep for
 * the life of the process, and nobody notices for a month.</li>
 * <li><strong>An item that throws does not stop the sweep.</strong> Each item runs in its own
 * transaction, so one bad case cannot take the other ninety-nine with it.</li>
 * </ul>
 *
 * <p>That second property is exactly why the lock in {@link JobLock} must be session-scoped
 * rather than transaction-scoped: there is no single transaction spanning the sweep to hang it
 * on.
 *
 * <p><strong>The ledger row is written before the work, not after.</strong> A row left
 * {@code RUNNING} is how a JVM killed mid-sweep announces itself, and that is worth seeing.
 */
@Component
public class SweepRunner {

	private static final Logger log = LoggerFactory.getLogger(SweepRunner.class);

	private final JobLock lock;
	private final JobLedger ledger;

	/**
	 * The ledger is a separate bean and must stay one: Spring's {@code @Transactional} is
	 * proxy-based, so a runner calling its own annotated methods would get no transaction at
	 * all — the annotation present, doing nothing, silently.
	 */
	SweepRunner(JobLock lock, JobLedger ledger) {
		this.lock = lock;
		this.ledger = ledger;
	}

	/**
	 * Runs one sweep under its lock, recording the attempt.
	 *
	 * @param jobType the lock key and the ledger's key. One constant per sweep, and it must not
	 *                change: it is what two instances agree on
	 * @param items   the finder. Called inside the lock so it cannot read rows another instance
	 *                is already acting on
	 * @param act     what to do with one item, in its own transaction. Returns true if it acted,
	 *                false if it looked and decided not to — which is the normal case and the
	 *                difference between {@code seen} and {@code acted}
	 * @return true if the sweep ran, false if another instance held the lock
	 */
	public <T> boolean sweep(String jobType, Supplier<List<T>> items, ItemAction<T> act) {
		return lock.runExclusively(jobType, () -> {
			ScheduledJob run = ledger.start(jobType);
			int seen = 0;
			int acted = 0;
			try {
				List<T> found = items.get();
				seen = found.size();
				for (T item : found) {
					// Each item in its own transaction, and a thrown one is absorbed HERE so the
					// sweep carries on — the rollback already happened inside the boundary.
					try {
						if (ledger.actOnOneItem(() -> act.act(item))) {
							acted++;
						}
					}
					catch (RuntimeException itemFailed) {
						log.error("Sweep {} skipped one item and continued", jobType, itemFailed);
					}
				}
				ledger.finish(run, ScheduledJob.Status.OK, seen, acted, null);
			}
			catch (RuntimeException failure) {
				log.error("Sweep {} failed after seeing {} items", jobType, seen, failure);
				ledger.finish(run, ScheduledJob.Status.FAILED, seen, acted, failure.toString());
			}
		});
	}

	/** One item's work. Separated so the transaction boundary is visible at the call site. */
	@FunctionalInterface
	public interface ItemAction<T> {
		boolean act(T item);
	}

	/**
	 * Whether this sweep could be started right now.
	 *
	 * <p>For the GM's "Run now" button to answer "already running" without attempting it.
	 * Advisory: the real claim is atomic and happens in {@link #sweep}.
	 */
	public boolean isFree(String jobType) {
		return lock.isFree(jobType);
	}
}
