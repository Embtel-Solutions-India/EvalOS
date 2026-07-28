package com.ie.evalos.service;

import java.util.ArrayList;
import java.util.List;

import com.ie.evalos.security.TenantContext;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.jpa.domain.Specification;

/**
 * The one place brand/team/assignee predicates are built. Every scoped read
 * composes its Specification from here — a repository query that skips it can
 * cross brands, which is a defect, not a feature.
 *
 * <p>Fail closed: a non-GM caller with no brand on their principal matches
 * nothing rather than everything.
 */
public final class ScopePredicate {

	/**
	 * Which attributes on the target entity carry the scope. Any of them may be
	 * null when the entity has no such axis (e.g. no team column).
	 *
	 * @param brand    attribute holding the owning brand id
	 * @param team     attribute holding the owning team id, or null
	 * @param assignee attribute holding the assigned member id, or null
	 */
	public record Fields(String brand, String team, String assignee) {

		public static Fields brandOnly(String brand) {
			return new Fields(brand, null, null);
		}
	}

	private ScopePredicate() {
	}

	public static <T> Specification<T> of(TenantContext ctx, Fields fields) {
		return (root, query, cb) -> {
			if (ctx.isCrossBrand()) {
				return cb.conjunction();
			}
			if (ctx.brandId() == null) {
				// Brand-locked role without a brand: match nothing.
				return cb.disjunction();
			}

			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.equal(root.get(fields.brand()), ctx.brandId()));

			switch (ctx.role().tier()) {
				case TEAM -> {
					if (fields.team() != null && ctx.teamId() != null) {
						predicates.add(cb.equal(root.get(fields.team()), ctx.teamId()));
					}
				}
				case SELF -> {
					if (fields.assignee() != null) {
						predicates.add(cb.equal(root.get(fields.assignee()), ctx.memberId()));
					}
				}
				// BRAND and SUPPLY read their whole brand; ALL returned above.
				default -> {
				}
			}

			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}
}
