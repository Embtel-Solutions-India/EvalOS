package com.ie.evalos.common;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Writes the standard envelope for failures raised inside the security filter
 * chain, which never reaches {@code @RestControllerAdvice}. Without this, Spring
 * would answer those with its own body shape and break the envelope invariant.
 */
@Component
public class ApiErrors {

	private final ObjectMapper objectMapper;

	ApiErrors(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public void write(HttpServletResponse response, HttpStatus status, String code, String message)
			throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
	}
}
