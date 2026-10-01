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
	/**
	 * Unit 68: a deactivated member is refused on their next request, not when their 8 h token
	 * expires. A provider because web-slice tests build no JPA layer; there, the check is skipped.
	 */
	private final org.springframework.beans.factory.ObjectProvider<com.ie.evalos.repository.TeamMemberRepository> members;

	JwtFilter(JwtService jwtService,
			org.springframework.beans.factory.ObjectProvider<com.ie.evalos.repository.TeamMemberRepository> members) {
		this.jwtService = jwtService;
		this.members = members;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {

		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER)
				&& SecurityContextHolder.getContext().getAuthentication() == null) {
			try {
				StaffPrincipal principal = jwtService.verify(header.substring(BEARER.length()));
				var repository = members.getIfAvailable();
				if (repository != null && !repository.existsByIdAndActiveTrue(principal.memberId())) {
					// Deactivated (or removed) since the token was signed: stay anonymous, 401.
					chain.doFilter(request, response);
					return;
				}
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
