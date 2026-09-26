package com.ie.evalos.chat.live;

/** An Ably TokenRequest, field for field — ably-js takes this as the authUrl / authCallback answer. */
public record AblyToken(String keyName, String clientId, String capability, long ttl, long timestamp,
		String nonce, String mac) {
}
