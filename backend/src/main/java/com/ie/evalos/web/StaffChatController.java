package com.ie.evalos.web;

import com.ie.evalos.chat.ChatApi;
import com.ie.evalos.chat.ChatIdentity;
import com.ie.evalos.security.TenantContext;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Case chat for staff, on the staff session (Unit 57 §4). The routes are {@link ChatRoutes}'. */
@RestController
@RequestMapping("/api/chat")
public class StaffChatController extends ChatRoutes {

	StaffChatController(ChatApi api) {
		super(api);
	}

	@Override
	protected ChatIdentity who() {
		return ChatIdentity.staff(TenantContext.current());
	}
}
