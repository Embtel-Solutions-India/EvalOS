package com.ie.evalos.security;

import java.io.IOException;

import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a {@code Bearer} token into the authenticated {@link StaffPrincipal},
 * which is what {@link TenantContext} reads for brand/team/self scoping.
 *
 * <p>A missing or unparseable token leaves the context empty and lets the chain
 * continue — the authorization rules, not this filter, decide whether anonymous
 * is acceptable for the path.
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private final JwtService jwtService;

	JwtFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {

		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER)
				&& SecurityContextHolder.getContext().getAuthentication() == null) {
			try {
				StaffPrincipal principal = jwtService.verify(header.substring(BEARER.length()));
				var authentication = new UsernamePasswordAuthenticationToken(
						principal, null, principal.getAuthorities());
				authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
			catch (JwtException | IllegalArgumentException ex) {
				// Tampered, expired, or malformed: stay anonymous and let the
				// entry point answer 401. Never log the token itself.
				SecurityContextHolder.clearContext();
				logger.debug("Rejected bearer token: " + ex.getMessage());
			}
		}

		chain.doFilter(request, response);
	}
}
