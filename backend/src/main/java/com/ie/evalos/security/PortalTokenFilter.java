package com.ie.evalos.security;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.service.PortalAccessService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns an {@code X-Portal-Token} header into the authenticated {@link PortalPrincipal}, for the
 * portal chain and nothing else.
 *
 * <p><strong>Not a {@code @Component}.</strong> {@code SecurityConfig} constructs it, so Spring
 * Boot cannot auto-register it as a servlet filter for every request — which would put a portal
 * principal in the context on staff routes. It exists only inside the chain that matches
 * {@code /api/portal/**}.
 *
 * <p>A header in preference to a query parameter, because a query parameter lands in access logs,
 * {@code Referer} headers and browser history. The SPA reads the token out of the URL fragment
 * (never sent to a server) and sends it here.
 *
 * <p>An absent or unusable token leaves the context empty and lets the chain continue: the chain's
 * {@code authenticated()} rule and entry point answer the 401, so unknown, expired and revoked
 * produce one identical refusal — as does a missing header.
 */
public class PortalTokenFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Portal-Token";

	private final PortalAccessService portalAccess;
	private final ApiErrors apiErrors;
	private final int perMinute;

	/**
	 * One fixed window per instance, cleared when the window rolls.
	 *
	 * <p>ponytail: in-memory and per-instance, so two app instances would each allow the limit.
	 * EvalOS runs single-instance at the NFR's stated scale; move this to Redis or to a gateway
	 * limit if that changes. The map is bounded by the number of distinct callers inside one
	 * minute and is emptied on the roll, so it cannot grow without limit.
	 */
	private final Map<String, Integer> hits = new ConcurrentHashMap<>();

	private volatile long window;

	public PortalTokenFilter(PortalAccessService portalAccess, ApiErrors apiErrors, int perMinute) {
		this.portalAccess = portalAccess;
		this.apiErrors = apiErrors;
		this.perMinute = perMinute;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {

		if (overLimit(request.getRemoteAddr(), Instant.now())) {
			// Refused before the token is looked at, so a flood of guesses costs no database read.
			apiErrors.write(response, HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
					"Too many requests. Please wait a minute and try again.");
			return;
		}

		String presented = request.getHeader(HEADER);
		if (presented != null && SecurityContextHolder.getContext().getAuthentication() == null) {
			portalAccess.resolve(presented).ifPresent(principal -> {
				// No authorities: the audience is the authorization on this surface, and it is
				// checked in exactly one place (PortalPrincipal.current). A role name here would be
				// a second place for the same rule to be stated, and to disagree.
				SecurityContextHolder.getContext().setAuthentication(
						new UsernamePasswordAuthenticationToken(principal, null, List.of()));
			});
		}

		chain.doFilter(request, response);
	}

	/**
	 * True when this caller has already spent the window's allowance.
	 *
	 * <p>Keyed on the remote address, which is all an unauthenticated caller offers. The
	 * clear-on-roll races harmlessly: at worst two threads clear the same window twice, which loses
	 * a count or two at a minute boundary and cannot let the limit be exceeded by more than that.
	 *
	 * <p>Package-private so it can be tested as what it is — a counter and a window — rather than
	 * through sixty HTTP requests.
	 */
	boolean overLimit(String caller, Instant now) {
		long current = now.getEpochSecond() / 60;
		if (current != window) {
			window = current;
			hits.clear();
		}
		return hits.merge(caller, 1, Integer::sum) > perMinute;
	}
}
