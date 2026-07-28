package com.ie.evalos.security;

import com.ie.evalos.repository.TeamMemberRepository;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads staff credentials for the login exchange. Active members only. */
@Service
public class EvalOsUserDetailsService implements UserDetailsService {

	private final TeamMemberRepository teamMembers;

	EvalOsUserDetailsService(TeamMemberRepository teamMembers) {
		this.teamMembers = teamMembers;
	}

	@Override
	@Transactional(readOnly = true)
	public StaffPrincipal loadUserByUsername(String email) throws UsernameNotFoundException {
		return teamMembers.findByEmailIgnoreCaseAndActiveTrue(email)
				.map(StaffPrincipal::of)
				// Deliberately vague: never reveal whether the address exists.
				.orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));
	}
}
