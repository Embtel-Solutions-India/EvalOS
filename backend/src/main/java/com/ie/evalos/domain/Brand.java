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

	/** Resolves the brand for that brand's inbound GHL endpoint at Handoff A. */
	@Column(name = "webhook_endpoint_token", nullable = false, unique = true)
	private String webhookEndpointToken;

	/**
	 * The HMAC secret this brand's inbound GHL webhooks are verified against. Null
	 * until it is set, which fails closed: nothing can be verified, so nothing is
	 * accepted. Never log it and never put it in a DTO.
	 */
	@Column(name = "ghl_webhook_secret")
	private String ghlWebhookSecret;

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

	public String getGhlWebhookSecret() {
		return ghlWebhookSecret;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
