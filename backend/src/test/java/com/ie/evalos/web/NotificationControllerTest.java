package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Notification;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.Role;
import com.ie.evalos.notification.NotificationService;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The four bell routes. Every staff role has a bell and none may read another's, so
 * there is deliberately no role gate to assert here — what matters is that the routes
 * are authenticated, that they carry no recipient parameter, and that the envelope is
 * the standard one.
 */
@WebMvcTest(controllers = NotificationController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class NotificationControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID ME = UUID.randomUUID();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	NotificationService notifications;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(ME, role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@Test
	void theListReturnsTheStandardEnvelope() throws Exception {
		given(notifications.mine(0, 20)).willReturn(List.of(
				new Notification(BRAND_IE, ME, NotificationType.NEW_CASE_IN_POOL, UUID.randomUUID(),
						"Case IE-2026-0001 is paid and needs a project manager.")));

		mockMvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, bearer(Role.BRAND_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].type").value("NEW_CASE_IN_POOL"))
				.andExpect(jsonPath("$.data[0].read").value(false))
				.andExpect(jsonPath("$.data[0].body").value(
						"Case IE-2026-0001 is paid and needs a project manager."))
				// No recipient is echoed: the answer is always the caller's own.
				.andExpect(jsonPath("$.data[0].recipientId").doesNotExist());
	}

	@Test
	void theUnreadCountIsTheBadgeValue() throws Exception {
		given(notifications.myUnreadCount()).willReturn(7L);

		mockMvc.perform(get("/api/notifications/unread-count")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.CASE_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").value(7));
	}

	@Test
	void markReadReturnsTheRepaintedBadge() throws Exception {
		given(notifications.myUnreadCount()).willReturn(2L);

		mockMvc.perform(post("/api/notifications/{id}/read", UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.marked").value(1))
				.andExpect(jsonPath("$.data.unread").value(2));
	}

	@Test
	void readAllReportsHowManyItCleared() throws Exception {
		given(notifications.markAllRead()).willReturn(5);
		given(notifications.myUnreadCount()).willReturn(0L);

		mockMvc.perform(post("/api/notifications/read-all")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.marked").value(5))
				.andExpect(jsonPath("$.data.unread").value(0));
	}

	/** Somebody else's notification surfaces as the standard 403, not a 500. */
	@Test
	void markingAnotherMembersIsForbidden() throws Exception {
		willThrow(new ForbiddenException("No such notification for this member"))
				.given(notifications).markRead(any());

		mockMvc.perform(post("/api/notifications/{id}/read", UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.CASE_MANAGER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void everyBellRouteNeedsAToken() throws Exception {
		mockMvc.perform(get("/api/notifications"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
		mockMvc.perform(get("/api/notifications/unread-count"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/notifications/read-all"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/notifications/{id}/read", UUID.randomUUID()))
				.andExpect(status().isUnauthorized());
	}
}
