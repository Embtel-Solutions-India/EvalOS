package com.ie.evalos.job;

import java.util.List;

import com.ie.evalos.chat.ConversationService;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.CaseRepository;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatReconcileSweepTest {

	@Test
	void everyCaseNotYetClosedIsEnsuredAndSynced() {
		CaseRepository cases = mock(CaseRepository.class);
		ConversationService conversations = mock(ConversationService.class);
		SweepRunner runner = mock(SweepRunner.class);
		Case open = mock(Case.class);
		when(cases.findByCurrentStageNot(Stage.CLOSED)).thenReturn(List.of(open));
		when(runner.sweep(eq("CHAT_RECONCILE"), any(), any())).thenAnswer((call) -> {
			java.util.function.Supplier<List<Case>> items = call.getArgument(1);
			SweepRunner.ItemAction<Case> act = call.getArgument(2);
			items.get().forEach(act::act);
			return true;
		});

		new ChatReconcileSweep(runner, cases, conversations).run();

		verify(conversations).ensureAndSync(open);
	}

	/** A case whose CASE_CLOSED listener failed is no longer "active", so the sweep freezes it separately. */
	@Test
	void conversationsOfClosedCasesAreMadeReadOnly() {
		CaseRepository cases = mock(CaseRepository.class);
		ConversationService conversations = mock(ConversationService.class);
		SweepRunner runner = mock(SweepRunner.class);
		when(runner.sweep(eq("CHAT_RECONCILE"), any(), any())).thenReturn(true);

		new ChatReconcileSweep(runner, cases, conversations).run();

		verify(conversations).makeReadOnlyWhereClosed();
	}
}
