package com.ie.evalos.job;

import java.util.Set;

import com.ie.evalos.chat.ConversationService;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.CaseRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every open case's conversations right (Unit 57 §3). Hourly, on Unit 19's machinery so the
 * admin panel's staleness warning covers it.
 *
 * <p><strong>The floor under the listener.</strong> It is what catches a change no event announces
 * — a revoked pipeline, an ENM activated or deactivated, a client who makes their portal account
 * after the case — and a listener that failed. <strong>Its first run backfills every case that
 * existed before Unit 57.</strong> Cases already CLOSED are left alone: they get no conversations,
 * because nobody can talk in a closed case and backfilling history that never happened is noise.
 *
 * <p>{@code findActiveForSweep} returns paid cases only, which is every case: a case is created
 * once the client has paid (business rule, 2026-09-26).
 */
@Component
public class ChatReconcileSweep implements Sweep {

	static final String JOB_TYPE = "CHAT_RECONCILE";

	private final SweepRunner runner;
	private final CaseRepository cases;
	private final ConversationService conversations;

	ChatReconcileSweep(SweepRunner runner, CaseRepository cases, ConversationService conversations) {
		this.runner = runner;
		this.cases = cases;
		this.conversations = conversations;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.CHAT_RECONCILE}")
	@Override
	public boolean run() {
		boolean ran = runner.sweep(JOB_TYPE, () -> cases.findActiveForSweep(Set.of(Stage.CLOSED)), (subject) -> {
			conversations.ensureAndSync(subject);
			return true;
		});
		// A case that closed while its CASE_CLOSED listener failed is no longer in the list above,
		// so its conversations are frozen here instead.
		conversations.makeReadOnlyWhereClosed();
		return ran;
	}
}
