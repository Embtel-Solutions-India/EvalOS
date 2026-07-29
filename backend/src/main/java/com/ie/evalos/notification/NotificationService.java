package com.ie.evalos.notification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Notification;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.repository.NotificationRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff notification centre: the only writer of the {@code notification} table
 * and the only reader the endpoints use. EvalOS runs no mail server, so there is no
 * second channel to keep in step — a row here is the whole delivery.
 *
 * <p>Writes join the caller's transaction. A notification about a transition that
 * rolled back would be a lie, so it commits with the transition or not at all.
 */
@Service
public class NotificationService {

	/** A page nobody asked to size. Big enough for a bell, small enough to be one query. */
	static final int DEFAULT_PAGE_SIZE = 20;

	private final NotificationRepository notifications;

	NotificationService(NotificationRepository notifications) {
		this.notifications = notifications;
	}

	/**
	 * Raise one alert per recipient. The brand is the *case's*, passed explicitly
	 * because a listener has no authenticated caller to read it from — and because it
	 * must be the brand of the thing that happened, never the reader's.
	 *
	 * <p>Silently does nothing when the recipient list is empty: "documents complete on
	 * a case with no PM" is a real state, not an error, and a resolver that found nobody
	 * has already said so.
	 */
	@Transactional
	public void create(UUID brandId, Collection<UUID> recipients, NotificationType type, UUID caseId, String body) {
		recipients.forEach(recipient -> notifications.save(
				new Notification(brandId, recipient, type, caseId, body)));
	}

	/**
	 * Whether this case has already raised this kind of alert. Exists for the pool
	 * arrival, which must be announced once however many times its event fires.
	 */
	@Transactional(readOnly = true)
	public boolean alreadyRaised(UUID caseId, NotificationType type) {
		return notifications.existsByCaseIdAndType(caseId, type);
	}

	// --- the caller's own centre ---------------------------------------------
	//
	// Every read below is keyed on the caller's own member id, taken from the security
	// context. No parameter selects a recipient, so there is no request that can ask for
	// somebody else's bell.

	@Transactional(readOnly = true)
	public List<Notification> mine(int page, int size) {
		return notifications.findByRecipientIdOrderByReadAscCreatedAtDesc(
				me(), PageRequest.of(Math.max(page, 0), size < 1 ? DEFAULT_PAGE_SIZE : size));
	}

	@Transactional(readOnly = true)
	public long myUnreadCount() {
		return notifications.countByRecipientIdAndReadFalse(me());
	}

	/**
	 * Mark one read. Absent-or-somebody-else's are the same answer on purpose: whether
	 * a notification id exists is another member's business, so this cannot be used to
	 * probe for them. Matches how an out-of-scope case answers (Unit 04).
	 */
	@Transactional
	public Notification markRead(UUID id) {
		Notification subject = notifications.findByIdAndRecipientId(id, me())
				.orElseThrow(() -> new ForbiddenException("No such notification for this member"));
		subject.markRead();
		return notifications.save(subject);
	}

	/** Returns how many were still unread, so the caller can confirm the badge cleared. */
	@Transactional
	public int markAllRead() {
		return notifications.markAllReadFor(me());
	}

	private static UUID me() {
		return TenantContext.current().memberId();
	}
}
