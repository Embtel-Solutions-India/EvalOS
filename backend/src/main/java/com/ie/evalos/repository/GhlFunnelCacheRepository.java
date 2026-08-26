package com.ie.evalos.repository;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.GhlFunnelCache;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The marketing funnel's cache rows.
 *
 * <p><strong>Not a {@code ScopedRepository}, and this is the one place that needs saying twice.</strong>
 * Every other repository here scopes by {@code brand_id} because its rows belong to a brand. These
 * rows do not: they hold figures read from the single GHL sub-account named by a global setting,
 * which EvalOS cannot attribute to any brand. A scoped finder would need a {@code brandId} nobody
 * could correctly supply, and adding one would imply a narrowing that is not happening. The
 * endpoint that reads this is GM-only for the same reason.
 *
 * <p>The only lookup is by the window, matching the table's unique key exactly. There is no
 * "find all" use: the screen asks for one funnel and one period, and a listing of cache rows
 * would be a view onto storage rather than onto the business.
 */
public interface GhlFunnelCacheRepository extends JpaRepository<GhlFunnelCache, UUID> {

	/**
	 * One window's row, or empty on a cold cache.
	 *
	 * <p>Both halves of the key, always. The two funnels' payloads are identical in shape, so
	 * looking up by period alone would return the ads figures for an email request and the screen
	 * would show them under the wrong heading with nothing to contradict it.
	 */
	Optional<GhlFunnelCache> findByFunnelAndRangeName(String funnel, String rangeName);
}
