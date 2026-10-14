package com.ie.evalos.common;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.security.StaffPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Writes the standard envelope for failures raised inside the security filter
 * chain, which never reaches {@code @RestControllerAdvice}. Without this, Spring
 * would answer those with its own body shape and break the envelope invariant.
 */
@Component
public class ApiErrors {

	private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

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

	/**
	 * A security chain's refusal: logged, then written. A 403 is a WARN (someone signed in reached for
	 * something outside their role or scope); a 401 is INFO, because expired sessions make it routine.
	 *
	 * <p>Only the method, the path and the caller's id are logged — never the query string, a header or
	 * an email. No route that carries a secret in its path ({@code /api/webhooks/**},
	 * {@code /api/local-documents/**}) can reach here: both are permitAll.
	 */
	public void refuse(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
			String message) throws IOException {
		if (status == HttpStatus.FORBIDDEN) {
			log.warn("Refused {} {} {}: {} for {}", status.value(), request.getMethod(), request.getRequestURI(), code, caller());
		}
		else {
			log.info("Refused {} {} {}: {} for {}", status.value(), request.getMethod(), request.getRequestURI(), code, caller());
		}
		write(response, status, code, message);
	}

	/** Who was refused, by id only. Shared with {@link ApiExceptionHandler}'s 403. */
	static String caller() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			return "anonymous";
		}
		if (authentication.getPrincipal() instanceof StaffPrincipal staff) {
			return "member " + staff.memberId() + " (" + staff.role() + ")";
		}
		if (authentication.getPrincipal() instanceof PortalPrincipal portal) {
			return "portal access " + portal.portalAccessId() + " (" + portal.audience() + ")";
		}
		return "anonymous";
	}
}
