package com.ie.evalos.security;

import java.io.IOException;

import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a {@code Bearer} token into the authenticated {@link StaffPrincipal},
 * which is what {@link TenantContext} reads for brand/team/self scoping.
 *
 * <p>A missing or unparseable token leaves the context empty and lets the chain
 * continue — the authorization rules, not this filter, decide whether anonymous
 * is acceptable for the path.
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	/**
	 * The GM opens one production member's own dashboard (2026-10-30): {@code ?viewAs=<memberId>} on a GET of
	 * exactly these reads runs the request <em>as that member</em>, so their role gate and their scope apply and
	 * the figures are the ones they see. GM only, GET only, these paths only, and only a PM, Coordinator, Case
	 * Manager or ENM who is active — anything else is a 403, never a silent fall back to the GM's own view.
	 */
	static final String VIEW_AS = "viewAs";
	static final java.util.Set<String> VIEW_AS_PATHS = java.util.Set.of(
			"/api/metrics/pm", "/api/metrics/pm/overview",
			"/api/metrics/coordinator", "/api/metrics/coordinator/work",
			"/api/metrics/case-manager", "/api/metrics/case-manager/work",
			"/api/metrics/expert-network", "/api/metrics/expert-network/work",
			"/api/opportunities/board", "/api/payouts/summary");
	static final java.util.Set<com.ie.evalos.domain.Role> VIEWABLE = java.util.EnumSet.of(
			com.ie.evalos.domain.Role.PROJECT_MANAGER, com.ie.evalos.domain.Role.PROJECT_COORDINATOR,
			com.ie.evalos.domain.Role.CASE_MANAGER, com.ie.evalos.domain.Role.EXPERT_NETWORK_MANAGER);

	private final JwtService jwtService;
	/**
	 * Unit 68: a deactivated member is refused on their next request, not when their 8 h token
	 * expires. A provider because web-slice tests build no JPA layer; there, the check is skipped.
	 */
	private final org.springframework.beans.factory.ObjectProvider<com.ie.evalos.repository.TeamMemberRepository> members;
	private final org.springframework.beans.factory.ObjectProvider<com.ie.evalos.repository.TeamMemberPipelineRepository> pipelines;

	JwtFilter(JwtService jwtService,
			org.springframework.beans.factory.ObjectProvider<com.ie.evalos.repository.TeamMemberRepository> members,
			org.springframework.beans.factory.ObjectProvider<com.ie.evalos.repository.TeamMemberPipelineRepository> pipelines) {
		this.jwtService = jwtService;
		this.members = members;
		this.pipelines = pipelines;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {

		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER)
				&& SecurityContextHolder.getContext().getAuthentication() == null) {
			try {
				StaffPrincipal principal = jwtService.verify(header.substring(BEARER.length()));
				var repository = members.getIfAvailable();
				if (repository != null && !repository.existsByIdAndActiveTrue(principal.memberId())) {
					// Deactivated (or removed) since the token was signed: stay anonymous, 401.
					chain.doFilter(request, response);
					return;
				}
				// The pipelines as granted now, not as at sign-in (D19b, edited 2026-10-14): a desk given a second
				// pipeline saw no tabs on its board until it signed in again, and a revoked one stayed readable.
				var assignments = pipelines.getIfAvailable();
				if (assignments != null) {
					principal = new StaffPrincipal(principal.memberId(), principal.email(), principal.displayName(),
							principal.role(), principal.brandId(), principal.teamId(),
							assignments.ghlIdsFor(principal.memberId()), principal.passwordHash(), principal.active());
				}
				String viewAs = request.getParameter(VIEW_AS);
				if (viewAs != null) {
					principal = viewedAs(request, principal, viewAs, repository, assignments);
					if (principal == null) {
						SecurityContextHolder.clearContext();
						response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not a dashboard the GM may view as that member");
						return;
					}
				}
				var authentication = new UsernamePasswordAuthenticationToken(
						principal, null, principal.getAuthorities());
				authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
			catch (JwtException | IllegalArgumentException ex) {
				// Tampered, expired, or malformed: stay anonymous and let the
				// entry point answer 401. Never log the token itself.
				SecurityContextHolder.clearContext();
				logger.debug("Rejected bearer token: " + ex.getMessage());
			}
		}

		chain.doFilter(request, response);
	}

	/** The member the GM views as, or null when the request may not do that. */
	static StaffPrincipal viewedAs(HttpServletRequest request, StaffPrincipal caller, String viewAs,
			com.ie.evalos.repository.TeamMemberRepository members,
			com.ie.evalos.repository.TeamMemberPipelineRepository assignments) {
		if (caller.role() != com.ie.evalos.domain.Role.GM || !"GET".equals(request.getMethod())
				|| !VIEW_AS_PATHS.contains(request.getRequestURI()) || members == null || assignments == null) {
			return null;
		}
		java.util.UUID id;
		try {
			id = java.util.UUID.fromString(viewAs);
		}
		catch (IllegalArgumentException notAnId) {
			return null;
		}
		return members.findById(id)
				.filter(com.ie.evalos.domain.TeamMember::isActive)
				.filter((member) -> VIEWABLE.contains(member.getRole()))
				.map((member) -> StaffPrincipal.of(member, assignments.ghlIdsFor(member.getId())))
				.orElse(null);
	}
}
