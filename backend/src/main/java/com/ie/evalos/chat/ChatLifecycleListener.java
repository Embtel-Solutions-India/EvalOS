package com.ie.evalos.chat;

import java.util.EnumSet;
import java.util.Set;

import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Moves a case's conversations when the case moves (Unit 57 §3).
 *
 * <p><strong>After commit, in a new transaction, and never throwing.</strong> The listener opens
 * the REQUIRES_NEW transaction itself, inside its try: a failure rolls back only that transaction and
 * is caught here. (An annotated transaction on this method was marked rollback-only by the failing
 * sync and then threw UnexpectedRollbackException at commit, past the catch.) A chat problem must
 * not undo an assignment somebody just made; the hourly {@code CHAT_RECONCILE} sweep repairs whatever
 * this misses. {@code fallbackExecution} so an event published outside a transaction still lands.
 */
@Component
public class ChatLifecycleListener {

	private static final Logger log = LoggerFactory.getLogger(ChatLifecycleListener.class);

	static final Set<CaseEvents.Type> MOVES_THE_CHAT = EnumSet.of(CaseEvents.Type.CASE_CREATED,
			CaseEvents.Type.PM_ASSIGNED, CaseEvents.Type.COORDINATOR_ASSIGNED, CaseEvents.Type.EXPERT_ASSIGNED,
			CaseEvents.Type.EXPERT_ACCEPTED, CaseEvents.Type.EXPERT_DECLINED, CaseEvents.Type.EXPERT_TIMED_OUT,
			CaseEvents.Type.CASE_MANAGER_REASSIGNED, CaseEvents.Type.CASE_CLOSED);

	private final CaseRepository cases;
	private final ConversationService conversations;
	private final TransactionTemplate newTransaction;

	ChatLifecycleListener(CaseRepository cases, ConversationService conversations,
			PlatformTransactionManager transactions) {
		this.cases = cases;
		this.conversations = conversations;
		this.newTransaction = new TransactionTemplate(transactions);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void on(CaseEvents.CaseEvent event) {
		if (!MOVES_THE_CHAT.contains(event.type())) {
			return;
		}
		try {
			// Unscoped by id, as NotificationListeners does: a listener has no caller whose scope applies.
			// A NEW transaction, opened here: after commit the finished one is still bound, and joining
			// it would never commit. Its rollback on failure stays inside this try.
			newTransaction.executeWithoutResult(
					(status) -> cases.findById(event.caseId()).ifPresent(conversations::ensureAndSync));
		}
		catch (RuntimeException failed) {
			log.warn("Chat did not follow {} on case {}; CHAT_RECONCILE will repair it", event.type(),
					event.caseId(), failed);
		}
	}
}
