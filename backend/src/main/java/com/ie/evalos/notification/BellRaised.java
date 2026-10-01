package com.ie.evalos.notification;

import java.util.UUID;

import com.ie.evalos.domain.NotificationType;

/**
 * One bell row written, for the push beside it (D37). Published inside the writing transaction, so
 * a listener on {@code AFTER_COMMIT} never pushes a notification that rolled back.
 */
public record BellRaised(UUID recipientId, NotificationType type, UUID caseId, String body) {
}
