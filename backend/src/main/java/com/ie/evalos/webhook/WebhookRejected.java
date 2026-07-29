package com.ie.evalos.webhook;

import org.springframework.http.HttpStatus;

/**
 * An inbound delivery that never got as far as a side effect: bad signature,
 * unknown endpoint, unusable payload. Carries the status the source should see,
 * because the source is a machine deciding whether to retry — a 4xx here means
 * "do not bother", and only an unhandled failure downstream becomes a retriable
 * 5xx.
 *
 * <p>Messages are safe to return: they never say whether a secret was missing or
 * merely wrong, and never echo the payload back.
 */
public class WebhookRejected extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public WebhookRejected(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}
}
