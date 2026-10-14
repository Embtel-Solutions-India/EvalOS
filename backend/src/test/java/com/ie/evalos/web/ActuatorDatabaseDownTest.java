package com.ie.evalos.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The database failing, as the probes see it: readiness goes DOWN (503 — take this instance out of
 * rotation) while liveness stays UP (200 — do not restart it, a restart cannot fix Postgres).
 *
 * <p>The failure is simulated by replacing Boot's {@code db} indicator under its own bean name, because
 * the application cannot start without a database to migrate. What is under test is the probe wiring in
 * application.yml; Boot's real indicator reporting UP against Postgres is {@link ActuatorEndpointsTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
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
		// The suite boots the `local` profile, which shows health detail to anyone; pin the deployed rule.
		"management.endpoint.health.show-details=when-authorized",
		"management.endpoint.health.show-components=when-authorized",
})
class ActuatorDatabaseDownTest {

	@TestConfiguration
	static class DatabaseDown {
		/** Named as Boot's own, so Boot's indicator backs off and this one reports as {@code db}. */
		@Bean
		HealthIndicator dbHealthContributor() {
			return () -> Health.down().withDetail("error", "simulated connection refused").build();
		}
	}

	@Autowired
	MockMvc mvc;

	@Test
	void readinessFailsSoTrafficStopsButLivenessHoldsSoTheInstanceIsNotRestarted() throws Exception {
		mvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"));
		mvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void theAggregateSaysDownWithoutTellingAnAnonymousCallerWhy() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"))
				.andExpect(content().string(not(containsString("simulated"))));
	}
}
