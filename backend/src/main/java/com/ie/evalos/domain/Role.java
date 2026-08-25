package com.ie.evalos.domain;

/**
 * The six EvalOS staff roles. Each carries the ABAC scope tier that decides how
 * far a caller can read (see {@link Tier}); the tier is the single source of
 * truth for scoping, so no query re-derives it from the role.
 */
public enum Role {

	GM(Tier.ALL),
	BRAND_MANAGER(Tier.BRAND),
	PROJECT_MANAGER(Tier.TEAM),
	// Self, per the design, and now actually reachable: V17 added
	// `evalos_case.assigned_coordinator` and `CaseRepository.SCOPE` declares it
	// alongside `assigned_cm`, so a SELF caller matches a case that names them in
	// *either* slot. Before that the only assignee column held a Case Manager, so a
	// Coordinator matched no case at all and their four declared transitions
	// (docs-complete, send-to-client, deliver, close) answered 403 on their own work.
	// Closed by giving the axis the missing column, not by widening the predicate —
	// a scope that matches when it should not is the failure mode this design avoids.
	PROJECT_COORDINATOR(Tier.SELF),
	CASE_MANAGER(Tier.SELF),
	EXPERT_NETWORK_MANAGER(Tier.SUPPLY);

	/** How wide a role reads. Anything but {@code ALL} is brand-locked. */
	public enum Tier {
		/** Every brand. GM only. */
		ALL,
		/** Own brand. */
		BRAND,
		/** Own brand + own team. */
		TEAM,
		/** Own brand + rows assigned to the caller. */
		SELF,
		/**
		 * Own brand, exactly like {@link #BRAND} — this tier adds no predicate in
		 * {@code ScopePredicate} and the row scope of the two is identical.
		 *
		 * <p>What makes it supply-side is <strong>field</strong> projection, not row scope:
		 * {@code CaseController.seesCaseContent} withholds {@code clientName},
		 * {@code driveLink} and {@code draftLink} from this tier on every case payload, the
		 * board's and the detail's alike. The row stays readable because the Expert Network
		 * Manager has three case transitions — expert signed, declined, reassign — that must
		 * load the case to act on it.
		 *
		 * <p>This javadoc used to read "own brand's expert/roster supply side — not case
		 * content" while the tier was referenced nowhere in the codebase and excluded nothing,
		 * so every case payload carried the client straight through it.
		 */
		SUPPLY
	}

	private final Tier tier;

	Role(Tier tier) {
		this.tier = tier;
	}

	public Tier tier() {
		return tier;
	}

	/** The Spring Security authority name for this role. */
	public String authority() {
		return "ROLE_" + name();
	}
}
