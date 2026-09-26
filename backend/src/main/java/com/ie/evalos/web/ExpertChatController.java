package com.ie.evalos.web;

import com.ie.evalos.chat.ChatApi;
import com.ie.evalos.chat.ChatIdentity;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Case chat for experts, on an expert portal token (Unit 57 §4). The routes are {@link ChatRoutes}'. */
@RestController
@RequestMapping("/api/portal/expert/chat")
public class ExpertChatController extends ChatRoutes {

	ExpertChatController(ChatApi api) {
		super(api);
	}

	@Override
	protected ChatIdentity who() {
		return api.expert();
	}
}
