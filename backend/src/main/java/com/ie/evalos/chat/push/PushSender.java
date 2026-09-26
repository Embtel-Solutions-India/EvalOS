package com.ie.evalos.chat.push;

import java.security.Security;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;

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

	public Outcome send(PushSubscription to, String json) {
		if (service == null) {
			return Outcome.FAILED;
		}
		try {
			Subscription subscription = new Subscription(to.getEndpoint(),
					new Subscription.Keys(to.getP256dh(), to.getAuth()));
			int status = service.send(new Notification(subscription, json)).getStatusLine().getStatusCode();
			if (status == 404 || status == 410) {
				return Outcome.GONE;
			}
			return status >= 200 && status < 300 ? Outcome.SENT : Outcome.FAILED;
		}
		catch (Exception failed) {
			log.warn("Web push to {} failed", to.getEndpoint(), failed);
			return Outcome.FAILED;
		}
	}
}
