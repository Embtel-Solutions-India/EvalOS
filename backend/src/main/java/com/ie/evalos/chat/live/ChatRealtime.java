package com.ie.evalos.chat.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ie.evalos.chat.ChatIdentity;

import io.ably.lib.rest.AblyRest;
import io.ably.lib.rest.Auth;
import io.ably.lib.types.AblyException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only class that talks to Ably (Unit 57 §5, D50).
 *
 * <p><strong>Ably relays; it is never the record.</strong> Every change is already committed in
 * PostgreSQL when it is published here, so a failed publish is logged and dropped: the recipient's
 * app catches up over REST on its next reconnect.
 */
@Component
public class ChatRealtime {

	private static final Logger log = LoggerFactory.getLogger(ChatRealtime.class);
	private static final long TOKEN_TTL_MS = 60 * 60 * 1000L;
	private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	private final AblyRest rest;

	ChatRealtime(AblySettings settings) {
		AblyRest built = null;
		if (settings.enabled()) {
			try {
				built = new AblyRest(settings.apiKey());
			}
			catch (AblyException invalid) {
				throw new IllegalStateException("ABLY_API_KEY is not a valid Ably key", invalid);
			}
		}
		else {
			log.warn("No ABLY_API_KEY configured - case chat works over REST without live updates.");
		}
		this.rest = built;
	}

	public boolean enabled() {
		return rest != null;
	}

	public AblyToken token(ChatIdentity who) {
		if (rest == null) {
			throw new RealtimeUnavailableException();
		}
		Auth.TokenParams params = new Auth.TokenParams();
		params.clientId = ChatChannels.clientId(who);
		params.capability = ChatChannels.capability(who);
		params.ttl = TOKEN_TTL_MS;
		try {
			Auth.TokenRequest request = rest.auth.createTokenRequest(params, null);
			return new AblyToken(request.keyName, request.clientId, request.capability, request.ttl,
					request.timestamp, request.nonce, request.mac);
		}
		catch (AblyException failed) {
			throw new RealtimeUnavailableException();
		}
	}

	public void publish(String channel, String event, Object data) {
		if (rest == null) {
			return;
		}
		try {
			rest.channels.get(channel).publish(event, data instanceof String s ? s : JSON.writeValueAsString(data));
		}
		catch (AblyException | JsonProcessingException failed) {
			log.warn("Ably publish of {} to {} failed; the recipient catches up over REST", event, channel, failed);
		}
	}

	public boolean isPresent(String channel) {
		if (rest == null) {
			return false;
		}
		try {
			return rest.channels.get(channel).presence.get(null).items().length > 0;
		}
		catch (AblyException failed) {
			// Unknown is treated as offline: a spare push beats a missed one.
			log.warn("Ably presence check on {} failed", channel, failed);
			return false;
		}
	}
}
