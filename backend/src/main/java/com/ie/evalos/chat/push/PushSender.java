package com.ie.evalos.chat.push;

import java.security.Security;

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

	public enum Outcome { SENT, GONE, FAILED }

	private static final Logger log = LoggerFactory.getLogger(PushSender.class);

	private final PushService service;

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
		switch (sender.send(to, json)) {
			case SENT -> {
				to.markSent(java.time.Instant.now());
				subscriptions.save(to);
			}
			case GONE -> subscriptions.delete(to);
			case FAILED -> {
				// Logged by the sender.
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
			int status = service.send(new Notification(subscription, json, Urgency.HIGH)).getStatusLine().getStatusCode();
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
			log.warn("Web push to {} failed", to.getEndpoint(), failed);
			return Outcome.FAILED;
		}
	}
}
