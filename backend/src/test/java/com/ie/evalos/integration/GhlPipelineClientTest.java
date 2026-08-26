package com.ie.evalos.integration;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The GHL read client's two boot-time properties: that it <em>does</em> start without a token,
 * and that the property names it asks for are the ones {@code application.yml} actually declares.
 *
 * <p>The second half is the one worth explaining. {@code GhlPipelineClient} is a
 * {@code @Component} whose {@code base-url}, {@code api-version} and {@code timeout} values have
 * <strong>no defaults</strong> — a typo in any of those keys is an unresolvable placeholder and
 * therefore a <em>boot failure</em>. And nothing in a normal {@code ./mvnw verify} would catch it:
 * every web test is a {@code @WebMvcTest} slice that never instantiates this bean, and the only
 * full-context test is gated behind {@code -Devalos.db.test=true}. That is exactly the gap
 * {@code mem:backend/core} already records for {@code GoogleDriveConfig} ("a typo here is
 * invisible to verify alone"), so this closes it rather than repeating it.
 *
 * <p>Done with {@link ApplicationContextRunner} rather than {@code @SpringBootTest}: the question
 * is only whether these placeholders resolve against the real configuration, and
 * {@link ConfigDataApplicationContextInitializer} loads {@code application.yml} without a
 * database, a web server, or several seconds of autoconfiguration.
 */
class GhlPipelineClientTest {

	/** A read has to be attempted for the unconfigured guard to fire — construction never throws. */
	private static void attemptRead(GhlPipelineClient client) {
		client.pipelineNamed("Google ADS Pipeline");
	}

	private static GhlPipelineClient client(String token, String locationId) {
		return new GhlPipelineClient("https://services.leadconnectorhq.com", "2021-07-28", token, locationId,
				Duration.ofSeconds(10));
	}

	/**
	 * <strong>A missing token must not fail the boot, and that is the deliberate difference from
	 * Drive.</strong> {@code GoogleDriveConfig} throws from its constructor because signing or
	 * storing without its key is unrecoverable; this gates one read-only GM screen, so the app
	 * serves every other route and answers 502 on that one view.
	 */
	@Test
	void anUnconfiguredEnvironmentStillBuildsTheClient() {
		assertThatCode(() -> client("", "")).doesNotThrowAnyException();
	}

	@Test
	void anUnconfiguredReadIsAStated502NamingBothVariables() {
		// Either blank is enough to be unusable: a token with no location cannot address a
		// sub-account, and a location with no token cannot authenticate.
		for (GhlPipelineClient unusable : new GhlPipelineClient[] { client("", ""), client("pit-abc", ""),
				client("", "loc-1") }) {
			assertThatThrownBy(() -> attemptRead(unusable))
					.isInstanceOf(GhlUnavailableException.class)
					// Whoever reads this is provisioning an environment and needs to know which
					// variables to set. Neither name is a secret.
					.hasMessageContaining("GHL_API_TOKEN")
					.hasMessageContaining("GHL_LOCATION_ID");
		}
	}

	/** The token's value must never reach a message — it is a header, not something we echo. */
	@Test
	void theTokenValueNeverAppearsInAFailureMessage() {
		assertThatThrownBy(() -> attemptRead(client("pit-super-secret-value", "")))
				.hasMessageNotContaining("pit-super-secret-value");
	}

	/**
	 * The keys in {@code application.yml} are the keys this bean asks for.
	 *
	 * <p>Fails on a typo in either place, which is the whole point: rename
	 * {@code evalos.ghl.base-url} in the yaml or in the {@code @Value} and this test stops the
	 * build instead of a deployment stopping the app.
	 */
	@Test
	void everyPropertyItAsksForResolvesFromTheRealConfiguration() {
		new ApplicationContextRunner()
				.withInitializer(new ConfigDataApplicationContextInitializer())
				// **Pinned empty, and this is a bug fix rather than tidiness.** Without it the
				// token comes from whatever the developer happens to have exported, the client
				// considers itself configured, and `unconfiguredByDefault` below makes a REAL
				// call to GHL — which failed with a live 401 and reported it as a broken test.
				// A unit test must not depend on ambient environment and must not reach the
				// network; the question here is only whether the placeholders resolve.
				.withPropertyValues("evalos.ghl.token=")
				// `10s` -> Duration is a LENIENT conversion, and it needs Boot's
				// ApplicationConversionService. A real boot installs that itself (SpringApplication
				// does it), which is why `evalos.drive.timeout` has always bound in this app; a bare
				// ApplicationContextRunner does not, so without this line the harness fails on
				// something production does correctly. **Keep it** — removing it does not find a
				// bug, it invents one.
				.withInitializer((context) -> context.getBeanFactory()
						.setConversionService(ApplicationConversionService.getSharedInstance()))
				// Jackson is needed because RestClient's message converters are built from the
				// context's ObjectMapper. Nothing else from the app is loaded.
				.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
				.withUserConfiguration(GhlPipelineClient.class)
				.run((context) -> {
					// No unresolvable placeholder, no missing key, no wrong type. `timeout` is the
					// one most likely to break quietly: it binds to a Duration, so `10s` in the
					// yaml has to stay a value Spring can convert.
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(GhlPipelineClient.class);
					unconfiguredByDefault(context);
				});
	}

	/**
	 * And with no {@code GHL_API_TOKEN} in the environment, the bean built from that same real
	 * configuration is the unconfigured one — so the documented default really is "serves the app,
	 * 502s this screen" rather than something only the comments claim.
	 */
	private static void unconfiguredByDefault(AssertableApplicationContext context) {
		assertThatThrownBy(() -> attemptRead(context.getBean(GhlPipelineClient.class)))
				.isInstanceOf(GhlUnavailableException.class)
				.hasMessageContaining("not configured");
	}

	/**
	 * The same check against {@code application-prod.yml}.
	 *
	 * <p><strong>Nothing else in the suite loads that file.</strong> Every other test runs on the
	 * default profile ({@code local}), so a malformed prod profile was invisible until a deployment
	 * tried it — the worst place to find one.
	 *
	 * <p><strong>Be precise about what this does and does not catch.</strong> It catches a YAML
	 * syntax error, and it catches a missing key that has no default. It does <em>not</em> catch a
	 * <em>renamed</em> {@code evalos.ghl.*} key, because all three that prod restates carry a
	 * default in the placeholder — a typo there falls back silently rather than failing. Closing
	 * that would mean asserting the resolved values, which would pin prod's contents to a test and
	 * make every legitimate deployment change a test edit. Verified by mutation: breaking the
	 * file's indentation does fail this test.
	 *
	 * <p>Prod's own convention is that nothing has a default, so the four keys it deliberately
	 * makes fatal are supplied here as throwaways. That is not weakening the test: their absence
	 * failing the boot is asserted by the fact that they must be passed at all.
	 */
	@Test
	void theProdProfileBindsToo() {
		new ApplicationContextRunner()
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.withInitializer((context) -> context.getBeanFactory()
						.setConversionService(ApplicationConversionService.getSharedInstance()))
				.withPropertyValues(
						"spring.profiles.active=prod",
						// Prod's no-default keys. Values are irrelevant — only that they resolve.
						"DB_URL=jdbc:postgresql://localhost:5432/unused",
						"DB_USER=unused",
						"DB_PASSWORD=unused",
						"PORTAL_BASE_URL=https://portal.invalid",
						// Same reason as the local runner above: never inherit a real token.
						"evalos.ghl.token=")
				.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
				.withUserConfiguration(GhlPipelineClient.class)
				.run((context) -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(GhlPipelineClient.class);
					// Prod keeps token and location empty on purpose — one read-only screen is not
					// worth failing a boot over. That decision is asserted, not just commented.
					unconfiguredByDefault(context);
				});
	}
}
