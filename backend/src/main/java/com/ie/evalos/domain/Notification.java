package com.ie.evalos.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * An in-app notification for one staff member. EvalOS runs no mail server, so
 * this table is the whole staff notification centre; client messages go out
 * through GHL and expert nudges through the portal.
 */
@Entity
@Table(name = "notification")
public class Notification extends ScopedEntity {

	@Column(name = "recipient_id")
	private UUID recipientId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type")
	private NotificationType type;

	/** Loose reference: a notification may outlive the case it points at. */
	@Column(name = "case_id")
	private UUID caseId;

	@Column(name = "body")
	private String body;

	@Column(name = "read", nullable = false)
	private boolean read;

	protected Notification() {
		// for JPA
	}

	public Notification(UUID brandId, UUID recipientId, NotificationType type, UUID caseId, String body) {
		super(brandId);
		this.recipientId = recipientId;
		this.type = type;
		this.caseId = caseId;
		this.body = body;
	}

	public UUID getRecipientId() {
		return recipientId;
	}

	public NotificationType getType() {
		return type;
	}

	public UUID getCaseId() {
		return caseId;
	}

	public String getBody() {
		return body;
	}

	public boolean isRead() {
		return read;
	}

	/** One-way: the centre has no unread button, so there is no setter to flip it back. */
	public void markRead() {
		this.read = true;
	}
}
