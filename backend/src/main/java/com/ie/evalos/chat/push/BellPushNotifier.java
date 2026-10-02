package com.ie.evalos.chat.push;

import java.util.Map;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.chat.live.ChatPresence;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.notification.BellRaised;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The bell's push (D37: notifications are in-app <strong>and</strong> push). Every committed
 * {@code notification} row is pushed to its recipient's browsers, unless they have the app open —
 * then the bell itself updates live (Unit 70), and a push would say it twice.
 *
 * <p>Rides chat's push: the same {@code push_subscriptions} rows (a staff member subscribes once,
 * from Conversations or the bell), the same VAPID keys and sender, the same executor. The body is
 * the bell's own line, which names a case by its code and never a client.
 */
@Component
public class BellPushNotifier {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final PushSettings settings;
	private final PushSubscriptionRepository subscriptions;
	private final PushSender sender;
	private final ChatPresence presence;
	private final Executor executor;

	BellPushNotifier(PushSettings settings, PushSubscriptionRepository subscriptions, PushSender sender,
			ChatPresence presence, @Qualifier("chatPushExecutor") Executor executor) {
		this.settings = settings;
		this.subscriptions = subscriptions;
		this.sender = sender;
		this.presence = presence;
		this.executor = executor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void on(BellRaised raised) {
		if (!settings.enabled()) {
			return;
		}
		// Presence is an Ably call and a send is HTTPS: never on the request thread.
		executor.execute(() -> push(raised));
	}

	private void push(BellRaised raised) {
		if (presence.isOnline(ParticipantKind.STAFF, raised.recipientId())) {
			return;
		}
		String payload = payload(raised);
		for (PushSubscription to : subscriptions.findBySubscriberKindAndSubscriberId(ParticipantKind.STAFF,
				raised.recipientId())) {
			PushSender.deliver(sender, subscriptions, to, payload);
		}
	}

	private String payload(BellRaised raised) {
		String path = raised.caseId() != null ? "/cases/" + raised.caseId()
				: raised.type() == NotificationType.HIRING_PIPELINE_UPDATED ? "/hiring" : "/";
		try {
			// The tag is the alert and its case, so a repeat (another "deadline at risk" on the same
			// case) replaces the earlier one on the lock screen instead of stacking.
			return JSON.writeValueAsString(Map.of("title", title(raised.type()), "body", raised.body(),
					"url", settings.staffOrigin() + path, "tag", "bell:" + raised.type() + ":" + raised.caseId()));
		}
		catch (JsonProcessingException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	/** Named, not derived from the enum: {@code SLA_AT_RISK} would read as "Sla at risk". */
	static String title(NotificationType type) {
		return switch (type) {
			case NEW_LEAD -> "New lead";
			case NEW_CASE_IN_POOL -> "New case needs a PM";
			case CASE_ASSIGNED -> "Case assigned to you";
			case STAGE_CHANGED -> "Case moved on";
			case SLA_AT_RISK -> "Deadline at risk";
			case SLA_OVERDUE -> "Deadline missed";
			case EXCEPTION_RAISED -> "Case on hold";
			case DOC_CHASE_DUE -> "Documents to chase";
			case DOCS_ESCALATED -> "Documents escalated";
			case EXPERT_SIGN_AT_RISK -> "Signature at risk";
			case EXPERT_SIGN_OVERDUE -> "Signature overdue";
			case HIRING_PIPELINE_UPDATED -> "Hiring pipeline";
			case PAYOUT_DUE -> "Payout due";
			case PAYOUT_CONFIRMED -> "Payout confirmed";
		};
	}
}
