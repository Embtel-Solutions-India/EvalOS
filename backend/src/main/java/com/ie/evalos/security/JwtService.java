package com.ie.evalos.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.ie.evalos.domain.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies the short-lived staff access token. The token carries the
 * whole tenant identity (role, brand, team) so no request needs a database hit
 * to be scoped — the trade-off is that a role or brand change only takes effect
 * on the next login, which the short TTL bounds.
 */
@Service
public class JwtService {

	private static final String CLAIM_EMAIL = "email";
	private static final String CLAIM_NAME = "name";
	private static final String CLAIM_ROLE = "role";
	private static final String CLAIM_BRAND = "brandId";
	private static final String CLAIM_TEAM = "teamId";

	private final SecretKey key;
	private final Duration ttl;

	JwtService(@Value("${evalos.security.jwt.secret}") String secret,
			@Value("${evalos.security.jwt.ttl}") Duration ttl) {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		if (keyBytes.length < 32) {
			throw new IllegalStateException("evalos.security.jwt.secret must be at least 32 bytes for HS256");
		}
		this.key = Keys.hmacShaKeyFor(keyBytes);
		this.ttl = ttl;
	}

	public String issue(StaffPrincipal principal) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(principal.memberId().toString())
				.claim(CLAIM_EMAIL, principal.email())
				.claim(CLAIM_NAME, principal.displayName())
				.claim(CLAIM_ROLE, principal.role().name())
				.claim(CLAIM_BRAND, asString(principal.brandId()))
				.claim(CLAIM_TEAM, asString(principal.teamId()))
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(ttl)))
				.signWith(key)
				.compact();
	}

	/**
	 * Verifies the signature and expiry and rebuilds the principal.
	 *
	 * @throws JwtException if the token is tampered with, expired, or malformed
	 */
	public StaffPrincipal verify(String token) {
		Claims claims = Jwts.parser()
				.verifyWith(key)
				.build()
				.parseSignedClaims(token)
				.getPayload();

		return new StaffPrincipal(
				UUID.fromString(claims.getSubject()),
				claims.get(CLAIM_EMAIL, String.class),
				claims.get(CLAIM_NAME, String.class),
				Role.valueOf(claims.get(CLAIM_ROLE, String.class)),
				asUuid(claims.get(CLAIM_BRAND, String.class)),
				asUuid(claims.get(CLAIM_TEAM, String.class)),
				null,
				true);
	}

	private static String asString(UUID value) {
		return value == null ? null : value.toString();
	}

	private static UUID asUuid(String value) {
		return value == null ? null : UUID.fromString(value);
	}
}
