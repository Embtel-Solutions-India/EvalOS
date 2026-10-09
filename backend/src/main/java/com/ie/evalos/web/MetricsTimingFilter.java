package com.ie.evalos.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Says how long each dashboard read took: a {@code Server-Timing: app;dur=…} header on every
 * {@code /api/metrics/**} response, which the browser's Network tab shows under Timing, and a log line
 * for any that took a second or more.
 *
 * <p>It exists to answer "which dashboard request is slow, and is it the server?" with a measurement
 * rather than a guess. The wrapper buffers the response so the header can be added after the handler
 * has run; metrics payloads are small. It times the whole request including the security filters
 * inside it, which is what the browser waits for bar the network.
 */
@Component
// Ahead of Spring Security's filter chain (order -100), so its time and a 401 or 403 it answers are measured too.
// One behind RequestIdFilter, so the slow-read line carries the request id.
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class MetricsTimingFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(MetricsTimingFilter.class);

	/** A dashboard read at or above this is logged, so a slow one is findable without opening the browser. */
	static final long SLOW_MS = 1000;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/metrics/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
		long started = System.nanoTime();
		try {
			chain.doFilter(request, wrapped);
		}
		finally {
			long millis = (System.nanoTime() - started) / 1_000_000;
			wrapped.setHeader("Server-Timing", "app;dur=" + millis);
			if (millis >= SLOW_MS) {
				log.warn("Slow metrics read: {} {} took {} ms", request.getMethod(), request.getRequestURI(), millis);
			}
			wrapped.copyBodyToResponse();
		}
	}
}
