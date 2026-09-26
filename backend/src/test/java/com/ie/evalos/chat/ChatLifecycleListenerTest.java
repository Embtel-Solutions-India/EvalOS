package com.ie.evalos.chat;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatLifecycleListenerTest {

	private final CaseRepository cases = mock(CaseRepository.class);
	private final ConversationService conversations = mock(ConversationService.class);
	private final org.springframework.transaction.PlatformTransactionManager transactions =
			mock(org.springframework.transaction.PlatformTransactionManager.class);
	private final ChatLifecycleListener listener = new ChatLifecycleListener(cases, conversations, transactions);

	private CaseEvents.CaseEvent event(CaseEvents.Type type, UUID caseId) {
		return new CaseEvents.CaseEvent(type, UUID.randomUUID(), caseId, null, null, Stage.DOC_COLLECTION);
	}

	@Test
	void aCreatedCaseGetsItsConversations() {
		UUID id = UUID.randomUUID();
		Case subject = mock(Case.class);
		when(cases.findById(id)).thenReturn(Optional.of(subject));

		listener.on(event(CaseEvents.Type.CASE_CREATED, id));

		verify(conversations).ensureAndSync(subject);
	}

	@Test
	void everyAssignmentEventSyncsMembership() {
		for (CaseEvents.Type type : new CaseEvents.Type[] { CaseEvents.Type.PM_ASSIGNED,
				CaseEvents.Type.COORDINATOR_ASSIGNED, CaseEvents.Type.EXPERT_ASSIGNED, CaseEvents.Type.EXPERT_ACCEPTED,
				CaseEvents.Type.EXPERT_DECLINED, CaseEvents.Type.EXPERT_TIMED_OUT,
				CaseEvents.Type.CASE_MANAGER_REASSIGNED, CaseEvents.Type.CASE_CLOSED }) {
			UUID id = UUID.randomUUID();
			Case subject = mock(Case.class);
			when(cases.findById(id)).thenReturn(Optional.of(subject));
			listener.on(event(type, id));
			verify(conversations).ensureAndSync(subject);
		}
	}

	@Test
	void anUnrelatedEventDoesNothing() {
		listener.on(event(CaseEvents.Type.DRAFT_SUBMITTED, UUID.randomUUID()));
		verify(conversations, never()).ensureAndSync(any());
	}

	@Test
	void aChatFailureIsSwallowedSoTheCaseChangeStands() {
		UUID id = UUID.randomUUID();
		Case subject = mock(Case.class);
		when(cases.findById(id)).thenReturn(Optional.of(subject));
		when(conversations.ensureAndSync(subject)).thenThrow(new IllegalStateException("boom"));

		assertThatCode(() -> listener.on(event(CaseEvents.Type.PM_ASSIGNED, id))).doesNotThrowAnyException();
	}

	/**
	 * Review I3: the sync runs in a NEW transaction the listener opens itself (after commit, a plain
	 * joined transaction would never commit), committed on success, rolled back and swallowed on
	 * failure — so a chat problem never reaches whoever published the event.
	 */
	@Test
	void theSyncRunsInItsOwnNewTransactionCommittedOnSuccessRolledBackOnFailure() {
		UUID ok = UUID.randomUUID();
		UUID bad = UUID.randomUUID();
		Case fine = mock(Case.class);
		Case broken = mock(Case.class);
		when(cases.findById(ok)).thenReturn(Optional.of(fine));
		when(cases.findById(bad)).thenReturn(Optional.of(broken));
		when(conversations.ensureAndSync(broken)).thenThrow(new IllegalStateException("boom"));
		org.springframework.transaction.TransactionStatus status =
				new org.springframework.transaction.support.SimpleTransactionStatus();
		when(transactions.getTransaction(any())).thenReturn(status);

		listener.on(event(CaseEvents.Type.PM_ASSIGNED, ok));
		assertThatCode(() -> listener.on(event(CaseEvents.Type.PM_ASSIGNED, bad))).doesNotThrowAnyException();

		verify(transactions, org.mockito.Mockito.times(2)).getTransaction(org.mockito.ArgumentMatchers.argThat(
				(d) -> d.getPropagationBehavior() == org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
		verify(transactions).commit(status);
		verify(transactions).rollback(status);
	}
}
