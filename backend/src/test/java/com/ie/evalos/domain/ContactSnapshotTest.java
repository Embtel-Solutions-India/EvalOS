package com.ie.evalos.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A partial GHL payload is a partial statement about the contact, never an instruction to forget
 * the rest of it.
 *
 * <p><strong>This is the one that mattered.</strong> {@code GhlMirrorHandler} routes
 * {@code contact.created} and {@code contact.updated} into {@code findOrCreate}, and a GHL Custom
 * Webhook's body is whatever the workflow author mapped — the {@code contact_id} is the only field
 * EvalOS can count on. So a workflow carrying an id and a phone number reached
 * {@link ContactSnapshot#syncFromGhl} and blanked the client's name and nulled the email the portal
 * signs them in and mails them at. The five enum and UTM fields already had the guard; the four
 * identity strings did not.
 */
class ContactSnapshotTest {

	private static final UUID BRAND = UUID.randomUUID();

	private ContactSnapshot held() {
		ContactSnapshot contact = new ContactSnapshot(BRAND, "ghl-contact-1");
		contact.syncFromGhl("Ana Ruiz", "ana@example.test", "+1 555 0100", "Ruiz Law",
				null, null, null, null, null);
		return contact;
	}

	/** The webhook's own record rebuilds a missing full name as "", so blank has to count too. */
	@Test
	void aPayloadCarryingOnlyAPhoneNumberKeepsEverythingElse() {
		ContactSnapshot contact = held();

		contact.syncFromGhl("", null, "+1 555 0199", null, null, null, null, null, null);

		assertThat(contact.getFullName()).isEqualTo("Ana Ruiz");
		assertThat(contact.getEmail()).isEqualTo("ana@example.test");
		assertThat(contact.getCompany()).isEqualTo("Ruiz Law");
		assertThat(contact.getPhone()).isEqualTo("+1 555 0199");
	}

	/** A full payload still replaces a full payload: the guard narrows nothing that was working. */
	@Test
	void aCompletePayloadStillReplacesEveryField() {
		ContactSnapshot contact = held();

		contact.syncFromGhl("Ana Ruiz-Delgado", "ana@ruizlaw.test", "+1 555 0200", "Ruiz Delgado",
				null, null, null, null, null);

		assertThat(contact.getFullName()).isEqualTo("Ana Ruiz-Delgado");
		assertThat(contact.getEmail()).isEqualTo("ana@ruizlaw.test");
		assertThat(contact.getPhone()).isEqualTo("+1 555 0200");
		assertThat(contact.getCompany()).isEqualTo("Ruiz Delgado");
	}
}
