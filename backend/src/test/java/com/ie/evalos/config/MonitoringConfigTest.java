package com.ie.evalos.config;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Actuator rules live in the base profile only, and the deployed profiles never widen them.
 *
 * <p>A deployed profile that exposed {@code env} or set {@code show-details: always} would publish
 * configuration and dependency detail to anyone who can reach {@code /actuator/health}, and no request
 * test would notice, because the test suite never boots {@code prod}. So the files themselves are read.
 */
class MonitoringConfigTest {

	private static Properties load(String file) {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource(file));
		return yaml.getObject();
	}

	@Test
	void theBaseProfileSwitchesEverythingOffAndExposesThreeEndpoints() {
		Properties base = load("application.yml");

		assertThat(base.getProperty("management.endpoints.access.default")).isEqualTo("none");
		assertThat(base.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info,metrics");
		assertThat(base.getProperty("management.endpoint.health.show-details")).isEqualTo("when-authorized");
		assertThat(base.getProperty("management.endpoint.health.roles")).isEqualTo("ADMIN");
		// Readiness waits on the database; liveness is left at Boot's default (its own state only).
		assertThat(base.getProperty("management.endpoint.health.group.readiness.include")).isEqualTo("readinessState,db");
		assertThat(base.stringPropertyNames()).noneMatch(key -> key.startsWith("management.endpoint.health.group.liveness"));
		assertThat(base.getProperty("spring.datasource.hikari.data-source-properties.logServerErrorDetail")).isEqualTo("false");
	}

	@ParameterizedTest
	@ValueSource(strings = { "application-prod.yml", "application-testprod.yml" })
	void aDeployedProfileNeverWidensActuatorOrLogsAtDebug(String file) {
		Properties profile = load(file);

		assertThat(profile.stringPropertyNames()).noneMatch(key -> key.startsWith("management."));
		assertThat(profile.stringPropertyNames().stream()
				.filter(key -> key.startsWith("logging.level."))
				.map(profile::getProperty))
				.noneMatch(level -> level.equalsIgnoreCase("DEBUG") || level.equalsIgnoreCase("TRACE"));
	}

	@Test
	void theLaptopProfileShowsHealthDetailButExposesNothingMore() {
		Properties local = load("application-local.yml");

		assertThat(local.getProperty("management.endpoint.health.show-details")).isEqualTo("always");
		assertThat(local.stringPropertyNames()).noneMatch(key -> key.startsWith("management.endpoints."));
	}
}
