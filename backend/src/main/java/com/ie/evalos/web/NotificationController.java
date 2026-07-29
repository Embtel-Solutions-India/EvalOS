package com.ie.evalos.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Notification;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.notification.NotificationService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own notification centre, and nothing else.
 *
 * <p>There is deliberately **no {@code @PreAuthorize} and no recipient parameter**:
 * every route reads the member id off the security context, so each is already
 * restricted to exactly one person's rows. A role gate would be the wrong tool —
 * every staff role has a bell, and none of them may read another's. Endpoints are
 * secured by default, so an unauthenticated call is already 401.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

	/** What a bell shows. No recipient id: the answer is always the caller's own. */
	public record NotificationView(
			UUID id,
			NotificationType type,
			UUID caseId,
			String body,
			boolean read,
			Instant createdAt) {

		static NotificationView of(Notification source) {
			return new NotificationView(source.getId(), source.getType(), source.getCaseId(),
					source.getBody(), source.isRead(), source.getCreatedAt());
		}
	}

	/** Returned by both mark-read routes so a client can repaint the badge in one call. */
	public record ReadResult(int marked, long unread) {
	}

	private final NotificationService notifications;

	NotificationController(NotificationService notifications) {
		this.notifications = notifications;
	}

	@GetMapping
	public ApiResponse<List<NotificationView>> list(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ApiResponse.ok(notifications.mine(page, size).stream().map(NotificationView::of).toList());
	}

	@GetMapping("/unread-count")
	public ApiResponse<Long> unreadCount() {
		return ApiResponse.ok(notifications.myUnreadCount());
	}

	@PostMapping("/{id}/read")
	public ApiResponse<ReadResult> markRead(@PathVariable UUID id) {
		notifications.markRead(id);
		return ApiResponse.ok(new ReadResult(1, notifications.myUnreadCount()));
	}

	@PostMapping("/read-all")
	public ApiResponse<ReadResult> markAllRead() {
		int marked = notifications.markAllRead();
		return ApiResponse.ok(new ReadResult(marked, notifications.myUnreadCount()));
	}
}
