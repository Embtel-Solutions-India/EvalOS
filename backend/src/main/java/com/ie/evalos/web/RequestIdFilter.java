package com.ie.evalos.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request one id, so its log lines — filters, security, controller, service, the
 * catch-all handler — can be pulled out of the stream together.
 *
 * <p>The id goes into the MDC as {@code requestId} (printed by {@code logging.pattern.correlation})
 * and back to the caller as {@code X-Request-Id}, so a user reporting an error can quote it. A
 * proxy's id is reused when it sends one, <strong>but only if it is a short plain token</strong>:
 * anything else is replaced, because the value is written verbatim into every log line and a header
 * carrying a newline would forge entries.
 *
 * <p>First in the servlet chain, ahead of Spring Security (-100), so a 401 or 403 is logged with it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";

	private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {
		String presented = request.getHeader(HEADER);
		String id = presented != null && SAFE.matcher(presented).matches() ? presented : UUID.randomUUID().toString();
		MDC.put(MDC_KEY, id);
		response.setHeader(HEADER, id);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}
}
