package com.ie.evalos.security;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Role;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who is asking, and how far they may read. Derived from the authenticated
 * principal that {@link JwtFilter} put in the security context — never from a
 * request body, query parameter, or header.
 *
 * <p>The security context is already request-scoped and cleared by Spring
 * Security at the end of each request, so this is read straight off it rather
 * than duplicated into a second request-scoped bean.
 */
public record TenantContext(UUID memberId, Role role, UUID brandId, UUID teamId, String ghlPipelineId) {

	/**
	 * A caller who owns no GHL pipeline — every role but {@code SALES} and {@code MARKETING}.
	 *
	 * <p>Not a defaulting convenience: {@code null} is the <em>correct</em> value for a role
	 * outside {@code Tier.PIPELINE}, and it is also the fail-closed one. A pipeline-scoped
	 * caller built through here matches nothing rather than everything, which is asserted in
	 * {@code ScopePredicateTest} rather than left to be discovered.
	 */
	public TenantContext(UUID memberId, Role role, UUID brandId, UUID teamId) {
		this(memberId, role, brandId, teamId, null);
	}

	public static TenantContext of(StaffPrincipal principal) {
		return new TenantContext(principal.memberId(), principal.role(), principal.brandId(),
				principal.teamId(), principal.ghlPipelineId());
	}

	/** The caller for the current request, or empty when unauthenticated. */
	public static Optional<TenantContext> find() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof StaffPrincipal principal)) {
			return Optional.empty();
		}
		return Optional.of(of(principal));
	}

	/** The caller for the current request. Only call where auth is guaranteed. */
	public static TenantContext current() {
		return find().orElseThrow(() -> new IllegalStateException("No authenticated staff principal in context"));
	}

	/** True when the caller reads across every brand (GM only). */
	public boolean isCrossBrand() {
		return role.tier() == Role.Tier.ALL;
	}
}
