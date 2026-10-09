package com.ie.evalos.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Spec 78: the Admin account is default-deny. This pins the list — and, as importantly, the client-data
 * endpoints that have no {@code @PreAuthorize} and would otherwise be open to a {@code Tier.ALL} role.
 */
class AdminAllowlistTest {

	@Test
	void theAdminFunctionsAreOpenForEveryMethod() {
		for (String method : new String[] {"GET", "POST", "PUT", "DELETE"}) {
			assertThat(AdminAllowlist.allows(method, "/api/team-members")).isTrue();
			assertThat(AdminAllowlist.allows(method, "/api/team-members/abc/pipelines")).isTrue();
			assertThat(AdminAllowlist.allows(method, "/api/ghl/pipelines")).isTrue();
			assertThat(AdminAllowlist.allows(method, "/api/jobs/x/run")).isTrue();
			assertThat(AdminAllowlist.allows(method, "/api/sync/status")).isTrue();
		}
	}

	@Test
	void theReadOnlyBusinessViewsAreGetOnly() {
		for (String path : new String[] {"/api/metrics/gm", "/api/metrics/journey", "/api/cases/board",
				"/api/opportunities/board", "/api/brands", "/api/sales/users", "/api/me"}) {
			assertThat(AdminAllowlist.allows("GET", path)).as(path).isTrue();
			assertThat(AdminAllowlist.allows("POST", path)).as("POST " + path).isFalse();
			assertThat(AdminAllowlist.allows("PUT", path)).as("PUT " + path).isFalse();
			assertThat(AdminAllowlist.allows("DELETE", path)).as("DELETE " + path).isFalse();
		}
	}

	@Test
	void clientDataIsRefusedEvenThoughTheControllersCarryNoRoleAnnotation() {
		for (String path : new String[] {"/api/cases", "/api/cases/123", "/api/cases/123/documents",
				"/api/cases/123/timeline", "/api/cases/123/notes", "/api/cases/pm-notes", "/api/cases/123/checklist",
				"/api/chat/conversations", "/api/contacts", "/api/experts", "/api/payouts", "/api/payments"}) {
			assertThat(AdminAllowlist.allows("GET", path)).as(path).isFalse();
		}
	}

	@Test
	void businessWritesAndTheGmsOwnControlsAreRefused() {
		assertThat(AdminAllowlist.allows("PUT", "/api/metrics/gm/goal")).isFalse();
		assertThat(AdminAllowlist.allows("POST", "/api/opportunities/board/refresh")).isFalse();
		assertThat(AdminAllowlist.allows("POST", "/api/cases/123/refund/approve")).isFalse();
		assertThat(AdminAllowlist.allows("PUT", "/api/member-targets")).isFalse();
	}

	@Test
	void aPathThatOnlyStartsLikeAnAdminAreaIsNotThatArea() {
		assertThat(AdminAllowlist.allows("GET", "/api/jobsX")).isFalse();
		assertThat(AdminAllowlist.allows("GET", "/api/team-membersX")).isFalse();
		assertThat(AdminAllowlist.allows("GET", "/api/metrics/gmX")).isFalse();
		assertThat(AdminAllowlist.allows("GET", "/api/cases/board/extra")).isFalse();
	}

	@Test
	void aTrailingSlashDoesNotChangeTheAnswer() {
		assertThat(AdminAllowlist.allows("GET", "/api/cases/board/")).isTrue();
		assertThat(AdminAllowlist.allows("GET", "/api/cases/")).isFalse();
	}

	@Test
	void anAdminReadsItsOwnNotifications() {
		assertThat(AdminAllowlist.allows("GET", "/api/notifications/unread-count")).isTrue();
		assertThat(AdminAllowlist.allows("POST", "/api/notifications/read-all")).isTrue();
	}
}
