package com.ie.evalos.chat.live;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The Ably API key. Empty = realtime off (a startup warning, not a failed boot). */
@Component
public class AblySettings {

	private final String apiKey;

	AblySettings(@Value("${evalos.ably.api-key:}") String apiKey) {
		this.apiKey = apiKey == null ? "" : apiKey.trim();
	}

	public boolean enabled() {
		return !apiKey.isEmpty();
	}

	public String apiKey() {
		return apiKey;
	}
}
