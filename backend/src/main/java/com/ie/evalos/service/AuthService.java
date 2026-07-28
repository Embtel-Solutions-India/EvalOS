package com.ie.evalos.service;

import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.StaffPrincipal;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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
		Authentication authenticated = authenticationManager
				.authenticate(new UsernamePasswordAuthenticationToken(email, rawPassword));
		StaffPrincipal principal = (StaffPrincipal) authenticated.getPrincipal();
		return new Session(jwtService.issue(principal), principal);
	}
}
