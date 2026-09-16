package com.ie.evalos.job;

import com.ie.evalos.service.SyncOutboxService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the outbox — Unit 45, slice C.
 *
 * <p><strong>Two minutes, which is the one interval here chosen for a person rather than a budget.</strong>
 * What is queued is a client's request reaching Sales after a GHL outage. Every minute it sits is a
 * minute a salesperson does not know the lead exists; an hour, like the pipeline mirror's, would
 * make the queue itself the outage.
 *
 * <p>The cost is bounded from the other side: a drain sends at most one batch, the pacer serialises
 * those against every other GHL caller, and a {@code 429} pauses the whole location anyway.
 *
 * <p>On Unit 19's machinery, so a drain that stopped running joins the admin panel's staleness
 * warning. That matters here as much as for the audit: <strong>a queue nobody is draining looks
 * exactly like a queue with nothing in it</strong> from every screen except this one.
 */
@Component
public class SyncOutboxSweep implements Sweep {

	static final String JOB_TYPE = "SYNC_OUTBOX";

	private final SweepRunner runner;
	private final SyncOutboxService outbox;

	SyncOutboxSweep(SweepRunner runner, SyncOutboxService outbox) {
		this.runner = runner;
		this.outbox = outbox;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.SYNC_OUTBOX}")
	@Override
	public boolean run() {
		return runner.sweep(JOB_TYPE, () -> java.util.List.of(Boolean.TRUE),
				(item) -> outbox.drain().changed() > 0);
	}
}
