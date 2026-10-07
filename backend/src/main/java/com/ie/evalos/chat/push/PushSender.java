package com.ie.evalos.chat.push;

import java.security.Security;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

	/** A push service holds a message this long for a device that is offline, and delivers it when it is back. */
	private static final int TTL_SECONDS = 24 * 60 * 60;
	/** Waits before each retry of a push the push service could not take right now (network, 429, 5xx). */
	private static final long[] RETRY_SECONDS = { 30, 120, 600 };

	private static final Logger log = LoggerFactory.getLogger(PushSender.class);

	private final PushService service;
	// ponytail: in-memory retries are lost on a restart; move to a DB outbox if restarts during a push outage matter.
	private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor((r) -> {
		Thread t = new Thread(r, "push-retry");
		t.setDaemon(true);
		return t;
	});

	PushSender(PushSettings settings) {
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
		deliver(sender, subscriptions, to, json, 0);
	}

	private static void deliver(PushSender sender, PushSubscriptionRepository subscriptions, PushSubscription to,
			String json, int attempt) {
		switch (sender.send(to, json)) {
			case SENT -> {
				to.markSent(java.time.Instant.now());
				subscriptions.save(to);
			}
			case GONE -> subscriptions.delete(to);
			case RETRY -> {
				if (attempt < RETRY_SECONDS.length) {
					sender.retries.schedule(() -> deliver(sender, subscriptions, to, json, attempt + 1),
							RETRY_SECONDS[attempt], TimeUnit.SECONDS);
				}
			}
			case FAILED -> {
				// Logged by the sender; retrying would not change a refusal.
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
			int status = service.send(Notification.builder().subscription(subscription).payload(json).urgency(Urgency.HIGH)
					.ttl(TTL_SECONDS).build()).getStatusLine().getStatusCode();
			if (status == 404 || status == 410) {
				return Outcome.GONE;
			}
			if (status < 200 || status >= 300) {
				// Was silent. 401/403 means the subscription was made with another VAPID key (rotated, or
				// another environment) and will never deliver; 413/429/5xx are the push service's own.
				log.warn("Web push to {} was refused with HTTP {}", to.getEndpoint(), status);
				return Outcome.FAILED;
			}
			return Outcome.SENT;
		}
		catch (Exception failed) {
			log.warn("Web push to {} failed; will retry", to.getEndpoint(), failed);
			return Outcome.RETRY;
		}
	}
}
