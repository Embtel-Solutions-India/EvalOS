package com.ie.evalos.chat.push;

import java.time.Instant;
import java.util.UUID;

import com.ie.evalos.domain.ScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One push still owed to a browser the push service could not take it for (V92). Deleted when it
 * settles; see {@link PushSender#retry}.
 */
@Entity
@Table(name = "push_outbox")
public class PushRetry extends ScopedEntity {

	@Column(name = "subscription_id", nullable = false, updatable = false)
	private UUID subscriptionId;

	@Column(name = "payload", nullable = false, updatable = false)
	private String payload;

	@Column(name = "retries", nullable = false)
	private int retries;

	@Column(name = "due_at", nullable = false)
	private Instant dueAt;

	protected PushRetry() {
		// for JPA
	}

	PushRetry(PushSubscription to, String payload, Instant dueAt) {
		super(to.getBrandId());
		this.subscriptionId = to.getId();
		this.payload = payload;
		this.dueAt = dueAt;
	}

	void spendOne(Instant nextDueAt) {
		this.retries++;
		this.dueAt = nextDueAt;
	}

	public UUID getSubscriptionId() {
		return subscriptionId;
	}

	public String getPayload() {
		return payload;
	}

	public int getRetries() {
		return retries;
	}

	public Instant getDueAt() {
		return dueAt;
	}
}
