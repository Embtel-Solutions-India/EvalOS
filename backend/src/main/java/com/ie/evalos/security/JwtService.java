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
 * whole tenant identity (role, brand, team, GHL pipeline) so no request needs a
 * database hit to be scoped — the trade-off is that a role, brand or pipeline
 * change only takes effect on the next login, which the short TTL bounds.
 */
@Service
public class JwtService {

	private static final String CLAIM_EMAIL = "email";
	private static final String CLAIM_NAME = "name";
	private static final String CLAIM_ROLE = "role";
	private static final String CLAIM_BRAND = "brandId";
	private static final String CLAIM_TEAM = "teamId";
	/**
	 * The pipelines this member may work.
	 *
	 * <p><strong>Renamed and re-shaped at Unit 44b</strong>, from a single {@code ghlPipelineId}
	 * string. A token minted before that carries the old claim, which this does not read — so an
	 * old token resolves to <em>no</em> pipelines and, per {@code ScopePredicate}'s PIPELINE arm,
	 * matches nothing. One re-login fixes it, and an empty board is the safe direction for a scope
	 * to be wrong in.
	 */
	private static final String CLAIM_PIPELINES = "ghlPipelineIds";

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
				.claim(CLAIM_PIPELINES, principal.ghlPipelineIds())
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
				// Absent from a token minted before Unit 44b, which reads as an empty list and,
				// per ScopePredicate's PIPELINE arm, matches nothing. One re-login fixes it, and
				// that is the safe direction for a scope to be wrong in.
				pipelinesIn(claims),
				null,
				true);
	}

	/**
	 * The pipeline claim, defensively.
	 *
	 * <p>A JWT is signed, so the list cannot have been tampered with — but it can be <em>absent</em>
	 * (a token from before Unit 44b) or hold something unexpected if the claim is ever re-shaped
	 * again. Anything that is not a list of strings resolves to none rather than throwing: a
	 * malformed scope must fail closed, not fail the request in a way that looks like an outage.
	 */
	private static java.util.List<String> pipelinesIn(Claims claims) {
		Object raw = claims.get(CLAIM_PIPELINES);
		if (!(raw instanceof java.util.List<?> values)) {
			return java.util.List.of();
		}
		return values.stream()
				.filter((value) -> value instanceof String)
				.map(String.class::cast)
				.toList();
	}

	private static String asString(UUID value) {
		return value == null ? null : value.toString();
	}

	private static UUID asUuid(String value) {
		return value == null ? null : UUID.fromString(value);
	}
}
