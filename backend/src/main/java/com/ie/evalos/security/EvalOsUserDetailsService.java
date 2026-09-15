package com.ie.evalos.security;

import com.ie.evalos.repository.TeamMemberPipelineRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads staff credentials for the login exchange. Active members only. */
@Service
public class EvalOsUserDetailsService implements UserDetailsService {

	private final TeamMemberRepository teamMembers;

	/**
	 * The member's pipelines, read at sign-in (Unit 44b).
	 *
	 * <p><strong>The staleness this leaves is unchanged in kind, not new.</strong> The set goes into
	 * the token, so a GM adding or removing a pipeline takes effect on the member's next sign-in —
	 * exactly as the single {@code ghlPipelineId} claim behaved before, and for the same reason
	 * {@code JwtService} already documents. What is new is that reassignment becomes routine once a
	 * desk can hold several, so the bound matters more: shorten {@code evalos.security.jwt.ttl} if it
	 * bites, and resolve per request at Unit 46 when the desks move onto the mirror.
	 */
	private final TeamMemberPipelineRepository pipelines;

	EvalOsUserDetailsService(TeamMemberRepository teamMembers, TeamMemberPipelineRepository pipelines) {
		this.teamMembers = teamMembers;
		this.pipelines = pipelines;
	}

	@Override
	@Transactional(readOnly = true)
	public StaffPrincipal loadUserByUsername(String email) throws UsernameNotFoundException {
		return teamMembers.findByEmailIgnoreCaseAndActiveTrue(email)
				.map((member) -> StaffPrincipal.of(member, pipelines.ghlIdsFor(member.getId())))
				// Deliberately vague: never reveal whether the address exists.
				.orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));
	}
}
