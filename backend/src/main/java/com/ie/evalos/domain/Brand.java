package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A tenant. Every scoped row in EvalOS belongs to exactly one brand. */
@Entity
@Table(name = "brand")
public class Brand {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private String name;

	/** URL-safe brand key. */
	@Column(nullable = false, unique = true)
	private String slug;

	@Column(nullable = false)
	private boolean active = true;

	/**
	 * Resolves the brand for that brand's inbound GHL endpoint at Handoff A, and is the
	 * only credential that endpoint has. Treat it as a secret: never log it, never put it
	 * in a DTO, and rotate it to revoke the endpoint.
	 */
	@Column(name = "webhook_endpoint_token", nullable = false, unique = true)
	private String webhookEndpointToken;

	/**
	 * What experts on this brand are paid in. Nullable because db/seed-local/V900
	 * inserts brands before this column exists; Flyway orders by version globally.
	 * payout_ledger.currency NOT NULL ensures the gap does not reach the ledger, and
	 * Task 3's openForDelivery refuses a brand with no currency.
	 */
	@Column(name = "currency")
	private String currency;

	/** Days from delivery to a payout's due date. Drives the weekly batch view. */
	@Column(name = "payout_term_days", nullable = false)
	private int payoutTermDays = 7;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	protected Brand() {
		// for JPA
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getSlug() {
		return slug;
	}

	public boolean isActive() {
		return active;
	}

	public String getWebhookEndpointToken() {
		return webhookEndpointToken;
	}

	public String getCurrency() {
		return currency;
	}

	public int getPayoutTermDays() {
		return payoutTermDays;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
