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
		String passwordHash,
		boolean active) implements UserDetails {

	public static StaffPrincipal of(TeamMember member) {
		return new StaffPrincipal(
				member.getId(),
				member.getEmail(),
				member.getDisplayName(),
				member.getRole(),
				member.getBrandId(),
				member.getTeamId(),
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
