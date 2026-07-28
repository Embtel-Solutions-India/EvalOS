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
	// Self, per the design. Note the open gap this leaves: `evalos_case`'s assignee
	// axis is `assigned_cm`, which only ever holds a Case Manager, so a Coordinator
	// currently matches no case and their declared stage actions (docs-complete,
	// send-to-client, deliver, close) answer 403 at runtime. Closing it needs an
	// `assigned_coordinator` column and a migration — see the open question in
	// context/progress-tracker.md. Do not close it by widening the predicate: a
	// scope that matches when it should not is the failure mode this design avoids.
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
		/** Own brand's expert/roster supply side — not case content. */
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
