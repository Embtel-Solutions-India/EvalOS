package com.ie.evalos.chat.push;

import java.time.Instant;
import java.util.UUID;

import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.domain.ScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** One browser a person allowed notifications in (Unit 57 §6, D37). Deleted when the push service says it is gone. */
@Entity
@Table(name = "push_subscriptions")
public class PushSubscription extends ScopedEntity {

	@Enumerated(EnumType.STRING)
	@Column(name = "subscriber_kind", nullable = false, updatable = false)
	private ParticipantKind subscriberKind;

	@Column(name = "subscriber_id", nullable = false, updatable = false)
	private UUID subscriberId;

	@Column(name = "endpoint", nullable = false, updatable = false)
	private String endpoint;

	@Column(name = "p256dh", nullable = false)
	private String p256dh;

	@Column(name = "auth", nullable = false)
	private String auth;

	@Column(name = "last_success_at")
	private Instant lastSuccessAt;

	protected PushSubscription() {
		// for JPA
	}

	public PushSubscription(UUID brandId, ParticipantKind subscriberKind, UUID subscriberId, String endpoint,
			String p256dh, String auth) {
		super(brandId);
		this.subscriberKind = subscriberKind;
		this.subscriberId = subscriberId;
		this.endpoint = endpoint;
		this.p256dh = p256dh;
		this.auth = auth;
	}

	public void markSent(Instant at) {
		this.lastSuccessAt = at;
	}

	public boolean belongsTo(ParticipantKind kind, UUID id) {
		return subscriberKind == kind && subscriberId.equals(id);
	}

	public ParticipantKind getSubscriberKind() {
		return subscriberKind;
	}

	public UUID getSubscriberId() {
		return subscriberId;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public String getP256dh() {
		return p256dh;
	}

	public String getAuth() {
		return auth;
	}

	public Instant getLastSuccessAt() {
		return lastSuccessAt;
	}
}
