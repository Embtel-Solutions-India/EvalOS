package com.ie.evalos.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The production Administrator seed — {@code seed-prod/V960_1} (the guard) followed by {@code V961} (the
 * original insert), in Flyway's order — run from their own files with the two placeholders filled in,
 * against the real {@code evalos_test} schema, each test rolled back.
 *
 * <p>The contract: an existing Administrator comes out byte-for-byte as it went in (hash included, so the
 * same password still signs in); a missing one is created once, through a BCrypt hash, and never twice;
 * an address that is ambiguous or belongs to somebody else stops the migration rather than promoting or
 * duplicating anyone.
 *
 * <p>The shared schema already holds the local seed's Administrator ({@code V916}). The tests that need
 * "no Administrator at all" make it a GM inside their own transaction, which the rollback restores.
 */
@SpringBootTest
@EnabledIf("com.ie.evalos.repository.LocalPostgresIntegrationTest#postgresIsUsable")
@TestPropertySource(properties = {
		"spring.datasource.url=${DB_TEST_URL:jdbc:postgresql://localhost:5432/evalos?currentSchema=evalos_test}",
		"spring.flyway.schemas=evalos_test",
		"spring.flyway.create-schemas=true",
		"spring.flyway.locations=classpath:db/migration,classpath:db/seed-local",
		"spring.flyway.out-of-order=true",
		"spring.flyway.ignore-migration-patterns=*:missing",
		"spring.jpa.properties.hibernate.default_schema=evalos_test",
		"spring.jpa.show-sql=false",
		"evalos.jobs.enabled=false",
})
@Transactional
class AdminSeedTest {

	private static final String BRAND_IE = "11111111-1111-1111-1111-111111111111";

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder encoder;

	private static String unusedEmail() {
		return "admin-" + UUID.randomUUID() + "@seed-test.invalid";
	}

	/** Runs the guard then V961, as a Flyway migrate of the seed-prod tree would. */
	private void seed(String email, String hash) {
		for (String file : new String[] {"db/seed-prod/V960_1__ensure_admin.sql", "db/seed-prod/V961__seed_admin.sql"}) {
			jdbc.execute(read(file).replace("${admin-email}", email).replace("${admin-password-hash}", hash));
		}
	}

	private void insertMember(String email, String role, String brand, String hash, boolean active) {
		jdbc.update("insert into team_member (id, brand_id, role, email, password_hash, display_name, active) "
				+ "values (gen_random_uuid(), ?::uuid, ?, ?, ?, 'Original Name', ?)", brand, role, email, hash, active);
	}

	private Map<String, Object> row(String email) {
		return jdbc.queryForMap("select id, brand_id, team_id, role, email, password_hash, display_name, reports_to, "
				+ "active, created_at from team_member where email = ?", email);
	}

	private int accountsFor(String email) {
		return jdbc.queryForObject("select count(*) from team_member where lower(trim(email)) = lower(trim(?))",
				Integer.class, email);
	}

	private int administrators() {
		return jdbc.queryForObject("select count(*) from team_member where role = 'ADMIN'", Integer.class);
	}

	@Test
	void anExistingAdministratorIsLeftExactlyAsStoredAndStillSignsIn() {
		String email = unusedEmail();
		String originalHash = encoder.encode("The-Original-Passw0rd");
		insertMember(email, "ADMIN", null, originalHash, false);
		Map<String, Object> before = row(email);

		// A different hash in the environment must change nothing; neither must running it again.
		seed(email, encoder.encode("A-Replacement-Passw0rd"));
		seed(email, encoder.encode("A-Replacement-Passw0rd"));

		assertThat(row(email)).isEqualTo(before);
		assertThat(accountsFor(email)).isEqualTo(1);
		assertThat(encoder.matches("The-Original-Passw0rd", (String) row(email).get("password_hash"))).isTrue();
	}

	@Test
	void aMissingAdministratorIsCreatedOnceThroughTheBCryptHash() {
		jdbc.update("update team_member set role = 'GM' where role = 'ADMIN'");
		String email = unusedEmail();
		String hash = encoder.encode("First-Admin-Passw0rd");

		seed(email, hash);
		Map<String, Object> created = row(email);
		seed(email, encoder.encode("Some-Other-Passw0rd"));

		assertThat(accountsFor(email)).isEqualTo(1);
		assertThat(administrators()).isEqualTo(1);
		assertThat(created).containsEntry("role", "ADMIN").containsEntry("active", true)
				.containsEntry("password_hash", hash).containsEntry("brand_id", null);
		assertThat(row(email)).isEqualTo(created);
		assertThat(encoder.matches("First-Admin-Passw0rd", (String) created.get("password_hash"))).isTrue();
	}

	@Test
	void anAddressBelongingToANonAdminStopsTheSeedRatherThanPromotingIt() {
		String email = unusedEmail();
		insertMember(email, "CASE_MANAGER", BRAND_IE, encoder.encode("Desk-Passw0rd"), true);

		assertThatThrownBy(() -> seed(email, encoder.encode("Admin-Passw0rd")))
				.rootCause().hasMessageContaining("Admin seed refused")
				.hasMessageContaining("never promoted");
	}

	/** Sign-in ignores case but the unique index does not, so V961 alone would create a second login. */
	@Test
	void anAddressDifferingOnlyInCaseFromTheAdministratorStopsTheSeed() {
		String email = unusedEmail();
		insertMember(email, "ADMIN", null, encoder.encode("Admin-Passw0rd"), true);

		assertThatThrownBy(() -> seed(email.toUpperCase(), encoder.encode("Admin-Passw0rd")))
				.rootCause().hasMessageContaining("Admin seed refused");
	}

	@Test
	void twoAccountsThatAlreadyCollideOnTheAddressStopTheSeed() {
		String email = unusedEmail();
		insertMember(email, "ADMIN", null, encoder.encode("Admin-Passw0rd"), true);
		insertMember(email.toUpperCase(), "ADMIN", null, encoder.encode("Admin-Passw0rd"), true);

		assertThatThrownBy(() -> seed(email, encoder.encode("Admin-Passw0rd")))
				.rootCause().hasMessageContaining("matches 2 accounts");
	}

	/** V961 matches exactly, so a padded address would otherwise end as a second login beside the guard's own. */
	@Test
	void aPaddedAddressIsRefusedRatherThanSeededTwice() {
		jdbc.update("update team_member set role = 'GM' where role = 'ADMIN'");

		for (String padded : new String[] {unusedEmail() + " ", unusedEmail() + "\n", " " + unusedEmail()}) {
			assertThatThrownBy(() -> seed(padded, encoder.encode("Admin-Passw0rd"))).as(padded)
					.rootCause().hasMessageContaining("leading or trailing space");
		}
	}

	@Test
	void aPasswordInPlaceOfAHashIsRefusedBeforeAnythingIsStored() {
		jdbc.update("update team_member set role = 'GM' where role = 'ADMIN'");

		assertThatThrownBy(() -> seed(unusedEmail(), "PlainTextPassw0rd!"))
				.rootCause().hasMessageContaining("not a BCrypt hash");
	}

	/**
	 * An Administrator under another address (renamed, or testprod's V954) means one exists: the guard adds
	 * nobody. V961, on its own first run, still inserts its own address — the pre-existing behaviour, and a
	 * distinct login rather than a duplicate.
	 */
	@Test
	void theGuardNeverAddsASecondAdministratorWhenOneExists() {
		int before = administrators();
		assertThat(before).isPositive();

		jdbc.execute(read("db/seed-prod/V960_1__ensure_admin.sql")
				.replace("${admin-email}", unusedEmail())
				.replace("${admin-password-hash}", encoder.encode("Admin-Passw0rd")));

		assertThat(administrators()).isEqualTo(before);
	}

	private static String read(String path) {
		try {
			return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
