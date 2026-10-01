package com.ie.evalos.chat.live;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.repository.CaseDocumentRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The only publisher of screen-refresh signals (Unit 70, D68): {@code case.changed {caseId}} and
 * {@code notifications.changed}. <strong>A signal, never data</strong> — the screen re-reads over
 * its own scoped REST route, so brand checks and redaction stay where they are.
 *
 * <p>Calls inside a transaction collect into one set bound to it and publish once, after commit,
 * off the request thread; a rolled-back write publishes nothing. Outside a transaction a call
 * publishes at once. Nothing happens when Ably is not configured.
 */
@Component
public class CaseLive {

	private static final Object KEY = CaseLive.class;

	private final ChatRealtime realtime;
	private final CaseRepository cases;
	private final CaseDocumentRepository documents;
	private final ClientAccountRepository clients;
	private final ExpertCaseOfferRepository offers;
	private final Executor executor;

	CaseLive(ChatRealtime realtime, CaseRepository cases, CaseDocumentRepository documents,
			ClientAccountRepository clients, ExpertCaseOfferRepository offers,
			@Qualifier("chatFanoutExecutor") Executor executor) {
		this.realtime = realtime;
		this.cases = cases;
		this.documents = documents;
		this.clients = clients;
		this.offers = offers;
		this.executor = executor;
	}

	/** A case-owned row was written. */
	public void touched(UUID brandId, UUID caseId) {
		if (brandId != null && caseId != null) {
			add(p -> p.cases.put(caseId, brandId));
		}
	}

	/** A draft comment was written: its case is resolved from the document after commit (§2.1). */
	public void touchedDocument(UUID brandId, UUID documentId) {
		if (brandId != null && documentId != null) {
			add(p -> p.documents.put(documentId, brandId));
		}
	}

	/** A staff member's bell changed: a notification for them, or one of theirs marked read. */
	public void notificationsChanged(UUID recipientId) {
		if (recipientId != null) {
			add(p -> p.recipients.add(recipientId));
		}
	}

	private void add(Consumer<Pending> change) {
		if (!realtime.enabled()) {
			return;
		}
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			Pending now = new Pending();
			change.accept(now);
			executor.execute(() -> publish(now));
			return;
		}
		Pending bound = (Pending) TransactionSynchronizationManager.getResource(KEY);
		if (bound == null) {
			Pending fresh = new Pending();
			TransactionSynchronizationManager.bindResource(KEY, fresh);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					executor.execute(() -> publish(fresh));
				}

				@Override
				public void afterCompletion(int status) {
					TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
				}
			});
			bound = fresh;
		}
		change.accept(bound);
	}

	void publish(Pending p) {
		p.documents.forEach((documentId, brandId) -> documents.findById(documentId)
				.filter(d -> brandId.equals(d.getBrandId()))
				.ifPresent(d -> p.cases.put(d.getCaseId(), brandId)));
		p.cases.forEach(this::publishCase);
		p.recipients.forEach(recipient -> realtime.publish(ChatChannels.personal(ParticipantKind.STAFF, recipient),
				"notifications.changed", new ChatEnvelope("notifications.changed", null, Map.of())));
	}

	private void publishCase(UUID caseId, UUID brandId) {
		ChatEnvelope signal = new ChatEnvelope("case.changed", null, Map.of("caseId", caseId));
		realtime.publish(ChatChannels.liveBrand(brandId), signal.type(), signal);
		// A listener has no caller whose scope applies (as CaseMailListener): the brand is checked
		// by hand, so a stray id can never reach another brand's client or expert.
		Case subject = cases.findById(caseId).filter(c -> brandId.equals(c.getBrandId())).orElse(null);
		if (subject == null) {
			return;
		}
		if (subject.getContactId() != null) {
			clients.findByBrandIdAndContactId(brandId, subject.getContactId()).ifPresent(account -> realtime
					.publish(ChatChannels.personal(ParticipantKind.CLIENT, account.getId()), signal.type(), signal));
		}
		Set<UUID> experts = new LinkedHashSet<>();
		if (subject.getExpertId() != null) {
			experts.add(subject.getExpertId());
		}
		offers.findByCaseIdAndOutcome(caseId, OfferOutcome.OFFERED).stream()
				.filter(o -> brandId.equals(o.getBrandId()))
				.forEach(o -> experts.add(o.getExpertId()));
		experts.forEach(expert -> realtime.publish(ChatChannels.personal(ParticipantKind.EXPERT, expert),
				signal.type(), signal));
	}

	/** One transaction's worth of signals, each id once. */
	static final class Pending {
		final Map<UUID, UUID> cases = new LinkedHashMap<>();
		final Map<UUID, UUID> documents = new LinkedHashMap<>();
		final Set<UUID> recipients = new LinkedHashSet<>();
	}
}
