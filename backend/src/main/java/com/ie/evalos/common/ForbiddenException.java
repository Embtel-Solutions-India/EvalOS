package com.ie.evalos.common;

/**
 * The caller is authenticated but may not touch this row — wrong brand, wrong
 * team, or not their assignment. Mapped to 403 by {@link ApiExceptionHandler}.
 */
public class ForbiddenException extends RuntimeException {

	public ForbiddenException(String message) {
		super(message);
	}
}
