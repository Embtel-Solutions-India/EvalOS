package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A client's own EvalOS account: the thing they sign in with (Unit 42).
 *
 * <p><strong>{@code passwordHash == null} is the "never set a password" state</strong>, and it is
 * what {@code identify} answers {@code NO_PASSWORD} on. There is no status column beside it: a
 * second field stating what the first already states is a second thing to keep in step.
 *
 * <p><strong>{@code ghlContactId} is a link, not the identity.</strong> Invariant 7 as amended by
 * this unit — GHL's contact id remains canonical <em>in GHL</em>, but a client's ability to sign
 * in no longer depends on GHL holding a row.
 *
 * <p><strong>V45 copies it forward from {@code contact_snapshot}, and an earlier draft that
 * seeded it null was reversed.</strong> The argument for null was that IE's GHL sub-account was
 * replaced on 2026-09-11, so every id EvalOS holds names a contact GHL no longer has. True of the
 * column's GHL job, irrelevant to its EvalOS one: {@code PortalCaseService.authorized()} resolves
 * a party token to its cases <em>through</em> this id and fails closed when it is null, so a null
 * here would let every existing client sign in and then see no cases at all. Null is still legal
 * and normal — a self-signup, or a snapshot that never carried one — it is simply not what the
 * seed writes.
 */
@Entity
@Table(name = "client_account")
public class ClientAccount extends ScopedEntity {

	@Column(name = "email", nullable = false, updatable = false)
	private String email;

	@Column(name = "password_hash")
	private String passwordHash;

	/**
	 * The CRM row for this person — Unit 44c's link.
	 *
	 * <p><strong>The fix for "one person is two rows with no link".</strong> Both this table and
	 * {@code contact_snapshot} could hold a {@code ghl_contact_id} and nothing joined them, so a
	 * case could reach the contact and never the account. Null is legal: a client may sign up
	 * before EvalOS has any other trace of them, and a wrong link is far worse than a missing one.
	 */
	@Column(name = "contact_id")
	private UUID contactId;

	@Column(name = "ghl_contact_id")
	private String ghlContactId;

	@Column(name = "first_name")
	private String firstName;

	@Column(name = "last_name")
	private String lastName;

	@Column(name = "phone")
	private String phone;

	@Column(name = "country")
	private String country;

	@Column(name = "last_sign_in_at")
	private Instant lastSignInAt;

	/**
	 * Where this row came from — {@code SEED}, {@code SIGNUP} or {@code STAFF} (V59).
	 *
	 * <p><strong>It exists for one reader: {@code PORTAL_CLEANUP}.</strong> A seeded client and an
	 * abandoned self-signup are indistinguishable on every other column — null password, a linked
	 * contact, a null {@code last_sign_in_at} — so a sweep that deletes the second would have
	 * deleted the first, which is the whole seeded backlog and the exact failure V45 exists to
	 * prevent. Review caught it before it shipped.
	 *
	 * <p><strong>Defaults to {@code SEED}, which is the value nothing deletes.</strong> A writer
	 * that forgets to set this gets a row that survives rather than one that quietly qualifies.
	 */
	@Column(name = "created_via", nullable = false)
	private String createdVia = "SEED";

	protected ClientAccount() {
		// for JPA
	}

	public ClientAccount(UUID brandId, String email) {
		this(brandId, email, "SEED");
	}

	/**
	 * @param createdVia {@code SIGNUP} for a self-service sign-up — the only value
	 *                   {@code PORTAL_CLEANUP} will ever delete. See {@link #createdVia}.
	 */
	public ClientAccount(UUID brandId, String email, String createdVia) {
		super(brandId);
		this.email = email;
		this.createdVia = createdVia;
	}

	public String getCreatedVia() {
		return createdVia;
	}

	/** Whether this account can be signed into with a password today. */
	public boolean hasPassword() {
		return passwordHash != null && !passwordHash.isBlank();
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public String getGhlContactId() {
		return ghlContactId;
	}

	/** Records the contact GHL created for this client. Never mints one — invariant 7. */
	public void linkGhlContact(String ghlContactId) {
		this.ghlContactId = ghlContactId;
	}

	public void recordSignIn(Instant at) {
		this.lastSignInAt = at;
	}

	public Instant getLastSignInAt() {
		return lastSignInAt;
	}

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public String getCountry() {
		return country;
	}

	public void setCountry(String country) {
		this.country = country;
	}

	public UUID getContactId() {
		return contactId;
	}

	/** Set once, when the CRM row is first found or created for this person. */
	public void linkContact(UUID contactId) {
		if (this.contactId == null) {
			this.contactId = contactId;
		}
	}
}
