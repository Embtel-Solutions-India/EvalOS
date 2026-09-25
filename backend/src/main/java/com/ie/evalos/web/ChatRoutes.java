package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.chat.ChatApi;
import com.ie.evalos.chat.ChatIdentity;
import com.ie.evalos.chat.ChatViews;
import com.ie.evalos.chat.ConversationStatus;
import com.ie.evalos.chat.ConversationType;
import com.ie.evalos.chat.Reaction;
import com.ie.evalos.common.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The case-chat routes, once (Unit 57 §4). Each surface's controller extends this with its own prefix
 * and says who the caller is; Spring maps the inherited methods under that prefix.
 *
 * <p><strong>No role list, on purpose.</strong> Who may read a conversation is membership, decided per
 * conversation by {@code ChatAccess}; a caller with no conversations simply gets an empty inbox.
 */
public abstract class ChatRoutes {

	protected final ChatApi api;

	protected ChatRoutes(ChatApi api) {
		this.api = api;
	}

	/** The caller, as this surface's credential names them. */
	protected abstract ChatIdentity who();

	@GetMapping("/conversations")
	public ApiResponse<ChatViews.Page<ChatViews.ConversationView>> inbox(@RequestParam(required = false) UUID caseId,
			@RequestParam(required = false) ConversationType type,
			@RequestParam(required = false) ConversationStatus status, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "50") int limit) {
		return ApiResponse.ok(api.inbox(who(), caseId, type, status, cursor, limit));
	}

	@GetMapping("/conversations/{id}")
	public ApiResponse<ChatViews.ConversationView> conversation(@PathVariable UUID id) {
		return ApiResponse.ok(api.conversation(who(), id));
	}

	@GetMapping("/conversations/{id}/messages")
	public ApiResponse<ChatViews.Page<ChatViews.MessageView>> messages(@PathVariable UUID id,
			@RequestParam(required = false) String before, @RequestParam(required = false) String after,
			@RequestParam(defaultValue = "50") int limit) {
		return ApiResponse.ok(api.messages(who(), id, before, after, limit));
	}

	@GetMapping("/conversations/{id}/read-state")
	public ApiResponse<ChatViews.ReadState> readState(@PathVariable UUID id) {
		return ApiResponse.ok(api.readState(who(), id));
	}

	@GetMapping("/messages/{id}/replies")
	public ApiResponse<List<ChatViews.MessageView>> replies(@PathVariable UUID id) {
		return ApiResponse.ok(api.replies(who(), id));
	}

	@PostMapping("/conversations/{id}/messages")
	public ApiResponse<ChatViews.MessageView> send(@PathVariable UUID id,
			@Valid @RequestBody ChatApi.SendRequest request) {
		return ApiResponse.ok(api.send(who(), id, request));
	}

	@PutMapping("/messages/{id}")
	public ApiResponse<ChatViews.MessageView> edit(@PathVariable UUID id,
			@Valid @RequestBody ChatApi.EditRequest request) {
		return ApiResponse.ok(api.edit(who(), id, request));
	}

	@DeleteMapping("/messages/{id}")
	public ApiResponse<Void> delete(@PathVariable UUID id) {
		api.delete(who(), id);
		return ApiResponse.ok(null);
	}

	@PutMapping("/messages/{id}/reactions/{reaction}")
	public ApiResponse<ChatViews.MessageView> react(@PathVariable UUID id, @PathVariable Reaction reaction) {
		return ApiResponse.ok(api.react(who(), id, reaction, true));
	}

	@DeleteMapping("/messages/{id}/reactions/{reaction}")
	public ApiResponse<ChatViews.MessageView> unreact(@PathVariable UUID id, @PathVariable Reaction reaction) {
		return ApiResponse.ok(api.react(who(), id, reaction, false));
	}

	@PostMapping("/conversations/{id}/read")
	public ApiResponse<Void> read(@PathVariable UUID id, @Valid @RequestBody ChatApi.ReadRequest request) {
		api.read(who(), id, request);
		return ApiResponse.ok(null);
	}

	@GetMapping("/search")
	public ApiResponse<List<ChatViews.MessageView>> search(@RequestParam String q,
			@RequestParam(required = false) UUID caseId, @RequestParam(required = false) ConversationType type,
			@RequestParam(defaultValue = "25") int limit) {
		return ApiResponse.ok(api.search(who(), q, caseId, type, limit));
	}

	@GetMapping("/unread")
	public ApiResponse<Long> unread() {
		return ApiResponse.ok(api.unread(who()));
	}

	/** "I am typing" — relayed by the backend, throttled, never stored. */
	@PostMapping("/conversations/{id}/typing")
	public ApiResponse<Void> typing(@PathVariable UUID id) {
		api.typing(who(), id);
		return ApiResponse.ok(null);
	}

	/** Which of this conversation's participants have an app open. */
	@GetMapping("/conversations/{id}/presence")
	public ApiResponse<java.util.Map<String, Boolean>> presence(@PathVariable UUID id) {
		return ApiResponse.ok(api.presence(who(), id));
	}

	/** ably-js calls this through authCallback, and again before the token expires. */
	@GetMapping("/realtime/token")
	public ApiResponse<com.ie.evalos.chat.live.AblyToken> realtimeToken() {
		return ApiResponse.ok(api.realtimeToken(who()));
	}
}
