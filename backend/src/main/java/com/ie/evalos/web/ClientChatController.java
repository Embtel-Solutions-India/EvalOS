package com.ie.evalos.web;

import com.ie.evalos.chat.ChatApi;
import com.ie.evalos.chat.ChatIdentity;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Case chat for clients, on a client portal token (Unit 57 §4). The routes are {@link ChatRoutes}'. */
@RestController
@RequestMapping("/api/portal/client/chat")
public class ClientChatController extends ChatRoutes {

	ClientChatController(ChatApi api) {
		super(api);
	}

	@Override
	protected ChatIdentity who() {
		return api.client();
	}
}
