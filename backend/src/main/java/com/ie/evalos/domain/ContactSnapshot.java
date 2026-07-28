package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A brand-tagged copy of a GHL contact. GHL owns this data; EvalOS is not its
 * system of record (invariant 7). The only writer is the contact sync driven by
 * GHL's {@code contact.updated} event — no EvalOS business rule mutates a
 * synced field.
 */
@Entity
@Table(name = "contact_snapshot")
public class ContactSnapshot extends ScopedEntity {

	/** The GHL id this snapshot was taken from. */
	@Column(name = "ghl_contact_id")
	private String ghlContactId;

	@Column(name = "full_name")
	private String fullName;

	@Column(name = "email")
	private String email;

	@Column(name = "phone")
	private String phone;

	@Column(name = "company")
	private String company;

	@Enumerated(EnumType.STRING)
	@Column(name = "client_type")
	private ClientType clientType;

	@Enumerated(EnumType.STRING)
	@Column(name = "source_channel")
	private SourceChannel sourceChannel;

	@Column(name = "utm_source")
	private String utmSource;

	@Column(name = "utm_medium")
	private String utmMedium;

	@Column(name = "utm_campaign")
	private String utmCampaign;

	@Column(name = "date_first_captured")
	private Instant dateFirstCaptured;

	/** When this snapshot was last refreshed from GHL. */
	@Column(name = "synced_at")
	private Instant syncedAt;

	protected ContactSnapshot() {
		// for JPA
	}

	public ContactSnapshot(UUID brandId, String ghlContactId) {
		super(brandId);
		this.ghlContactId = ghlContactId;
		this.dateFirstCaptured = Instant.now();
	}

	/**
	 * Replaces the snapshot wholesale from GHL and restamps {@code synced_at}. The
	 * <em>only</em> writer of these fields: invariant 7 means no EvalOS business rule
	 * mutates a synced contact, and this is the sync, not a business rule. Called at
	 * Handoff A and, later, by GHL's {@code contact.updated}.
	 */
	public void syncFromGhl(String fullName, String email, String phone, String company, ClientType clientType,
			SourceChannel sourceChannel, String utmSource, String utmMedium, String utmCampaign) {
		this.fullName = fullName;
		this.email = email;
		this.phone = phone;
		this.company = company;
		this.clientType = clientType;
		this.sourceChannel = sourceChannel;
		this.utmSource = utmSource;
		this.utmMedium = utmMedium;
		this.utmCampaign = utmCampaign;
		this.syncedAt = Instant.now();
	}

	public String getGhlContactId() {
		return ghlContactId;
	}

	public String getFullName() {
		return fullName;
	}

	public String getEmail() {
		return email;
	}

	public Instant getSyncedAt() {
		return syncedAt;
	}
}
