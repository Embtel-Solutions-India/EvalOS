package com.ie.evalos.job;

import java.time.Duration;

import com.ie.evalos.service.OpportunityMirrorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the opportunity mirror current in the background — Unit 45, slice D.
 *
 * <p><strong>It is not the nightly audit and must not become one.</strong> {@code 00c} §4a gives
 * them different jobs: this one keeps the mirror current, {@link SyncAuditSweep} checks whether it
 * is, and a detector that also repairs cannot be trusted because its own writes become tomorrow's
 * findings ({@code 45-sync-engine.md} §2B.2).
 *
 * <p><strong>"Delta" is the pipeline, not the row, and the API is why.</strong> GHL's opportunity
 * search filters on {@code createdAt} and offers no updated-since filter — the verification lives on
 * {@code GhlPipelineClient.opportunitiesIn}, and it is the same absence that forces "won this month"
 * to be bucketed locally. So no read returns only what changed. What this sweep does instead is ask
 * {@code refreshIfStale} of every live mirrored pipeline, which is a no-op for any pipeline a desk
 * has looked at inside the TTL. The cost is therefore the pipelines nobody is reading — which is
 * exactly the gap it exists to close, because those are the ones the audit would otherwise report
 * as drift when the truth is that nobody looked.
 *
 * <p><strong>It creates no case.</strong> A dropped {@code opportunity.won} is not drift — no case
 * exists to diverge — and nothing here will ever open one ({@code 00d} §6.4). That is Handoff A's,
 * and its replay sweep stays separate.
 */
@Component
public class MirrorDeltaSweep implements Sweep {

	static final String JOB_TYPE = "MIRROR_DELTA";

	private static final Logger log = LoggerFactory.getLogger(MirrorDeltaSweep.class);

	private final SweepRunner runner;
	private final OpportunityMirrorService mirror;
	private final Duration ttl;

	MirrorDeltaSweep(SweepRunner runner, OpportunityMirrorService mirror,
			@Value("${evalos.ghl.delta-ttl}") Duration ttl) {
		this.runner = runner;
		this.mirror = mirror;
		this.ttl = ttl;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.MIRROR_DELTA}")
	@Override
	public boolean run() {
		// One item per live pipeline, so a GHL failure on one (a 400, a read timeout) rolls back and
		// skips only that one. It was a single item looping them all, and one failure cost the pass.
		return runner.sweep(JOB_TYPE, mirror::livePipelineIds, (pipelineId) -> {
			try {
				return mirror.refreshIfStale(pipelineId, ttl);
			}
			catch (RuntimeException failed) {
				// SweepRunner logs the exception but not the item; GHL's own 400 body does not name it either.
				log.warn("MIRROR_DELTA could not refresh GHL pipeline {}", pipelineId);
				throw failed;
			}
		});
	}
}
