package com.ie.evalos.security;

import java.time.Duration;
import java.time.Instant;

import com.ie.evalos.service.PortalAccessService;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The portal chain's rate limit, tested as what it is — a per-caller counter in a fixed window —
 * rather than through sixty HTTP requests.
 */
class PortalTokenFilterTest {

	private final PortalTokenFilter filter = new PortalTokenFilter(
			mock(PortalAccessService.class), mock(com.ie.evalos.common.ApiErrors.class), 3);

	@Test
	void aCallerIsCutOffAfterItsAllowanceAndOthersAreNot() {
		Instant now = Instant.parse("2026-08-05T10:00:30Z");

		assertThat(filter.overLimit("10.0.0.1", now)).isFalse();
		assertThat(filter.overLimit("10.0.0.1", now)).isFalse();
		assertThat(filter.overLimit("10.0.0.1", now)).isFalse();
		assertThat(filter.overLimit("10.0.0.1", now)).as("the fourth is over the allowance").isTrue();

		// Per caller, not global: one client hammering the page must not lock out another's link.
		assertThat(filter.overLimit("10.0.0.2", now)).isFalse();
	}

	@Test
	void theWindowRollsAndTheAllowanceComesBack() {
		Instant now = Instant.parse("2026-08-05T10:00:30Z");
		for (int attempt = 0; attempt < 4; attempt++) {
			filter.overLimit("10.0.0.1", now);
		}

		// Same minute, still refused; the next minute starts clean. A refusal that outlived its
		// window would lock a client out of their own draft for as long as the process lives.
		assertThat(filter.overLimit("10.0.0.1", now.plus(Duration.ofSeconds(20)))).isTrue();
		assertThat(filter.overLimit("10.0.0.1", now.plus(Duration.ofSeconds(40)))).isFalse();
	}
}
