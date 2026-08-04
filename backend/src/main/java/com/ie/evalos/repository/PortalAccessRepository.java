package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.PortalAccess;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.service.ScopePredicate;

/**
 * Portal tokens, brand-scoped like everything else — but note which of these two finders the
 * portal itself uses, and why neither is {@code findScoped}.
 *
 * <p>{@link #findByTokenHash} is <strong>the portal's read, and it is deliberately unscoped</strong>:
 * a client has no {@code TenantContext} to scope by, and the row that comes back carries the brand
 * and the one case it admits. That is what makes the token the scope rather than a key into a
 * scoped query. {@code ScopePredicate} is not involved on this path and is not modified.
 *
 * <p>Brand-only for the inherited {@code findScoped}, which is the staff side's read — there is no
 * assignee axis, because the person this row admits is its subject and not a principal who can
 * read it. The case's own assignee scoping was already applied by {@code CaseRepository} on the
 * read that found the case before a token was minted for it.
 */
public interface PortalAccessRepository extends ScopedRepository<PortalAccess> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/**
	 * The one lookup a portal request makes. Unique in the database (V21), so this is an
	 * {@code Optional} rather than a list.
	 *
	 * <p>Takes the <em>hash</em>, never the token: the caller hashes first, so the raw token
	 * never becomes a query parameter that could reach a log or a slow-query report.
	 */
	Optional<PortalAccess> findByTokenHash(String tokenHash);

	/**
	 * Whatever tokens exist for this case and audience, newest first — the mint's own read, so it
	 * can revoke the one it is superseding, and the staff panel's, so it can say whether a live
	 * link exists and when it expires.
	 *
	 * <p>No brand predicate, by the same convention as the other batched finders: the case id
	 * passed in has already come out of a scoped read. Do not call it with an id that arrived
	 * from a request.
	 */
	List<PortalAccess> findByCaseIdAndAudienceOrderByCreatedAtDesc(UUID caseId, PortalAudience audience);
}
