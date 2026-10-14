package com.ie.evalos.config;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An unset variable reaches Flyway as its own `${...}` text or as blank; either stops the boot. */
class FlywayPlaceholderGuardTest {

	@Test
	void anUnresolvedOrBlankPlaceholderStopsTheBootByKeyNotValue() {
		assertThatThrownBy(() -> FlywayPlaceholderGuard.check(Map.of("admin-email", "${ADMIN_EMAIL}")))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("admin-email")
				.hasMessageNotContaining("${ADMIN_EMAIL}");
		assertThatThrownBy(() -> FlywayPlaceholderGuard.check(Map.of("admin-password-hash", " ")))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void realValuesPass() {
		assertThatCode(() -> FlywayPlaceholderGuard.check(Map.of("admin-email", "admin@example.com",
				"admin-password-hash", "$2a$10$abcdefghijklmnopqrstuv"))).doesNotThrowAnyException();
	}
}
