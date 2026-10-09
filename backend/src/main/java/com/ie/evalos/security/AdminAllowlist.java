package com.ie.evalos.security;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.ie.evalos.domain.Role;

/**
 * The whole of what the {@code ADMIN} account may call (spec 78) — **default-deny, in one place**.
 *
 * <p>The Admin is {@code Tier.ALL}, so tier scoping alone would hand it every client: case list, case
 * read, documents, notes, timeline, checklists and chat have no {@code @PreAuthorize} and rely on the
 * tier to narrow what they return. A role boundary that depends on every present and future endpoint
 * remembering to exclude one role is the failure this class exists to avoid. Here the rule is
 * inverted: an Admin request is refused unless it is on this list, whatever the controller says, so a
 * new endpoint is closed to the Admin until someone opens it deliberately.
 *
 * <p><strong>The one exception is the four admin areas, which are open by prefix</strong> ({@link #ADMIN_AREAS}).
 * A new endpoint under one of them is reachable by the Admin whatever its own annotation says, so every
 * handler there must carry its own {@code @PreAuthorize}; {@code AdminAreasPreAuthorizeTest} fails the build
 * if one does not.
 *
 * <p>Wired as a request matcher in {@link SecurityConfig}, which denies what {@link #refuses} matches.
 * Pure on its inputs so the list is pinned by {@code AdminAllowlistTest} without a Spring context.
 */
final class AdminAllowlist {

	/** The admin functions themselves: staff and their pipelines, GHL pipelines and purpose, jobs, sync. */
	static final Set<String> ADMIN_AREAS = Set.of("/api/team-members", "/api/ghl", "/api/jobs", "/api/sync");

	/** The Admin's own notifications; scoped to the recipient by the controller. */
	private static final String NOTIFICATIONS = "/api/notifications";

	/** Read-only business views: the whole-business dashboard, the journey, the two boards. */
	private static final Set<String> READ_ONLY = Set.of(
			"/api/me", "/api/brands", "/api/sales/users", "/api/metrics/gm", "/api/metrics/journey",
			"/api/cases/board", "/api/opportunities/board");

	private AdminAllowlist() {
	}

	/** True when this request is from an Admin and is not on the list — i.e. it must be denied. */
	static boolean refuses(HttpServletRequest request) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof StaffPrincipal principal)
				|| principal.role() != Role.ADMIN) {
			return false;
		}
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !allows(request.getMethod(), path);
	}

	static boolean allows(String method, String rawPath) {
		String path = rawPath.length() > 1 && rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
		if (ADMIN_AREAS.stream().anyMatch((area) -> within(path, area))) {
			return true;
		}
		if (within(path, NOTIFICATIONS)) {
			return true;
		}
		return "GET".equalsIgnoreCase(method) && READ_ONLY.contains(path);
	}

	/** {@code path} is the area itself or beneath it — on a segment boundary, so {@code /api/jobsX} is not. */
	private static boolean within(String path, String area) {
		return path.equals(area) || path.startsWith(area + "/");
	}
}
