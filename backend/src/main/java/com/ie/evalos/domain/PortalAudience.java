package com.ie.evalos.domain;

/**
 * Who a {@link PortalAccess} token admits. One table, two portals: the client reviews the draft
 * (Unit 14) and the expert answers the offer and signs (Unit 15).
 *
 * <p>Deliberately not a {@link Role}. A role carries an ABAC {@code Tier} and a brand/team/self
 * scope built from a {@code StaffPrincipal}; a portal caller has none of those, and their scope
 * is the token — which names exactly one case. Reusing {@code Role} would put a non-staff caller
 * into the staff scoping path, where a later widening of a role tier would silently widen what a
 * client can read.
 */
public enum PortalAudience {

	CLIENT,
	EXPERT;

	/** How the audit trail names this actor. Explicit rather than {@code valueOf(name())}. */
	public ActorType actorType() {
		return this == CLIENT ? ActorType.CLIENT : ActorType.EXPERT;
	}
}
