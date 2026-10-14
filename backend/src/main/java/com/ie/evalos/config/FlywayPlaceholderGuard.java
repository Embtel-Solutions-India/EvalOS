package com.ie.evalos.config;

import java.util.Map;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.stereotype.Component;

import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Refuses to migrate when a Flyway placeholder has no real value.
 *
 * <p><strong>"No default, so it fails to migrate" was not true.</strong> Spring Boot binds
 * {@code spring.flyway.placeholders.*} leaving an unresolvable {@code ${ADMIN_EMAIL}} as that literal text, and
 * Flyway then seeded it: production's first Admin was email {@code ${ADMIN_EMAIL}}, hash
 * {@code ${ADMIN_PASSWORD_HASH}} (2026-10-14). A webhook token seeded the same way would be a public, guessable
 * credential. So every placeholder is checked here, before any migration runs, and the boot stops naming the key —
 * never the value, which is a secret when it is right.
 */
@Component
class FlywayPlaceholderGuard implements FlywayConfigurationCustomizer {

	@Override
	public void customize(FluentConfiguration configuration) {
		check(configuration.getPlaceholders());
	}

	static void check(Map<String, String> placeholders) {
		for (Map.Entry<String, String> entry : placeholders.entrySet()) {
			String value = entry.getValue();
			if (value == null || value.isBlank() || value.contains("${")) {
				throw new IllegalStateException("Flyway placeholder '" + entry.getKey()
						+ "' has no value: set its environment variable before starting (see application-prod.yml).");
			}
		}
	}
}
