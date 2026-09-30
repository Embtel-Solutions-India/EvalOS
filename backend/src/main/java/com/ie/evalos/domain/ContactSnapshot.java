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

	/** GHL's country code, e.g. {@code US}. Shown on the deal screen, as are the two below. */
	@Column(name = "country")
	private String country;

	@org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.ARRAY)
	@Column(name = "tags", nullable = false)
	private String[] tags = new String[0];

	/** Keyed by GHL field id, like {@code opportunity.custom_fields}; {@code ghl_custom_field} names them. */
	@org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
	@Column(name = "custom_fields", nullable = false)
	private java.util.Map<String, String> customFields = new java.util.LinkedHashMap<>();

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
		// **Blank means "leave it alone", never "clear it"** — and these four needed the guard the
		// other five already had. A GHL Custom Webhook's body is whatever the workflow author
		// mapped, so a workflow carrying only a contact id and a phone number reached here and
		// blanked the client's name and nulled the email the portal signs them in and mails them
		// at. A partial payload is a partial statement about the contact, not an instruction to
		// forget the rest of it. Blank rather than null because the webhook's own record rebuilds
		// a missing full_name as "".
		if (fullName != null && !fullName.isBlank()) {
			this.fullName = fullName;
		}
		if (email != null && !email.isBlank()) {
			this.email = email;
		}
		if (phone != null && !phone.isBlank()) {
			this.phone = phone;
		}
		if (company != null && !company.isBlank()) {
			this.company = company;
		}
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

	/**
	 * Country, tags and custom field values from a GHL read of the contact.
	 *
	 * <p>Separate from {@link #syncFromGhl} because only a full GHL read carries them — the contact
	 * sweep and the deal screen's backfill. A webhook or sign-up passes null, and <strong>null
	 * leaves the held value alone</strong>, the same rule as a blank name. A non-null tag list or
	 * field map replaces the held one, empty included: from a full read, "no tags" is a real answer.
	 */
	public void syncDetails(String country, java.util.List<String> tags, java.util.Map<String, String> customFields) {
		if (country != null && !country.isBlank()) {
			this.country = country;
		}
		if (tags != null) {
			this.tags = tags.toArray(String[]::new);
		}
		if (customFields != null) {
			this.customFields = new java.util.LinkedHashMap<>(customFields);
		}
	}

	public String getCountry() {
		return country;
	}

	public java.util.List<String> getTags() {
		return java.util.List.of(tags);
	}

	public java.util.Map<String, String> getCustomFields() {
		return java.util.Collections.unmodifiableMap(customFields);
	}

	public String getGhlContactId() {
		return ghlContactId;
	}

	/**
	 * The three fields a desk screen shows beside a deal.
	 *
	 * <p>Added at the opportunity detail screen (2026-09-17): the columns existed and were written
	 * by every sync, but nothing could read them, so "who is this deal with" had no answer that did
	 * not go back to GHL.
	 */
	public String getEmail() {
		return email;
	}

	public String getPhone() {
		return phone;
	}

	public String getCompany() {
		return company;
	}

	public String getFullName() {
		return fullName;
	}

	public Instant getSyncedAt() {
		return syncedAt;
	}

	/**
	 * How this person first reached the business, or null.
	 *
	 * <p><strong>A capture-time fact, which is why it is safe to show beside a deal.</strong>
	 * {@link #syncFromGhl} treats the five attribution fields as fill-only — they describe how
	 * somebody arrived and cannot change — so the value here is the one recorded when EvalOS first
	 * met them, not whatever the most recent payload happened to carry.
	 */
	public SourceChannel getSourceChannel() {
		return sourceChannel;
	}
}
