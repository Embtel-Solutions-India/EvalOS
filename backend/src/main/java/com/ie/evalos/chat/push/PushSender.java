package com.ie.evalos.chat.push;

import java.security.Security;
import java.time.Instant;
import java.util.UUID;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import nl.martijndwars.webpush.Urgency;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** One encrypted Web Push (RFC 8291) with VAPID (RFC 8292), via nl.martijndwars:web-push. */
@Component
public class PushSender {

	public enum Outcome { SENT, GONE, FAILED, RETRY }

	/**
	 * Waits before each retry of a push the push service could not take right now (network, 429, 5xx),
	 * then gives up. Held as {@code push_outbox} rows (V92) and sent by the {@code PUSH_OUTBOX} sweep, so
	 * a restart during a push outage delays them rather than losing them.
	 */
	static final long[] RETRY_SECONDS = { 30, 120, 600 };

	private static final Logger log = LoggerFactory.getLogger(PushSender.class);

	private final PushService service;
	private final PushOutboxRepository outbox;
	private final PushSubscriptionRepository subscriptions;

	PushSender(PushSettings settings, PushOutboxRepository outbox, PushSubscriptionRepository subscriptions) {
		this.outbox = outbox;
		this.subscriptions = subscriptions;
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
		}
		PushService built = null;
		if (settings.enabled()) {
			try {
				built = new PushService(settings.publicKey(), settings.privateKey(), settings.subject());
			}
			catch (Exception invalid) {
				throw new IllegalStateException("EVALOS_PUSH_VAPID_* keys are not a valid VAPID pair", invalid);
			}
		}
		this.service = built;
	}

	/**
	 * Send, then keep the subscription's record honest: stamped on success, deleted when the push
	 * service says the browser is gone, kept on a failure (a bad minute is not a dead browser). The
	 * one delivery step chat and the bell share.
	 */
	public static void deliver(PushSender sender, PushSubscriptionRepository subscriptions, PushSubscription to,
			String json) {
		Outcome outcome = sender.send(to, json);
		if (outcome == Outcome.RETRY) {
			sender.queueRetry(to, json);
		}
		else {
			settle(subscriptions, to, outcome);
		}
	}

	/** The first retry of a push, as a row the sweep will send when it falls due. */
	void queueRetry(PushSubscription to, String json) {
		outbox.save(new PushRetry(to, json, Instant.now().plusSeconds(RETRY_SECONDS[0])));
	}

	/**
	 * Sends one owed push, in the {@code PUSH_OUTBOX} sweep's per-row transaction. The row goes when the
	 * push settles either way, and on another miss it waits for the next step of the backoff until there
	 * is none.
	 *
	 * @return true if it sent, false if the row was already gone or the push did not get through
	 */
	public boolean retry(UUID id) {
		PushRetry owed = outbox.findById(id).orElse(null);
		if (owed == null) {
			return false;
		}
		PushSubscription to = subscriptions.findById(owed.getSubscriptionId()).orElse(null);
		Outcome outcome = to == null ? Outcome.GONE : send(to, owed.getPayload());
		int next = owed.getRetries() + 1;
		if (outcome == Outcome.RETRY && next < RETRY_SECONDS.length) {
			owed.spendOne(Instant.now().plusSeconds(RETRY_SECONDS[next]));
			outbox.save(owed);
			return false;
		}
		outbox.delete(owed);
		if (to != null) {
			settle(subscriptions, to, outcome);
		}
		return outcome == Outcome.SENT;
	}

	private static void settle(PushSubscriptionRepository subscriptions, PushSubscription to, Outcome outcome) {
		switch (outcome) {
			case SENT -> {
				to.markSent(Instant.now());
				subscriptions.save(to);
			}
			case GONE -> subscriptions.delete(to);
			case FAILED, RETRY -> {
				// FAILED is logged by the sender, and retrying would not change a refusal; RETRY here is out of retries.
			}
		}
	}

	public Outcome send(PushSubscription to, String json) {
		if (service == null) {
			return Outcome.FAILED;
		}
		try {
			Subscription subscription = new Subscription(to.getEndpoint(),
					new Subscription.Keys(to.getP256dh(), to.getAuth()));
			// High urgency: a chat message is time-sensitive, and the default (normal) is what Android
			// holds back in Doze — so a phone with the browser closed heard minutes or hours late.
			org.apache.http.HttpResponse response = service.send(new Notification(subscription, json, Urgency.HIGH));
			int status = response.getStatusLine().getStatusCode();
			if (status == 404 || status == 410) {
				return Outcome.GONE;
			}
			if (status < 200 || status >= 300) {
				// Was silent. 401/403 means the subscription was made with another VAPID key (rotated, or
				// another environment) and will never deliver; 413/429/5xx are the push service's own.
				// The push service says why in the body (sender-id mismatch, bad JWT, expired): the status alone cannot tell them apart.
				log.warn("Web push to {} was refused with HTTP {}: {}", to.getEndpoint(), status, reason(response));
				return Outcome.FAILED;
			}
			return Outcome.SENT;
		}
		catch (Exception failed) {
			log.warn("Web push to {} failed (retryable)", to.getEndpoint(), failed);
			return Outcome.RETRY;
		}
	}

	private static String reason(org.apache.http.HttpResponse response) {
		try {
			String body = response.getEntity() == null ? "" : org.apache.http.util.EntityUtils.toString(response.getEntity());
			return body.length() > 300 ? body.substring(0, 300) : body;
		}
		catch (Exception unreadable) {
			return "(body unreadable)";
		}
	}
}
