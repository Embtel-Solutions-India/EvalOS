package com.ie.evalos.security;

import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.PortalAccess;
import com.ie.evalos.domain.PortalAudience;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who is asking on a portal route, and the one case they may see. Put in the security context by
 * {@link PortalTokenFilter}, read only by the portal services.
 *
 * <p><strong>Why this is not a {@link TenantContext}.</strong> A tenant context is
 * {@code (memberId, Role, brandId, teamId)} built from a {@link StaffPrincipal}, and every scoped
 * query in EvalOS builds its predicate from it. A client has none of those four things, and
 * manufacturing a synthetic tenant context for them would put a non-staff caller into the staff
 * scoping path — where a later widening of a role tier would silently widen what a client can
 * read. {@code TenantContext.find()} matches on {@code StaffPrincipal} and therefore returns empty
 * for a portal request, which is the property that keeps the two surfaces apart.
 *
 * <p><strong>The token is the scope.</strong> {@link #caseId} came off the token's own row, so
 * there is no predicate to build and nothing to fail open: no portal route accepts a case id, so
 * there is nothing to enumerate. {@code ScopePredicate} is not involved.
 *
 * <p><strong>{@link #expertId} is the second half of that scope on the expert surface</strong>
 * (V37), and null for a client. A case-scoped token said which case but not which person, so a
 * token that outlived a rematch admitted the previous expert to a case that had moved on —
 * {@code ExpertPortalService} compares it against the case's own expert and refuses a mismatch.
 */
public record PortalPrincipal(UUID portalAccessId, UUID brandId, UUID caseId, PortalAudience audience,
		UUID expertId, String ghlContactId, UUID clientAccountId) {

	/**
	 * A <strong>case-scoped</strong> principal — the original five-field shape, with no party.
	 *
	 * <p>Kept as a constructor rather than pushed onto every caller as a trailing {@code null},
	 * because "case-scoped" is the thing being said and {@code null} is not a good way to say it.
	 * A party principal only ever comes from {@link #of}, off a row the database has already
	 * constrained, so there is no path that builds one of those by hand and forgets the party.
	 */
	public PortalPrincipal(UUID portalAccessId, UUID brandId, UUID caseId, PortalAudience audience,
			UUID expertId) {
		this(portalAccessId, brandId, caseId, audience, expertId, null, null);
	}

	/**
	 * A party-scoped principal without an account, which is every shape that existed before
	 * Unit 43 needed one.
	 */
	public PortalPrincipal(UUID portalAccessId, UUID brandId, UUID caseId, PortalAudience audience,
			UUID expertId, String ghlContactId) {
		this(portalAccessId, brandId, caseId, audience, expertId, ghlContactId, null);
	}

	public static PortalPrincipal of(PortalAccess access) {
		return new PortalPrincipal(access.getId(), access.getBrandId(), access.getCaseId(), access.getAudience(),
				access.getExpertId(), access.getGhlContactId(), access.getClientAccountId());
	}

	/**
	 * Whether this credential names a person rather than a case (Unit 35, D1).
	 *
	 * <p>{@code caseId} is null exactly when it does, which is what every service branches on:
	 * a case-scoped principal reads the one case it names, a party-scoped one takes the case id
	 * from the path and checks it against the party before reading anything.
	 */
	public boolean isPartyScoped() {
		return caseId == null;
	}

	/**
	 * <strong>{@code clientAccountId} is populated for one of a client's two legal token shapes,
	 * never both</strong> — which is V44's constraint, not an accident here.
	 *
	 * <p>{@code mintForClientAccount} writes a {@code ghl_contact_id} row when the account has a
	 * contact and a {@code client_account_id} row when it does not, because the first is what
	 * resolves the client's *cases* and the second is all there is when there are none. So a
	 * caller needing the account — Unit 43's funnel — reads this when it is set and looks the
	 * account up by contact when it is not. Two arms, both real, and neither is a fallback for a
	 * bug.
	 */
	public boolean namesAnAccountDirectly() {
		return clientAccountId != null;
	}

	/**
	 * The portal caller for the current request, refused unless their token was minted for this
	 * audience.
	 *
	 * <p>The audience check lives here rather than in a {@code @PreAuthorize} so there is exactly
	 * one place it happens, and so Unit 15's expert routes inherit it by asking for
	 * {@code EXPERT} — a client token on an expert route, or the reverse, is refused by the same
	 * line.
	 */
	public static PortalPrincipal current(PortalAudience expected) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof PortalPrincipal principal)) {
			throw new IllegalStateException("No portal principal in context");
		}
		if (principal.audience() != expected) {
			throw new ForbiddenException("This link does not admit you to that");
		}
		return principal;
	}
}
