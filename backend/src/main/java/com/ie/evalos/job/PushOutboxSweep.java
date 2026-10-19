package com.ie.evalos.job;

import java.time.Instant;

import com.ie.evalos.chat.push.PushOutboxRepository;
import com.ie.evalos.chat.push.PushRetry;
import com.ie.evalos.chat.push.PushSender;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the web pushes the push service could not take first time (V92), when their backoff falls due.
 *
 * <p><strong>One minute, the backoff's shortest wait rounded up to the ledger.</strong> A chat push is
 * time-sensitive, so a first retry lands 30-90s after the miss rather than at 30s; matching the 30s
 * exactly would double the {@code scheduled_job} rows this sweep writes (one per pass, never pruned)
 * to buy those few seconds. An empty pass is one indexed read.
 *
 * <p>One row per item, so each send and its row's fate share one transaction and one bad row does not
 * hold up the rest. Ids rather than rows: the per-item transaction reads the row afresh.
 */
@Component
public class PushOutboxSweep implements Sweep {

	static final String JOB_TYPE = "PUSH_OUTBOX";

	private final SweepRunner runner;
	private final PushOutboxRepository outbox;
	private final PushSender sender;

	PushOutboxSweep(SweepRunner runner, PushOutboxRepository outbox, PushSender sender) {
		this.runner = runner;
		this.outbox = outbox;
		this.sender = sender;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.PUSH_OUTBOX}")
	@Override
	public boolean run() {
		return runner.sweep(JOB_TYPE,
				() -> outbox.findTop100ByDueAtLessThanEqualOrderByDueAtAsc(Instant.now()).stream()
					.map(PushRetry::getId)
					.toList(),
				sender::retry);
	}
}
