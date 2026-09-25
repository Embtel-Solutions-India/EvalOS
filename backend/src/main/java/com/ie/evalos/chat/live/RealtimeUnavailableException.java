package com.ie.evalos.chat.live;

/** No Ably key here. Answered 503 REALTIME_UNAVAILABLE; the apps fall back to polling REST. */
public class RealtimeUnavailableException extends RuntimeException {

	public RealtimeUnavailableException() {
		super("Live updates are not set up here (ABLY_API_KEY). Chat still works; refresh to see new messages.");
	}
}
