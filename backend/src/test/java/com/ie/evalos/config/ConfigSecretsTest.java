package com.ie.evalos.config;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No credential may carry a default in any committed configuration file.
 *
 * <p><strong>This exists because it happened twice.</strong> First a live GHL Private Integration
 * Token was pasted into {@code application.yml} as the fallback in {@code ${GHL_API_TOKEN:…}}.
 * Then — after the first version of this test was written — the same token was pasted into
 * {@code application-local.yml}, and <em>this test did not catch it</em>. Both files are tracked
 * by git, the app works either way, and a placeholder default is the least conspicuous place a
 * secret can sit.
 *
 * <p><strong>Why the first version missed it, because the lesson is the point.</strong> It exempted
 * the <em>whole</em> local profile — reasoning that local legitimately carries throwaway
 * {@code JWT_SECRET} and {@code EVALOS_FIELD_KEY} values — and backstopped that with a check for
 * {@code "GHL_API_TOKEN:pit"}. The real paste had the {@code pit-} prefix stripped, so it was a
 * bare UUID and matched nothing. Two mistakes, one shape: an exemption granted per <em>file</em>
 * when the thing being exempted is a per-<em>setting</em> decision, and a guard that pattern-matched
 * the value instead of asserting the rule.
 *
 * <p><strong>The rule now: every profile is scanned, and the allowlist names exact settings.</strong>
 * {@link #DEV_THROWAWAYS} is three entries, each documented in the local profile as
 * not-for-deployment and none of which reaches anything off the laptop. Everything else credential-shaped must resolve from the environment. Adding
 * to that list should feel like a decision, which is what naming a variable in a test does and
 * what exempting a file does not.
 *
 * <p>Non-secret defaults stay allowed and must: a base URL, an API version, a timeout, a pipeline
 * name, a GHL <em>location id</em>. A location id appears in every GHL URL and grants nothing on
 * its own — the token is the credential.
 */
class ConfigSecretsTest {

	/** A placeholder with a non-empty default: <code>${NAME:value}</code>. */
	private static final Pattern DEFAULTED = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*):([^}\\n]+)}");

	/**
	 * What counts as a credential, by name.
	 *
	 * <p>By <em>name</em>, never by value shape — that is the mistake this file already made once.
	 * Guessing from the value ("looks base64", "longer than 30 chars") misses a short secret and a
	 * prefix-stripped one, and trips over legitimate long strings like a scope URL. The name states
	 * the intent of the setting, which is the thing being protected. {@code KEY} catches
	 * {@code GOOGLE_DRIVE_KEY_JSON}, which is correct — that is a service-account private key.
	 */
	private static final Pattern SECRET_NAME = Pattern.compile(".*(TOKEN|SECRET|PASSWORD|KEY|CREDENTIAL).*");

	/**
	 * The only credential defaults allowed to exist, and only in the local profile.
	 *
	 * <p>Both are documented in {@code application-local.yml} as throwaways that let the app start
	 * on a laptop, and neither reaches anything outside it. A GHL token is deliberately <b>not</b>
	 * here: it authenticates to a live third-party account holding customer data, so there is no
	 * dev-safe value for it in any file.
	 */
	private static final Set<String> DEV_THROWAWAYS = Set.of("JWT_SECRET", "EVALOS_FIELD_KEY",
			// The stock localhost Postgres password the local profile documents, so the app runs
			// on a laptop with no setup. It reaches nothing but that laptop's own database.
			"DB_PASSWORD");

	private static final String LOCAL_PROFILE = "application-local.yml";

	@ParameterizedTest
	@ValueSource(strings = { "application.yml", LOCAL_PROFILE, "application-prod.yml" })
	void noCredentialCarriesADefault(String profile) throws IOException {
		List<String> offenders = new ArrayList<>();

		for (String line : Files.readAllLines(resource(profile), StandardCharsets.UTF_8)) {
			// These placeholders are discussed constantly in comments; only settings are checked.
			if (line.stripLeading().startsWith("#")) {
				continue;
			}
			Matcher match = DEFAULTED.matcher(line);
			while (match.find()) {
				String name = match.group(1);
				if (!SECRET_NAME.matcher(name).matches()) {
					continue;
				}
				if (LOCAL_PROFILE.equals(profile) && DEV_THROWAWAYS.contains(name)) {
					continue;
				}
				// The variable name only — never the value. A failure message quoting the default
				// would copy the secret into CI logs, which is the same leak one step removed.
				offenders.add(profile + " -> " + name);
			}
		}

		assertThat(offenders)
				.describedAs("A credential must never have a fallback in a committed file. Every "
						+ "profile here is tracked by git and the app runs with or without the "
						+ "value, so nothing else catches it. Use ${NAME:} and supply it from the "
						+ "environment. Only " + DEV_THROWAWAYS + " may be defaulted, in "
						+ LOCAL_PROFILE + " alone.")
				.isEmpty();
	}

	/**
	 * The exemption is real and narrow, asserted so it is not mistaken for an oversight: the local
	 * profile does carry these defaults on purpose. If they are ever removed, the allowlist
	 * above is dead and should go with them.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "JWT_SECRET", "EVALOS_FIELD_KEY", "DB_PASSWORD" })
	void theAllowlistedThrowawaysReallyAreStillThere(String name) throws IOException {
		assertThat(Files.readString(resource(LOCAL_PROFILE), StandardCharsets.UTF_8))
				.describedAs(name + " is allowlisted above; if it no longer has a local default, "
						+ "shrink the allowlist rather than leaving a dead exemption.")
				.contains("${" + name + ":");
	}

	private static Path resource(String name) {
		URL found = ConfigSecretsTest.class.getClassLoader().getResource(name);
		assertThat(found).describedAs(name + " is not on the test classpath").isNotNull();
		return Path.of(found.getPath().replaceFirst("^/(?=[A-Za-z]:)", ""));
	}
}
