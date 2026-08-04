package com.ie.evalos.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The acceptance criterion that is about <em>not</em> starting.
 *
 * <p>An app that boots without Drive credentials, serves every other route, and then fails at
 * the first upload fails it in front of a client waiting for the profile. So the check is at
 * construction, and it is asserted here rather than left to a deployment to discover — which is
 * the same reason {@code JwtService} refuses a short signing key in its own constructor.
 *
 * <p>Tested on the constructor rather than by booting a context per profile: the rule is
 * "required and unconfigured is fatal", and a full {@code @SpringBootTest} per case would prove
 * the same predicate through several seconds of autoconfiguration and a database connection.
 */
class GoogleDriveConfigTest {

	private static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	private static final String KEY_JSON = "{\"type\":\"service_account\"}";

	@Test
	void requiredAndUnconfiguredRefusesToStartAndNamesBothVariables() {
		assertThatThrownBy(() -> new GoogleDriveConfig("", "", true, SCOPE, TIMEOUT))
				.isInstanceOf(IllegalStateException.class)
				// Whoever reads this is provisioning an environment and needs to know which
				// variable to set. Neither name is a secret.
				.hasMessageContaining("GOOGLE_DRIVE_KEY_JSON")
				.hasMessageContaining("GOOGLE_APPLICATION_CREDENTIALS");
	}

	/** Either source satisfies it — a container secret and a mounted volume take different shapes. */
	@Test
	void eitherCredentialSourceSatisfiesTheRequirement() {
		assertThatCode(() -> new GoogleDriveConfig(KEY_JSON, "", true, SCOPE, TIMEOUT))
				.doesNotThrowAnyException();
		assertThatCode(() -> new GoogleDriveConfig("", "/run/secrets/drive.json", true, SCOPE, TIMEOUT))
				.doesNotThrowAnyException();
	}

	/**
	 * The {@code local} profile is the only one allowed to run without a key, so a laptop with no
	 * service account still starts the whole app. Only the Drive write is affected, and it says
	 * so — see {@code GoogleDriveClient}, whose null-Drive guard turns this into a stated 502.
	 */
	@Test
	void localMayRunWithNoCredentialsAndStillBuildsAClient() {
		GoogleDriveConfig local = new GoogleDriveConfig("", "", false, SCOPE, TIMEOUT);

		assertThat(local.googleDriveClient()).isNotNull();
	}
}
