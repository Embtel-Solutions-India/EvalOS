package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The account's own rules. Nothing here touches a database: what is being pinned is that
 * "has no password" is derived from the hash rather than stored beside it.
 */
class ClientAccountTest {

	private static final UUID BRAND = UUID.randomUUID();

	@Test
	void seededAccountHasNoPassword() {
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");

		assertThat(account.hasPassword()).isFalse();
		assertThat(account.getPasswordHash()).isNull();
	}

	@Test
	void settingAHashGivesItAPassword() {
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");

		account.setPasswordHash("$2a$10$abcdefghijklmnopqrstuv");

		assertThat(account.hasPassword()).isTrue();
	}

	@Test
	void aCredentialTokenIsUsableOnceAndThenNeverAgain() {
		Instant now = Instant.parse("2026-09-12T10:00:00Z");
		ClientCredentialToken token = new ClientCredentialToken(
				BRAND, UUID.randomUUID(), "hash", CredentialPurpose.SET, now.plusSeconds(1800));

		assertThat(token.isUsable(now)).isTrue();

		token.markUsed(now);

		assertThat(token.isUsable(now)).isFalse();
	}

	@Test
	void anExpiredCredentialTokenIsNotUsable() {
		Instant issued = Instant.parse("2026-09-12T10:00:00Z");
		ClientCredentialToken token = new ClientCredentialToken(
				BRAND, UUID.randomUUID(), "hash", CredentialPurpose.RESET, issued.plusSeconds(1800));

		assertThat(token.isUsable(issued.plusSeconds(1801))).isFalse();
	}
}
