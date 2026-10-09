package com.ie.evalos.service;

import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.StaffPrincipal;

import io.micrometer.core.instrument.Metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * The password-for-token exchange. Credential checking itself belongs to Spring
 * Security's {@link AuthenticationManager} — this only turns the result into a
 * signed session, so there is one place a token is ever minted.
 */
@Service
public class AuthService {

	/** A freshly issued token and the identity it was issued for. */
	public record Session(String token, StaffPrincipal principal) {
	}

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	private final AuthenticationManager authenticationManager;
	private final JwtService jwtService;

	AuthService(AuthenticationManager authenticationManager, JwtService jwtService) {
		this.authenticationManager = authenticationManager;
		this.jwtService = jwtService;
	}

	/**
	 * @throws org.springframework.security.core.AuthenticationException on bad
	 *         credentials or an inactive member — the caller must not
	 *         distinguish the two in its response
	 */
	public Session login(String email, String rawPassword) {
		Authentication authenticated;
		try {
			authenticated = authenticationManager
					.authenticate(new UsernamePasswordAuthenticationToken(email, rawPassword));
		}
		catch (AuthenticationException refused) {
			// No address, no password: the request id ties this line to the caller's other lines, and a
			// spike in the counter is the brute-force signal.
			Metrics.counter("evalos.auth.login", "outcome", "refused").increment();
			log.warn("Staff sign-in refused: {}", refused.getClass().getSimpleName());
			throw refused;
		}
		StaffPrincipal principal = (StaffPrincipal) authenticated.getPrincipal();
		Metrics.counter("evalos.auth.login", "outcome", "ok").increment();
		log.info("Staff sign-in: member {} ({})", principal.memberId(), principal.role());
		return new Session(jwtService.issue(principal), principal);
	}
}
