package com.ie.evalos.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated staff identity. Built from the database at login and
 * rebuilt from the JWT claims on every later request, so {@code password} is
 * null once a request arrives bearing a token.
 */
public record StaffPrincipal(
		UUID memberId,
		String email,
		String displayName,
		Role role,
		UUID brandId,
		UUID teamId,
		/**
		 * The GHL pipeline this member owns, or null for every role that is not pipeline-scoped.
		 *
		 * <p>Carried on the principal rather than looked up, for the reason {@code teamId} is: a
		 * scope predicate that needs a database read to build is a read on every scoped query.
		 * The cost is that a pipeline reassignment takes effect on the member's next login —
		 * the same property {@code brandId} and {@code teamId} already have, stated here so
		 * nobody meets it as a bug.
		 */
		List<String> ghlPipelineIds,
		String passwordHash,
		boolean active) implements UserDetails {

	/**
	 * A principal for a role that owns no GHL pipeline. Same reasoning as
	 * {@link TenantContext#TenantContext(UUID, Role, UUID, UUID)}: {@code null} is what the
	 * column actually holds for these roles, and it fails closed for the ones it does not.
	 */
	public StaffPrincipal {
		// Never null, so no caller has to guard it and `ScopePredicate`'s empty case means exactly
		// one thing: this member is on no pipeline.
		ghlPipelineIds = ghlPipelineIds == null ? List.of() : List.copyOf(ghlPipelineIds);
	}

	/** A principal with no pipelines — every role that is not SALES or MARKETING. */
	public StaffPrincipal(UUID memberId, String email, String displayName, Role role, UUID brandId,
			UUID teamId, String passwordHash, boolean active) {
		this(memberId, email, displayName, role, brandId, teamId, List.of(), passwordHash, active);
	}

	/**
	 * One pipeline, as a set of one.
	 *
	 * <p><strong>Kept so that Unit 44b's change of shape did not become a change to fifty call
	 * sites.</strong> A desk with exactly one pipeline is still the normal case and will be for as
	 * long as there is one salesperson per funnel; what changed is that it is no longer the only
	 * case, because Case Delivery has no single owner ({@code 00d} §6.7).
	 */
	public StaffPrincipal(UUID memberId, String email, String displayName, Role role, UUID brandId,
			UUID teamId, String ghlPipelineId, String passwordHash, boolean active) {
		this(memberId, email, displayName, role, brandId, teamId,
				ghlPipelineId == null ? List.<String>of() : List.of(ghlPipelineId), passwordHash, active);
	}

	/**
	 * A principal for one member, with the pipelines they may work.
	 *
	 * <p><strong>The pipelines are passed in rather than read off the member</strong>, because as of
	 * Unit 44b they live in {@code team_member_pipeline} and not on the row.
	 * {@code team_member.ghl_pipeline_id} is vestigial — see {@code V54} — and reading it here would
	 * be the one place the old single-owner model survived.
	 */
	public static StaffPrincipal of(TeamMember member, List<String> ghlPipelineIds) {
		return new StaffPrincipal(
				member.getId(),
				member.getEmail(),
				member.getDisplayName(),
				member.getRole(),
				member.getBrandId(),
				member.getTeamId(),
				ghlPipelineIds,
				member.getPasswordHash(),
				member.isActive());
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority(role.authority()));
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return email;
	}

	@Override
	public boolean isEnabled() {
		return active;
	}
}
