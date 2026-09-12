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

	protected ClientAccount() {
		// for JPA
	}

	public ClientAccount(UUID brandId, String email) {
		super(brandId);
		this.email = email;
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
}
