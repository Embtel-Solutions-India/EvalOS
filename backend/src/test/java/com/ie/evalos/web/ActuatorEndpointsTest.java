package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator over the real application and a real PostgreSQL: what an anonymous probe sees, what an
 * ordinary staff role sees (nothing), what the Admin sees, and that the diagnostics that leak the most
 * are not there for anybody.
 *
 * <p>Same gate, schema and Flyway trees as {@code LocalPostgresIntegrationTest}; the seeded local
 * Admin and Case Manager are the callers.
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
class ActuatorEndpointsTest {

	private static final String UUID_PATTERN = ".*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TeamMemberRepository members;

	@Autowired
	JwtService jwt;

	private String bearer(String role) {
		UUID id = jdbc.queryForObject(
				"select id from team_member where role = ? and active order by created_at limit 1", UUID.class, role);
		return "Bearer " + jwt.issue(StaffPrincipal.of(members.findById(id).orElseThrow(), List.of()));
	}

	@Test
	void probesAndTheAggregateAnswerAnonymouslyWithAStatusAndNothingElse() throws Exception {
		for (String path : new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
			mvc.perform(get(path))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("UP"))
					.andExpect(jsonPath("$.components").doesNotExist())
					.andExpect(jsonPath("$.details").doesNotExist());
		}
	}

	@Test
	void theAdminSeesTheDatabaseAndOnlyReadinessDependsOnIt() throws Exception {
		String admin = bearer("ADMIN");

		mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.db.status").value("UP"))
				.andExpect(jsonPath("$.components.db.details.database").value("PostgreSQL"))
				// Mail degrades by design and must never drag the aggregate down (application.yml).
				.andExpect(jsonPath("$.components.mail").doesNotExist());
		mvc.perform(get("/actuator/health/readiness").header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(jsonPath("$.components.db.status").value("UP"))
				.andExpect(jsonPath("$.components.readinessState.status").value("UP"));
		// Boot's own liveness group: its state only, no components even for the Admin. That it ignores
		// the database is proved by ActuatorDatabaseDownTest.
		mvc.perform(get("/actuator/health/liveness").header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components").doesNotExist());
	}

	@Test
	void metricsAndInfoAreTheAdminsAloneNotAnyOrdinaryRole() throws Exception {
		for (String path : new String[] {"/actuator", "/actuator/metrics", "/actuator/info"}) {
			mvc.perform(get(path)).andExpect(status().isUnauthorized());
			for (String role : new String[] {"CASE_MANAGER", "GM", "BRAND_MANAGER"}) {
				mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(role)))
						.andExpect(status().isForbidden());
			}
			mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))).andExpect(status().isOk());
		}
	}

	/** The meters the runbook reads: memory, GC, threads, CPU, uptime, the pool, HTTP and log errors. */
	@Test
	void theAdminReadsTheProductionMeters() throws Exception {
		String admin = bearer("ADMIN");
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isOk());

		for (String meter : new String[] {"jvm.memory.used?tag=area:heap", "jvm.memory.used?tag=area:nonheap",
				"jvm.memory.max", "jvm.gc.memory.allocated", "jvm.gc.max.data.size", "jvm.threads.live",
				"jvm.threads.peak", "process.cpu.usage", "system.cpu.count", "process.uptime", "process.start.time",
				"hikaricp.connections.active", "hikaricp.connections.idle", "hikaricp.connections.pending",
				"hikaricp.connections.max", "http.server.requests", "http.server.requests?tag=status:200",
				"logback.events?tag=level:error"}) {
			mvc.perform(get("/actuator/metrics/" + meter).header(HttpHeaders.AUTHORIZATION, admin))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.measurements").isNotEmpty());
		}
	}

	/** Spring tags the route template, so request volume can never mint one series per case id. */
	@Test
	void httpMetricsAreTaggedByRouteTemplateNeverById() throws Exception {
		String admin = bearer("ADMIN");
		UUID memberId = jdbc.queryForObject(
				"select id from team_member where role = 'ADMIN' and active order by created_at limit 1", UUID.class);
		mvc.perform(get("/api/team-members/" + memberId).header(HttpHeaders.AUTHORIZATION, admin));

		String body = mvc.perform(get("/actuator/metrics/http.server.requests").header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> uris = JsonPath.read(body, "$.availableTags[?(@.tag == 'uri')].values[*]");

		assertThat(uris).isNotEmpty().noneMatch(uri -> uri.matches(UUID_PATTERN));
	}

	@Test
	void aRefusedSignInIsCountedWithoutAnyIdentifyingTag() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"nobody-%s@evalos.invalid\",\"password\":\"wrong\"}".formatted(UUID.randomUUID())))
				.andExpect(status().isUnauthorized());

		mvc.perform(get("/actuator/metrics/evalos.auth.login?tag=outcome:refused")
				.header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.measurements[0].value").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1.0)))
				// Only the global application tag remains — no email, no member, no address.
				.andExpect(jsonPath("$.availableTags[*].tag").value(org.hamcrest.Matchers.contains("application")));
	}

	/** The System health page's read: the Admin's alone, and every section filled from this process and its database. */
	@Test
	void theSystemHealthReportIsTheAdminsAndCarriesEverySection() throws Exception {
		mvc.perform(get("/api/system/health")).andExpect(status().isUnauthorized());
		for (String role : new String[] {"CASE_MANAGER", "GM", "BRAND_MANAGER"}) {
			mvc.perform(get("/api/system/health").header(HttpHeaders.AUTHORIZATION, bearer(role)))
					.andExpect(status().isForbidden());
		}
		String admin = bearer("ADMIN");
		mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isOk());

		mvc.perform(get("/api/system/health").header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status.overall").value("UP"))
				.andExpect(jsonPath("$.data.status.readiness").value("ACCEPTING_TRAFFIC"))
				.andExpect(jsonPath("$.data.status.liveness").value("CORRECT"))
				.andExpect(jsonPath("$.data.status.components[?(@.name == 'db')].status").value(org.hamcrest.Matchers.contains("UP")))
				.andExpect(jsonPath("$.data.database.reachable").value(true))
				.andExpect(jsonPath("$.data.database.roundTripMs").isNumber())
				.andExpect(jsonPath("$.data.database.serverVersion").isString())
				.andExpect(jsonPath("$.data.database.sizeBytes").isNumber())
				.andExpect(jsonPath("$.data.database.schemaVersion").isString())
				.andExpect(jsonPath("$.data.database.diskTotalBytes").isNumber())
				.andExpect(jsonPath("$.data.memory.heapUsed").isNumber())
				.andExpect(jsonPath("$.data.memory.heapMax").isNumber())
				.andExpect(jsonPath("$.data.memory.pools").isNotEmpty())
				.andExpect(jsonPath("$.data.threads.live").isNumber())
				.andExpect(jsonPath("$.data.threads.states.runnable").isNumber())
				.andExpect(jsonPath("$.data.cpu.processors").isNumber())
				.andExpect(jsonPath("$.data.pool.max").isNumber())
				.andExpect(jsonPath("$.data.pool.active").isNumber())
				.andExpect(jsonPath("$.data.http.requests").value(org.hamcrest.Matchers.greaterThan(0)))
				.andExpect(jsonPath("$.data.http.routes[?(@.uri == '/api/me')].count").isNotEmpty())
				.andExpect(jsonPath("$.data.logs.byLevel.error").isNumber())
				.andExpect(jsonPath("$.data.runtime.javaVersion").isString())
				.andExpect(jsonPath("$.data.runtime.uptimeMs").isNumber())
				// Nothing that names a person or a client: ids and route templates only.
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("@evalos.local"))));
	}

	/** Configuration, beans, heap and thread dumps, and runtime log levels are off — not merely hidden. */
	@Test
	void sensitiveDiagnosticsAreNotThereEvenForTheAdmin() throws Exception {
		String admin = bearer("ADMIN");
		for (String endpoint : new String[] {"env", "configprops", "beans", "mappings", "heapdump", "threaddump",
				"loggers", "scheduledtasks", "conditions", "caches", "flyway", "shutdown", "sbom", "startup"}) {
			mvc.perform(get("/actuator/" + endpoint).header(HttpHeaders.AUTHORIZATION, admin))
					.andExpect(status().isNotFound());
			mvc.perform(get("/actuator/" + endpoint)).andExpect(status().isUnauthorized());
		}
		// No write reaches Actuator at all, so no log level can be changed at runtime by anybody.
		mvc.perform(post("/actuator/loggers/com.ie.evalos").header(HttpHeaders.AUTHORIZATION, admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"configuredLevel\":\"TRACE\"}"))
				.andExpect(status().isForbidden());
	}
}
