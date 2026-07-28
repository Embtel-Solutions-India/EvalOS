package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One inbound webhook, archived before it was allowed to do anything (invariant
 * 10). This row is the idempotency ledger and the replay archive at once: the
 * unique {@code (source, external_id)} is what makes a redelivery a no-op.
 *
 * <p>Not a {@link ScopedEntity}, for the same reason as {@link AuditEvent}: the
 * brand is nullable because a source may one day be archived before its brand is
 * resolved. A GHL event always carries one, because resolution happens first.
 *
 * <p>Unlike an audit row this one is deliberately mutable, but only forwards:
 * {@code processed}/{@code processed_at} once it succeeds, or {@code error} when it
 * did not. The raw payload and the identity columns never change.
 */
@Entity
@Table(name = "webhook_event")
public class WebhookEvent {

	/** Long errors are truncated rather than failing the row that records them. */
	private static final int MAX_ERROR = 2000;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", updatable = false)
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, updatable = false)
	private WebhookSource source;

	@Column(name = "event_type", nullable = false, updatable = false)
	private String eventType;

	@Column(name = "external_id", nullable = false, updatable = false)
	private String externalId;

	@Column(name = "brand_id", updatable = false)
	private UUID brandId;

	@Column(name = "signature_verified", nullable = false, updatable = false)
	private boolean signatureVerified;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "raw_payload", nullable = false, updatable = false)
	private String rawPayload;

	@Column(name = "processed", nullable = false)
	private boolean processed;

	@Column(name = "received_at", nullable = false, updatable = false)
	private Instant receivedAt;

	@Column(name = "processed_at")
	private Instant processedAt;

	@Column(name = "error")
	private String error;

	protected WebhookEvent() {
		// for JPA
	}

	public WebhookEvent(WebhookSource source, String eventType, String externalId, UUID brandId,
			boolean signatureVerified, String rawPayload) {
		this.source = source;
		this.eventType = eventType;
		this.externalId = externalId;
		this.brandId = brandId;
		this.signatureVerified = signatureVerified;
		this.rawPayload = rawPayload;
	}

	@PrePersist
	void stampReceivedAt() {
		if (receivedAt == null) {
			receivedAt = Instant.now();
		}
	}

	public void markProcessed() {
		this.processed = true;
		this.processedAt = Instant.now();
		this.error = null;
	}

	/** Records why the handler failed. The row stays unprocessed so a redelivery retries it. */
	public void recordError(String message) {
		this.processed = false;
		this.error = message == null ? "(no message)"
				: message.substring(0, Math.min(message.length(), MAX_ERROR));
	}

	public UUID getId() {
		return id;
	}

	public UUID getBrandId() {
		return brandId;
	}

	public String getEventType() {
		return eventType;
	}

	public String getExternalId() {
		return externalId;
	}

	public boolean isProcessed() {
		return processed;
	}

	public String getError() {
		return error;
	}
}
