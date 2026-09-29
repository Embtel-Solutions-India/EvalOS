package com.ie.evalos.common;

/**
 * 404 for a thing the caller may ask about but which is not there yet — the delivered files before
 * delivery (Unit 58 §5). Not for "not yours": that stays {@link ForbiddenException}, one answer
 * whether or not the row exists.
 */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}
}
