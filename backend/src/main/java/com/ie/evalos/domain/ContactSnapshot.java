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
 * system of record (invariant 7): no EvalOS business rule mutates a synced field.
 *
 * <p>Two writers, and the split is the point:
 * <ul>
 * <li>{@link #syncFromGhl} replaces the synced fields wholesale from a GHL payload.
 *     Driven by Handoff A's {@code contact.created} today; {@code contact.updated} is
 *     recognized by the router but still a deliberate no-op, so it is not a driver yet.
 * <li>{@link #linkGhlContact} writes the GHL id, and <em>only</em> when it is absent.
 *     This one is EvalOS inference rather than a passthrough — it repairs a row matched
 *     by email — which is why it is write-once and separate. It does not touch a synced
 *     field, so invariant 7 still holds: identity is not content.
 * </ul>
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
	 * Fills in the GHL id on a row that was created without one — a snapshot matched by
	 * email because the delivery carried no id, then repaired when a later delivery does.
	 *
	 * <p><strong>Write-once.</strong> An id already present is never replaced: two GHL
	 * contacts sharing an email would otherwise let the second silently take over the
	 * first's snapshot, and every case pointing at it. Separate from
	 * {@link #syncFromGhl} for exactly that reason — the id is identity, not synced data.
	 */
	public void linkGhlContact(String ghlContactId) {
		if (ghlContactId != null && !ghlContactId.isBlank()
				&& (this.ghlContactId == null || this.ghlContactId.isBlank())) {
			this.ghlContactId = ghlContactId;
		}
	}

	/**
	 * Replaces the contact's own details wholesale from GHL and restamps {@code synced_at}.
	 * The <em>only</em> writer of these fields: invariant 7 means no EvalOS business rule
	 * mutates a synced contact, and this is the sync, not a business rule. Called at
	 * Handoff A and, later, by GHL's {@code contact.updated}.
	 *
	 * <p><strong>Attribution is fill-only, and that is not an inconsistency.</strong> Name,
	 * email, phone and company are current state, so a delivery that omits one is GHL saying
	 * it is gone. The five attribution fields are capture-time facts about how this person
	 * first arrived — they cannot change, only be absent from a payload that never carried
	 * them. GHL's Custom Webhook is exactly that payload: it sends the contact record and
	 * the deal and nothing about attribution, so a wholesale write here would blank every
	 * one of them on every won opportunity, and this being their only writer, nothing could
	 * put them back.
	 */
	public void syncFromGhl(String fullName, String email, String phone, String company, ClientType clientType,
			SourceChannel sourceChannel, String utmSource, String utmMedium, String utmCampaign) {
		this.fullName = fullName;
		this.email = email;
		this.phone = phone;
		this.company = company;
		if (clientType != null) {
			this.clientType = clientType;
		}
		if (sourceChannel != null) {
			this.sourceChannel = sourceChannel;
		}
		if (utmSource != null) {
			this.utmSource = utmSource;
		}
		if (utmMedium != null) {
			this.utmMedium = utmMedium;
		}
		if (utmCampaign != null) {
			this.utmCampaign = utmCampaign;
		}
		this.syncedAt = Instant.now();
	}

	public String getGhlContactId() {
		return ghlContactId;
	}

	public String getFullName() {
		return fullName;
	}

	public Instant getSyncedAt() {
		return syncedAt;
	}
}
