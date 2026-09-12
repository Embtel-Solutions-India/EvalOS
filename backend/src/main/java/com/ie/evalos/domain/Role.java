package com.ie.evalos.domain;

/**
 * The eight EvalOS staff roles. Each carries the ABAC scope tier that decides how
 * far a caller can read (see {@link Tier}); the tier is the single source of
 * truth for scoping, so no query re-derives it from the role.
 *
 * <p>The count has moved three times and the javadoc has lagged it twice, so it is worth stating
 * plainly: V3 shipped six, Unit 29 added {@code SALES_EXECUTIVE} for a seventh, {@code V30}
 * removed it again — and left this comment saying "seven" above a list of six — and Unit 36 adds
 * {@link #SALES} and {@link #MARKETING} for eight.
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
	EXPERT_NETWORK_MANAGER(Tier.SUPPLY),

	// Unit 36. Both brand-locked, both scoped by the one GHL pipeline they own
	// (`team_member.ghl_pipeline_id`), and neither gets a case transition: they act on
	// opportunities, which are GHL's, and the case does not exist until payment (invariant 8).
	//
	// The three business kinds of each — Attorney, Employer/Firm, Individual — are deliberately
	// NOT roles. They carry identical permissions, so they are `team_member.segment` and nothing
	// switches on them. See Segment.
	SALES(Tier.PIPELINE),
	MARKETING(Tier.PIPELINE);

	/**
	 * Whether this role reads the <em>content</em> of a case, as opposed to reaching the row.
	 *
	 * <p><strong>The two are different questions and conflating them is a leak.</strong> Row access
	 * is {@code ScopePredicate}'s job, and {@code Tier.SUPPLY} reads its whole brand — the Expert
	 * Network Manager has three case transitions that must load the case to act on it. They need
	 * the row; they must not receive the client on it.
	 *
	 * <p><strong>Lives here rather than on a controller</strong> so the field projection and the
	 * document routes cannot answer it differently. It was a package-private helper in
	 * {@code CaseController} until the presigned-URL route needed it from the service layer — and a
	 * service reaching into a controller for an authorisation rule is the direction that produces
	 * two copies of it.
	 */
	public boolean seesCaseContent() {
		return tier != Tier.SUPPLY;
	}

	/**
	 * Whether this role is scoped by the GHL pipeline it owns.
	 *
	 * <p>Exists so the two places that care — the assignment route's validation and the
	 * migration's CHECK, which must agree — ask one question instead of each listing the two
	 * role names. A third role joining {@code Tier.PIPELINE} then reaches both by adding one
	 * enum constant, which is the only way the enum and the constraint stay in step.
	 */
	public boolean isPipelineScoped() {
		return tier == Tier.PIPELINE;
	}

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
		SUPPLY,

		/**
		 * Own brand + the one GHL pipeline named on the caller's {@code team_member} row.
		 *
		 * <p><strong>A new tier rather than a reuse of {@link #SELF}, and the distinction is
		 * not pedantry.</strong> {@code SELF} means "rows that name me in an assignee column"
		 * and every one of those columns is on {@code evalos_case}. There is no assignee column
		 * on what these roles read. Reusing {@code SELF} would make {@code ScopePredicate}
		 * answer a question the schema never asked — the same failure mode
		 * {@code Fields.unteamedVisible} carries a paragraph about, where one flag came to mean
		 * two different things and the second meaning was wrong.
		 *
		 * <p><strong>Added beside the brand predicate, never instead of it.</strong> An EvalOS
		 * row stays brand-locked; the pipeline narrows further. The <em>key</em> is global
		 * (there is one GHL location, so one pipeline namespace) but the <em>row scope</em> is
		 * not, and conflating those is how a pipeline id shared across brands would become a
		 * cross-brand read.
		 */
		PIPELINE
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
