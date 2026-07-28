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
