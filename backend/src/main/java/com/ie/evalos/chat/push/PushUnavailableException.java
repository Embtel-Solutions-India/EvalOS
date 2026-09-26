package com.ie.evalos.chat.push;

/** No VAPID keys here (Unit 57 §6). Answered 404 PUSH_UNAVAILABLE so the apps hide the opt-in card. */
public class PushUnavailableException extends RuntimeException {

	public PushUnavailableException() {
		super("Notifications are not set up here.");
	}
}
