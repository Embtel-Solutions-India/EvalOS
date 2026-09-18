package com.ie.evalos.job;

import java.time.Duration;
import java.util.List;

import com.ie.evalos.service.OpportunityMirrorService;

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
		// One "item": the pass is a loop over pipelines inside the service, which owns the
		// selling brand and the TTL comparison. The ledger still records it, so the admin panel's
		// staleness warning covers a sweep that quietly stopped — the failure that matters here,
		// because a mirror nobody is refreshing looks exactly like a mirror with nothing to do.
		return runner.sweep(JOB_TYPE, () -> List.of(Boolean.TRUE), (item) -> mirror.refreshStale(ttl) > 0);
	}
}
