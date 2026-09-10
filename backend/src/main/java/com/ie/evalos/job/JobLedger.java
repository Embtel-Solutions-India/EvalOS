package com.ie.evalos.job;

import java.time.Instant;
import java.util.function.Supplier;

import com.ie.evalos.domain.ScheduledJob;
import com.ie.evalos.repository.ScheduledJobRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every transaction a sweep needs, on its own bean.
 *
 * <p><strong>Separate from {@link SweepRunner} because Spring's {@code @Transactional} is
 * proxy-based.</strong> A runner calling its own annotated methods through {@code this} bypasses
 * the proxy entirely: the annotations would sit there looking correct and open no transaction,
 * so each item would run outside one and a failure would roll back nothing. The bug is silent —
 * everything still appears to work until a sweep half-writes and does not undo it.
 *
 * <p>Three transactions, all {@code REQUIRES_NEW}, and each for its own reason:
 * <ul>
 * <li><strong>{@link #start} and {@link #finish}</strong> commit independently of the sweep, so
 * a run that <em>failed</em> still leaves the row that says so. A ledger that rolls away with
 * the failure it was recording is worse than no ledger.</li>
 * <li><strong>{@link #actOnOneItem}</strong> is the per-item boundary: one bad case rolls back
 * only itself and the other ninety-nine still run. That property is also why the lock is
 * session-scoped — there is no sweep-wide transaction to hang it on.</li>
 * </ul>
 */
@Component
public class JobLedger {

	private final ScheduledJobRepository runs;

	JobLedger(ScheduledJobRepository runs) {
		this.runs = runs;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ScheduledJob start(String jobType) {
		return runs.save(new ScheduledJob(jobType, Instant.now()));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void finish(ScheduledJob run, ScheduledJob.Status status, int seen, int acted, String error) {
		ScheduledJob row = runs.findById(run.getId()).orElse(run);
		row.finish(status, seen, acted, error);
		runs.save(row);
	}

	/**
	 * One item, one transaction.
	 *
	 * <p><strong>A throw is deliberately not caught here.</strong> It has to leave this method
	 * for {@code REQUIRES_NEW} to roll the item back — catching it would commit the half-written
	 * item — so the absorbing happens one frame out, in {@link SweepRunner}, which is also where
	 * it is logged.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean actOnOneItem(Supplier<Boolean> action) {
		return Boolean.TRUE.equals(action.get());
	}
}
