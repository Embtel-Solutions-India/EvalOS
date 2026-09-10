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
		String ghlPipelineId,
		String passwordHash,
		boolean active) implements UserDetails {

	/**
	 * A principal for a role that owns no GHL pipeline. Same reasoning as
	 * {@link TenantContext#TenantContext(UUID, Role, UUID, UUID)}: {@code null} is what the
	 * column actually holds for these roles, and it fails closed for the ones it does not.
	 */
	public StaffPrincipal(UUID memberId, String email, String displayName, Role role, UUID brandId,
			UUID teamId, String passwordHash, boolean active) {
		this(memberId, email, displayName, role, brandId, teamId, null, passwordHash, active);
	}

	public static StaffPrincipal of(TeamMember member) {
		return new StaffPrincipal(
				member.getId(),
				member.getEmail(),
				member.getDisplayName(),
				member.getRole(),
				member.getBrandId(),
				member.getTeamId(),
				member.getGhlPipelineId(),
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
