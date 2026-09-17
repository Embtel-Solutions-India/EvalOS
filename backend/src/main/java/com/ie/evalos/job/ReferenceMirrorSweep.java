package com.ie.evalos.job;

import java.util.List;

import com.ie.evalos.service.ReferenceMirrorService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the location's reference lists current — Unit 47.
 *
 * <p><strong>Hourly, and the slowness is the point</strong> — 44a's argument, unchanged. Custom
 * fields, calendars and users are structure, not traffic: somebody adds one a few times a year. A
 * fifteen-minute pass would spend GHL's shared budget re-reading an answer that has not moved in
 * twenty passes, and no screen needs a calendar the instant it is created.
 *
 * <p><strong>One sweep for three lists, because they change at the same speed.</strong> Three
 * sweeps would mean three intervals, three ledger rows an hour, and three chances for one of them
 * to be the one that quietly stopped. One is one thing to watch.
 *
 * <p>On Unit 19's machinery, so it joins the admin panel's staleness warning for free — and that
 * matters more here than it looks: after Unit 47 these lists are what the booking form and the new
 * deal form draw from, so a sweep that died shows up as a form quietly going out of date rather
 * than as an error anybody sees.
 */
@Component
public class ReferenceMirrorSweep implements Sweep {

	static final String JOB_TYPE = "REFERENCE_MIRROR";

	private final SweepRunner runner;
	private final ReferenceMirrorService mirror;

	ReferenceMirrorSweep(SweepRunner runner, ReferenceMirrorService mirror) {
		this.runner = runner;
		this.mirror = mirror;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.REFERENCE_MIRROR}")
	@Override
	public boolean run() {
		// One "item": the pass is three reads inside the service, which owns the selling brand and
		// the per-list failure handling. The ledger still records it, which is what the admin
		// panel's "this sweep has stopped" warning reads.
		return runner.sweep(JOB_TYPE, () -> List.of(Boolean.TRUE),
				(item) -> mirror.refresh().total() > 0);
	}
}
