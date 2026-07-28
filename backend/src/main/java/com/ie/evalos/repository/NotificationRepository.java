package com.ie.evalos.repository;

import com.ie.evalos.domain.Notification;
import com.ie.evalos.service.ScopePredicate;

/**
 * A notification's assignee is its recipient, so a Self-tier caller reads only
 * their own. There is no team column: the notification centre filters by
 * recipient regardless of tier.
 */
public interface NotificationRepository extends ScopedRepository<Notification> {

	ScopePredicate.Fields SCOPE = new ScopePredicate.Fields("brandId", null, "recipientId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}
}
