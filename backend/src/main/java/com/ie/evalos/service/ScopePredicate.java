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
	 * Which attributes on the target entity carry the scope. Team may be null and
	 * assignees may be empty when the entity has no such axis.
	 *
	 * @param brand     attribute holding the owning brand id
	 * @param team      attribute holding the owning team id, or null
	 * @param assignees every attribute that can name an assigned member, possibly
	 *                  empty. A {@code SELF} caller matches when <em>any</em> of them
	 *                  is them — a case is one pipeline and the people on it hold
	 *                  different slots, so a single column would only ever show the
	 *                  work to one of them.
	 */
	public record Fields(String brand, String team, List<String> assignees) {

		public Fields {
			assignees = List.copyOf(assignees);
		}

		public static Fields brandOnly(String brand) {
			return new Fields(brand, null, List.of());
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
					// Any slot naming the caller is enough: the Coordinator who chases the
					// documents and the Case Manager who writes the draft are on the same
					// case, in different columns. An entity with no assignment column at all
					// (an expert, a payout) is deliberately left brand-wide — narrowing it
					// would need a column that does not exist, and inventing one here is how
					// a scope starts disagreeing with the schema.
					Predicate[] mine = fields.assignees().stream()
							.map(attribute -> cb.equal(root.get(attribute), ctx.memberId()))
							.toArray(Predicate[]::new);
					if (mine.length > 0) {
						predicates.add(cb.or(mine));
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
