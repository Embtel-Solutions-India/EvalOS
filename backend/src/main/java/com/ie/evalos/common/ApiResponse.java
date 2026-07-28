package com.ie.evalos.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The one response envelope for every EvalOS endpoint — success and error.
 * No endpoint invents its own shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ApiError error) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, null);
	}

	public static <T> ApiResponse<T> error(String code, String message) {
		return new ApiResponse<>(false, null, new ApiError(code, message));
	}

	public record ApiError(String code, String message) {
	}
}
