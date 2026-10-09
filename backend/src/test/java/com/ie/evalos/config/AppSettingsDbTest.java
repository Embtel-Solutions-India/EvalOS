package com.ie.evalos.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D83 against the real {@code app_setting} table: a secret is ciphertext at rest and plaintext only in memory,
 * a reset hands the setting back to the environment, and saving one logs nothing about its value.
 *
 * <p><strong>Not {@code @Transactional}</strong>: a save inside a transaction is read back only after it
 * commits, and a test transaction never does. Each test resets what it saved, so the table and the cache are
 * left as they were found.
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
		"evalos.workload.cases-per-cm=12",
})
@ExtendWith(OutputCaptureExtension.class)
class AppSettingsDbTest {

	private static final String TOKEN = "pit-db-test-0123456789abcdef";

	@Autowired
	AppSettings settings;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void aSecretIsCiphertextAtRestAndPlaintextOnlyInMemory(CapturedOutput output) {
		settings.save(Setting.GHL_TOKEN, TOKEN, null);

		String stored = jdbc.queryForObject("SELECT value FROM app_setting WHERE key = 'GHL_TOKEN'", String.class);
		assertThat(stored).isNotBlank().doesNotContain(TOKEN);
		assertThat(settings.app(Setting.GHL_TOKEN)).contains(TOKEN);
		assertThat(settings.source(Setting.GHL_TOKEN)).isEqualTo(AppSettings.Source.APP);
		assertThat(output.getAll()).doesNotContain(TOKEN);

		settings.clear(Setting.GHL_TOKEN);
	}

	@Test
	void anAppValueWinsAndAResetHandsTheSettingBackToTheEnvironment() {
		assertThat(settings.effective(Setting.CASES_PER_CM)).isEqualTo("12");
		assertThat(settings.source(Setting.CASES_PER_CM)).isEqualTo(AppSettings.Source.ENVIRONMENT);

		settings.save(Setting.CASES_PER_CM, "20", null);
		assertThat(settings.effective(Setting.CASES_PER_CM)).isEqualTo("20");
		assertThat(settings.source(Setting.CASES_PER_CM)).isEqualTo(AppSettings.Source.APP);

		settings.save(Setting.CASES_PER_CM, "25", null);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_setting WHERE key = 'CASES_PER_CM'", Integer.class))
				.isEqualTo(1);

		settings.clear(Setting.CASES_PER_CM);
		assertThat(settings.effective(Setting.CASES_PER_CM)).isEqualTo("12");
		assertThat(settings.source(Setting.CASES_PER_CM)).isEqualTo(AppSettings.Source.ENVIRONMENT);
	}

	@Test
	void aSwitchIsOnUntilTheAdministratorTurnsItOff() {
		assertThat(settings.enabled(Setting.GHL_WRITES_ENABLED)).isTrue();

		settings.save(Setting.GHL_WRITES_ENABLED, "false", null);
		assertThat(settings.enabled(Setting.GHL_WRITES_ENABLED)).isFalse();

		settings.clear(Setting.GHL_WRITES_ENABLED);
		assertThat(settings.enabled(Setting.GHL_WRITES_ENABLED)).isTrue();
	}
}
