package com.ie.evalos.chat.push;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** VAPID keys and the three app origins a push opens. Empty keys = push off. */
@Component
public class PushSettings {

	private final String publicKey;
	private final String privateKey;
	private final String subject;
	private final String staffOrigin;
	private final String clientBase;
	private final String expertBase;

	@Autowired
	public PushSettings(@Value("${evalos.push.vapid-public:}") String publicKey,
			@Value("${evalos.push.vapid-private:}") String privateKey, @Value("${evalos.push.subject:}") String subject,
			@Value("${evalos.chat.staff-origin}") String staffOrigin,
			@Value("${evalos.portal.client-base-url}") String clientBase,
			@Value("${evalos.portal.expert-base-url}") String expertBase) {
		this.publicKey = publicKey.trim();
		this.privateKey = privateKey.trim();
		this.subject = subject.trim();
		this.staffOrigin = staffOrigin;
		this.clientBase = clientBase;
		this.expertBase = expertBase;
		if (!enabled()) {
			LoggerFactory.getLogger(PushSettings.class)
					.warn("No VAPID keys configured (EVALOS_PUSH_VAPID_*) - chat works, push notifications are off.");
		}
	}

	public boolean enabled() {
		return !publicKey.isEmpty() && !privateKey.isEmpty() && !subject.isEmpty();
	}

	public String publicKey() { return publicKey; }
	public String privateKey() { return privateKey; }
	public String subject() { return subject; }
	public String staffOrigin() { return staffOrigin; }
	public String clientBase() { return clientBase; }
	public String expertBase() { return expertBase; }
}
