package com.ie.evalos.web;

import com.ie.evalos.chat.ChatApi;
import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.PortalSecurityConfig;
import com.ie.evalos.service.PortalAccessService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The portal chat routes sit behind the portal token like every other /api/portal route (Unit 57 §4). */
@WebMvcTest(controllers = { ClientChatController.class, ExpertChatController.class })
@Import({ PortalSecurityConfig.class, ApiErrors.class, JwtService.class })
@TestPropertySource(properties = {
		"evalos.portal.rate-limit-per-minute=200",
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.portal.allowed-origins=https://portal.test",
})
class PortalChatControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ChatApi api;

	// Wired into the chain as PortalTokenFilter's collaborator.
	@MockitoBean
	PortalAccessService portalAccess;

	@Test
	void everyPortalChatRouteRefusesAnUnauthenticatedCaller() throws Exception {
		mockMvc.perform(get("/api/portal/client/chat/conversations")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/portal/expert/chat/conversations")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/portal/client/chat/unread")).andExpect(status().isUnauthorized());
	}
}
