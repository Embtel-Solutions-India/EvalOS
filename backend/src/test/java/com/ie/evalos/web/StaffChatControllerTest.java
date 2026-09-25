package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.chat.ChatApi;
import com.ie.evalos.chat.ChatIdentity;
import com.ie.evalos.chat.ChatViews;
import com.ie.evalos.chat.ConversationReadOnlyException;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Case chat for staff: identity from the session, access decided per conversation (Unit 57 §4). */
@WebMvcTest(controllers = StaffChatController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = "evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256")
class StaffChatControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	ChatApi api;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Desk", role,
				role == Role.GM ? null : BRAND_IE, null, (String) null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}

	@Test
	void anUnauthenticatedCallerIs401() throws Exception {
		mockMvc.perform(get("/api/chat/conversations")).andExpect(status().isUnauthorized());
	}

	@Test
	void theInboxIsTheServicesAnswerForTheCallersIdentity() throws Exception {
		given(api.inbox(any(), any(), any(), any(), any(), eq(50))).willReturn(new ChatViews.Page<>(List.of(), null));

		mockMvc.perform(get("/api/chat/conversations").header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk());

		then(api).should().inbox(argThat((ChatIdentity who) -> who.kind() == ParticipantKind.STAFF
				&& who.staffRole() == Role.PROJECT_MANAGER), isNull(), isNull(), isNull(), isNull(), eq(50));
	}

	@Test
	void sendingPassesBodyAndParent() throws Exception {
		UUID conversation = UUID.randomUUID();

		mockMvc.perform(post("/api/chat/conversations/" + conversation + "/messages")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\",\"parentId\":null}"))
				.andExpect(status().isOk());

		then(api).should().send(any(ChatIdentity.class), eq(conversation), argThat((ChatApi.SendRequest r) ->
				r.body().equals("hi") && r.parentId() == null));
	}

	@Test
	void aReadOnlyConversationIs409() throws Exception {
		given(api.send(any(), any(), any())).willThrow(new ConversationReadOnlyException());

		mockMvc.perform(post("/api/chat/conversations/" + UUID.randomUUID() + "/messages")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("CONVERSATION_READ_ONLY"));
	}

	@Test
	void aBlankBodyIs400() throws Exception {
		mockMvc.perform(post("/api/chat/conversations/" + UUID.randomUUID() + "/messages")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"  \"}"))
				.andExpect(status().isBadRequest());

		then(api).should(never()).send(any(), any(), any());
	}

	@Test
	void theRealtimeTokenIsTheServicesAnswer() throws Exception {
		given(api.realtimeToken(any())).willReturn(new com.ie.evalos.chat.live.AblyToken("app.key", "STAFF:x",
				"{}", 3600000, 1L, "nonce", "mac"));

		mockMvc.perform(get("/api/chat/realtime/token").header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.clientId").value("STAFF:x"));
	}

	@Test
	void realtimeOffIs503() throws Exception {
		given(api.realtimeToken(any())).willThrow(new com.ie.evalos.chat.live.RealtimeUnavailableException());

		mockMvc.perform(get("/api/chat/realtime/token").header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("REALTIME_UNAVAILABLE"));
	}

	@Test
	void anUnknownReactionIs400() throws Exception {
		mockMvc.perform(put("/api/chat/messages/" + UUID.randomUUID() + "/reactions/FIRE")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isBadRequest());
	}
}
