package com.ie.evalos.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.ie.evalos.domain.ClientAccount;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the seeding migration survives the two things contact_snapshot does that a naive insert
 * would trip over: a null email, and two contacts sharing one.
 *
 * <p><strong>Not {@code @DataJpaTest}.</strong> The brief's draft used it, but every other
 * database-backed test in this package ({@link LocalPostgresIntegrationTest}) is a
 * {@code @SpringBootTest} gated on {@link LocalPostgresIntegrationTest#postgresIsUsable()} against
 * the real {@code evalos_test} schema — there is no Testcontainers dependency in this project, and
 * introducing a second, incompatible base for one test class would mean this migration is only
 * ever proven under a style nothing else here uses. This class reuses that same gate and schema
 * rather than invent a second one, and runs the migration's own SQL file rather than a
 * hand-copied restatement of it, so the test fails if V45 itself ever drifts from what is
 * asserted here.
 *
 * <p><strong>{@code @Transactional}</strong>, unlike {@code LocalPostgresIntegrationTest}: that
 * suite gives every row a random id or a {@code UUID.randomUUID()} email so unrelated tests never
 * collide, but this class re-executes V45's own SQL on every call. Without a rollback, one test's
 * seeded rows are still there for the next test's {@code runSeed()} to re-select, which re-inserts
 * a {@code (brand_id, email)} pair {@code client_account_brand_email_key} already has and fails
 * the *next* test, not the one that created the row. Rolling back after each method keeps that
 * accumulation from ever happening.
 *
 * <p><strong>{@code runSeed()} scopes the file's own SQL to the brand this test just created</strong>
 * rather than running it completely unscoped. {@code evalos_test} is a persistent, shared schema —
 * V45 has already run for real here (Flyway migrates whatever is pending on every context start),
 * against years of accumulated {@code contact_snapshot} rows from this suite and from
 * {@code db/seed-local}'s fixtures, so replaying the file's bare SQL hits
 * {@code client_account_brand_email_key} on brands this test never touched. Deleting existing
 * {@code client_account} rows to dodge that is not safe either: {@code portal_access} and
 * {@code client_credential_token} both reference them, with no cascade. A trailing
 * {@code AND c.brand_id = ?} bound to {@link #seedBrand()}'s own id leaves the SELECT — the
 * DISTINCT ON, the null/blank-email guard, the ghl_contact_id copy-forward — exactly as V45 wrote
 * it, and only narrows which rows it is allowed to touch.
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
class ClientAccountSeedTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ClientAccountRepository accounts;

	/** Gives each snapshot inserted in a test its own ghl_contact_id, deterministically. */
	private final AtomicInteger ghlContactSeq = new AtomicInteger(0);

	/** Set by {@link #seedBrand()}; {@link #runSeed()} scopes V45's SQL to it. See the class javadoc. */
	private UUID testBrand;

	@Test
	void twoSnapshotsSharingAnEmailCollapseIntoOneAccount() {
		UUID brand = seedBrand();
		insertSnapshot(brand, "shared@example.com", "Ana One");
		insertSnapshot(brand, "SHARED@example.com", "Ana Two");

		runSeed();

		assertThat(accounts.findByBrandIdAndEmailIgnoreCase(brand, "shared@example.com")).isPresent();
		assertThat(jdbc.queryForObject(
				"select count(*) from client_account where brand_id = ?", Integer.class, brand))
				.isEqualTo(1);
	}

	@Test
	void aSnapshotWithNoEmailIsSkipped() {
		UUID brand = seedBrand();
		insertSnapshot(brand, null, "No Email");

		runSeed();

		assertThat(jdbc.queryForObject(
				"select count(*) from client_account where brand_id = ?", Integer.class, brand))
				.isZero();
	}

	@Test
	void seededAccountsHaveNoPasswordButKeepTheirGhlContactId() {
		UUID brand = seedBrand();
		insertSnapshot(brand, "ana@example.com", "Ana Perez");

		runSeed();

		ClientAccount account = accounts.findByBrandIdAndEmailIgnoreCase(brand, "ana@example.com")
				.orElseThrow();
		assertThat(account.hasPassword()).isFalse();
		// Copied, NOT nulled: this id is EvalOS's join key from a client to their cases, and
		// PortalCaseService.authorized() fails closed without it. See the migration's header.
		assertThat(account.getGhlContactId()).isEqualTo("ghl-contact-1");
	}

	// Helpers: seedBrand() inserts a minimal active brand row; insertSnapshot() inserts one
	// contact_snapshot; runSeed() executes the body of V45 against the test database. Read the
	// column lists off V43 and the contact_snapshot migration rather than guessing them.

	/** brand's columns per V2: id, name, slug, active, webhook_endpoint_token (both unique), created_at. */
	private UUID seedBrand() {
		UUID id = UUID.randomUUID();
		jdbc.update(
				"insert into brand (id, name, slug, active, webhook_endpoint_token, created_at) "
						+ "values (?, ?, ?, true, ?, now())",
				id, "Seed Test Brand " + id, "seed-test-" + id, "seed-test-token-" + id);
		testBrand = id;
		return id;
	}

	/**
	 * contact_snapshot's columns per V4. Each call gets its own {@code ghl_contact_id}
	 * ("ghl-contact-1", "ghl-contact-2", ...) so a test that inserts exactly one snapshot can
	 * assert on the value V45 must carry forward.
	 */
	private void insertSnapshot(UUID brandId, String email, String fullName) {
		jdbc.update(
				"insert into contact_snapshot (id, brand_id, ghl_contact_id, full_name, email, phone, created_at) "
						+ "values (gen_random_uuid(), ?, ?, ?, ?, ?, now())",
				brandId, "ghl-contact-" + ghlContactSeq.incrementAndGet(), fullName, email, "555-0100");
	}

	/**
	 * Runs V45's own SQL file rather than a copy of it, so this test fails if the migration and
	 * the assertions here ever drift apart — narrowed to {@link #testBrand} by appending one bound
	 * predicate ahead of the file's own {@code ORDER BY}. See the class javadoc for why an
	 * unscoped replay against this shared schema is not safe.
	 */
	private void runSeed() {
		try {
			String sql = StreamUtils.copyToString(
					new ClassPathResource("db/migration/V45__seed_client_accounts.sql").getInputStream(),
					StandardCharsets.UTF_8);
			String scoped = sql.replace(
					"order by c.brand_id, lower(c.email), c.created_at asc;",
					"and c.brand_id = ?\norder by c.brand_id, lower(c.email), c.created_at asc;");
			if (scoped.equals(sql)) {
				throw new IllegalStateException(
						"V45's ORDER BY line changed shape; update the scoping replace() above to match");
			}
			jdbc.update(scoped, testBrand);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
