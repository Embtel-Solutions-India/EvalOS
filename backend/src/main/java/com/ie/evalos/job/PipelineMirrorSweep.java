package com.ie.evalos.job;

import com.ie.evalos.service.PipelineMirrorService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the mirrored pipelines and stages in step with GHL — Unit 44, slice A.
 *
 * <p><strong>A sweep rather than a refresh-on-read, and the difference is what it can notice.</strong>
 * A read-through cache refills what somebody asked for; only a full pass can see that a pipeline
 * has <em>stopped</em> existing, which is the fact {@code missing_since} records and the fact a
 * drift report is built out of. It also puts this on the admin panel's staleness warning for free:
 * a mirror that quietly stopped running is exactly the failure that panel exists to name.
 *
 * <p><strong>The interval is slow on purpose.</strong> Pipelines are structure, not traffic — a GM
 * adds one every few months. Fifteen minutes would spend the GHL budget on an answer that has not
 * changed since the last twenty passes, and the one screen that needs a stage name the instant it
 * appears does not exist. The default is an hour, and it is a fixed delay like every other sweep,
 * so a slow pass cannot stack on itself.
 *
 * <p><strong>This is not Unit 45's delta sweep.</strong> That one reconciles <em>opportunities</em>
 * and carries conflict resolution, because both sides write them. Nothing writes a pipeline but
 * GHL, so this pass is a straight overwrite and needs none of it.
 */
@Component
public class PipelineMirrorSweep implements Sweep {

	static final String JOB_TYPE = "PIPELINE_MIRROR";

	private final SweepRunner runner;
	private final PipelineMirrorService mirror;

	PipelineMirrorSweep(SweepRunner runner, PipelineMirrorService mirror) {
		this.runner = runner;
		this.mirror = mirror;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.PIPELINE_MIRROR}")
	@Override
	public boolean run() {
		// One "item" — the whole GHL pipeline list is a single read and a single comparison, so
		// there is nothing to iterate. The ledger still records seen/acted, which is what the admin
		// panel shows, and the advisory lock still stops two instances syncing at once.
		return runner.sweep(JOB_TYPE, () -> java.util.List.of(Boolean.TRUE),
				(item) -> mirror.sync().changed() > 0);
	}
}
