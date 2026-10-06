package com.ie.evalos.chat.push;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.chat.live.ChatPresence;
import com.ie.evalos.domain.Case;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ClientAccountRepository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Push for the case events a client or an expert must not miss while the portal is closed — the same
 * events the four case emails already announce (D58, D67), plus a hold, a resume and an update the
 * staff wrote for the client (D74). Email stays: it is the record and works with no opt-in; this is the
 * one that reaches a phone in someone's pocket.
 *
 * <p>Rides chat's push (the same {@code push_subscriptions}, VAPID keys, sender and executor), and like
 * the bell and chat it skips a person who has the portal open, whose screens already update live
 * (D68). The body is a fixed line plus the case code — <strong>never what a remark or a hold's reason
 * says</strong>: a lock-screen is not where a client's case is read. The click opens the case in the
 * portal, which is signed in (D73).
 */
@Component
public class CasePushNotifier {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** A fixed title and line per event; null type = not pushed. */
	private record Line(String title, String body) {
	}

	private final PushSettings settings;
	private final PushSubscriptionRepository subscriptions;
	private final PushSender sender;
	private final ChatPresence presence;
	private final CaseRepository cases;
	private final ClientAccountRepository accounts;
	private final Executor executor;

	CasePushNotifier(PushSettings settings, PushSubscriptionRepository subscriptions, PushSender sender,
			ChatPresence presence, CaseRepository cases, ClientAccountRepository accounts,
			@Qualifier("chatPushExecutor") Executor executor) {
		this.settings = settings;
		this.subscriptions = subscriptions;
		this.sender = sender;
		this.presence = presence;
		this.cases = cases;
		this.accounts = accounts;
		this.executor = executor;
	}

	private static Line clientLine(CaseEvents.Type type) {
		return switch (type) {
			case CHECKLIST_REQUESTED -> new Line("Documents needed", "Open your case to upload them.");
			case CHECKLIST_CHASED -> new Line("Reminder: documents needed", "Open your case to upload them.");
			case DRAFT_READY_FOR_CLIENT -> new Line("Your draft is ready to review", "Open your case to review it.");
			case CASE_DELIVERED -> new Line("Your documents are ready", "Open your case to download them.");
			case CASE_ON_HOLD -> new Line("Your case is on hold", "Open your case to see why.");
			case CASE_RESUMED -> new Line("Your case has resumed", "Open your case for the latest status.");
			case CLIENT_REMARK_ADDED -> new Line("New update on your case", "Open your case to read it.");
			default -> null;
		};
	}

	private static Line expertLine(CaseEvents.Type type) {
		return switch (type) {
			case EXPERT_ASSIGNED -> new Line("A new case is offered to you", "Open it to review the offer.");
			case EXPERT_SENT_FOR_SIGNING -> new Line("A letter is ready to sign", "Open the case to sign it.");
			default -> null;
		};
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void on(CaseEvents.CaseEvent event) {
		if (!settings.enabled() || (clientLine(event.type()) == null && expertLine(event.type()) == null)) {
			return;
		}
		// Presence is an Ably call and a send is HTTPS: never on the request thread.
		executor.execute(() -> push(event));
	}

	private void push(CaseEvents.CaseEvent event) {
		Case subject = cases.findById(event.caseId()).filter((row) -> event.brandId().equals(row.getBrandId())).orElse(null);
		if (subject == null) {
			return;
		}
		Line toClient = clientLine(event.type());
		if (toClient != null && subject.getContactId() != null) {
			accounts.findByBrandIdAndContactId(subject.getBrandId(), subject.getContactId()).ifPresent(
					(account) -> deliver(ParticipantKind.CLIENT, account.getId(), toClient, subject,
							settings.clientBase() + "/cases/" + subject.getId(), event.type()));
		}
		Line toExpert = expertLine(event.type());
		if (toExpert != null && subject.getExpertId() != null) {
			deliver(ParticipantKind.EXPERT, subject.getExpertId(), toExpert, subject,
					settings.expertBase() + "/case?caseId=" + subject.getId(), event.type());
		}
	}

	private void deliver(ParticipantKind kind, UUID id, Line line, Case subject, String url, CaseEvents.Type type) {
		if (presence.isOnline(kind, id)) {
			return;
		}
		String payload = payload(line, subject.getCaseCode(), url, type, subject.getId());
		for (PushSubscription to : subscriptions.findBySubscriberKindAndSubscriberId(kind, id)) {
			PushSender.deliver(sender, subscriptions, to, payload);
		}
	}

	private static String payload(Line line, String caseCode, String url, CaseEvents.Type type, UUID caseId) {
		try {
			return JSON.writeValueAsString(Map.of("title", line.title(), "body", caseCode + " · " + line.body(),
					"url", url, "tag", "case:" + type + ":" + caseId));
		}
		catch (JsonProcessingException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

}
