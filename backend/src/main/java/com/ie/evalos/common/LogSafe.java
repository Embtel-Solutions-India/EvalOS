package com.ie.evalos.common;

/** What a log line may say about a person (D82): enough to tell two apart, never the address itself. */
public final class LogSafe {

	private LogSafe() {
	}

	/**
	 * {@code ana.perez@example.com} to {@code a***@example.com}. The domain stays because it is what an
	 * operator diagnosing delivery needs (one provider refusing, a typo'd domain); the mailbox does not.
	 */
	public static String email(String email) {
		if (email == null) {
			return "null";
		}
		int at = email.lastIndexOf('@');
		return at < 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
	}
}
