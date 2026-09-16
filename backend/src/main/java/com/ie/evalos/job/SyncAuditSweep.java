package com.ie.evalos.job;

import com.ie.evalos.service.SyncAuditService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The nightly question: is the mirror actually right? — Unit 45, slice B.
 *
 * <p><strong>Nightly, and that is a budget decision as much as a business one.</strong> This is the
 * longest read EvalOS makes — a paged full list of every opportunity on every mirrored pipeline,
 * about 30 seconds of GHL's request budget ({@code 00d} §6.3). Running it hourly would spend a
 * meaningful share of a shared 100-per-10-seconds allowance on a question whose answer changes
 * slowly, and starve the desks while it did.
 *
 * <p><strong>It is not the delta sweep and must not become one.</strong> {@code 00c} §4a gives them
 * different jobs: the delta sweep keeps the mirror current, this one checks whether it is. And
 * {@code 00d} §6.4 adds the correction that matters — the delta sweep "repairs dropped deliveries"
 * is <em>true for drift, false for events</em>: a dropped {@code opportunity.won} is not drift,
 * because no case exists to diverge, and nothing here will ever create one. That is Handoff A's, and
 * §2.3's replay sweep stays separate.
 *
 * <p>On Unit 19's machinery, so it joins the admin panel's staleness warning for free: an audit that
 * quietly stopped running is exactly the failure that panel exists to name, and a drift report
 * nobody is running is worse than none at all because it reads as "no drift".
 */
@Component
public class SyncAuditSweep implements Sweep {

	static final String JOB_TYPE = "SYNC_AUDIT";

	private final SweepRunner runner;
	private final SyncAuditService audit;

	SyncAuditSweep(SweepRunner runner, SyncAuditService audit) {
		this.runner = runner;
		this.audit = audit;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.SYNC_AUDIT}")
	@Override
	public boolean run() {
		// One "item" — the audit is a single comparison of two whole lists, so there is nothing to
		// iterate. The ledger still records it, which is what the admin panel reads, and the
		// advisory lock still stops two instances auditing at once.
		return runner.sweep(JOB_TYPE, () -> java.util.List.of(Boolean.TRUE),
				(item) -> audit.audit().changed() > 0);
	}
}
