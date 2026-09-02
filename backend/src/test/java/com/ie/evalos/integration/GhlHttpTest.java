package com.ie.evalos.integration;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two guarantees {@link GhlHttp} exists to hold, both of which are about what happens when a
 * <em>second</em> client shows up.
 */
class GhlHttpTest {

	private static GhlHttp configured() {
		// Nothing is actually sent: every test here either paces before failing to connect, or
		// reflects over the class. The port is closed on purpose, so a test that accidentally starts
		// making real requests fails instead of quietly reaching the internet.
		return new GhlHttp("http://127.0.0.1:1", "2021-07-28", "pit-test-token", "loc_test",
				Duration.ofMillis(200));
	}

	/**
	 * <strong>The test this class was extracted for.</strong>
	 *
	 * <p>GHL's rate limit is 100 requests per 10 seconds <em>per location</em>. The pacer used to
	 * live on {@code GhlPipelineClient}, which was correct while it was the only client; a second
	 * client bean would have meant two pacers governing one location — each correctly under the
	 * limit and the pair reliably over it.
	 *
	 * <p>So the assertion is deliberately about the <em>aggregate</em>: two clients sharing one
	 * {@code GhlHttp} must space their requests as one stream. There is one client class again now
	 * that the sales desk is gone, so the test uses two instances of it — the property under test
	 * was never about *which* clients, only that the pacer belongs to the location and not to
	 * whoever is reading. It fails if a client ever reacquires a pacer of its own, which is the
	 * regression that would otherwise stay invisible until GHL began refusing traffic.
	 */
	@Test
	@DisplayName("two clients sharing one GhlHttp are paced as one stream, not two")
	void pacerIsSharedAcrossClients() {
		GhlHttp http = configured();
		GhlPipelineClient reader = new GhlPipelineClient(http);
		GhlPipelineClient second = new GhlPipelineClient(http);

		Instant start = Instant.now();
		// Six calls alternating between the two clients. Each throws (the port is closed) *after*
		// pacing, which is what makes the elapsed time the thing under test.
		for (int i = 0; i < 3; i++) {
			attempt(() -> reader.pipelineNamed("anything"));
			attempt(() -> second.pipelineNamed("anything else"));
		}
		Duration elapsed = Duration.between(start, Instant.now());

		// Six paced requests are five intervals. With a per-client pacer each client would see only
		// three of its own and the pair would finish in roughly half this.
		Duration floor = GhlHttp.MIN_REQUEST_INTERVAL.multipliedBy(5);
		assertThat(elapsed)
				.describedAs("six requests across two clients must be spaced by the shared pacer; "
						+ "a per-client pacer would let the pair go at twice GHL's per-location rate")
				.isGreaterThanOrEqualTo(floor);
	}

	/**
	 * <strong>EvalOS reads GHL and writes nothing back.</strong>
	 *
	 * <p>Widened from "no delete" when the sales desk was removed: {@code post} and {@code put}
	 * existed only for it and went with it, so the whole write capability is now absent rather
	 * than present-and-unused. That is the stronger position and the one this repo held before
	 * Unit 29 — but it is still <em>not</em> backed by the credential, which remains
	 * {@code opportunities.write} + {@code contacts.write}. Code is the only thing holding the
	 * line, so the line is a test.
	 *
	 * <p>Checked on {@code GhlHttp} rather than only on the callers: an absent capability cannot be
	 * reached by accident from anywhere, where a caller-by-caller check would pass until somebody
	 * adds a caller.
	 */
	@Test
	@DisplayName("GhlHttp exposes no write verb — the capability is absent, not merely unused")
	void thereIsNoWriteMethod() {
		List<String> verbs = Stream.of(GhlHttp.class.getDeclaredMethods())
				.map(Method::getName)
				.toList();

		assertThat(verbs)
				.describedAs("EvalOS reads GHL and writes nothing back. Adding a write verb here is a "
						+ "decision that needs its own sign-off — it was one, for the sales desk, and "
						+ "it left with it")
				.doesNotContain("post", "put", "patch", "delete", "remove");
		// The positive half, so the test says what the class IS for and fails if the read goes missing.
		assertThat(verbs).contains("get");
	}

	/**
	 * An unconfigured environment answers 502 rather than starting up broken or reaching a
	 * half-configured GHL — unchanged behaviour, re-asserted here because the check moved classes.
	 */
	@Test
	@DisplayName("an unconfigured environment fails as unavailable, naming both variables")
	void unconfiguredIsUnavailable() {
		GhlHttp http = new GhlHttp("http://127.0.0.1:1", "2021-07-28", "", "", Duration.ofMillis(200));

		assertThat(http.isConfigured()).isFalse();
		assertThatThrownBy(() -> http.get(String.class, (uri) -> uri.path("/anything").build()))
				.isInstanceOf(GhlUnavailableException.class)
				.hasMessageContaining("GHL_API_TOKEN")
				.hasMessageContaining("GHL_LOCATION_ID");
	}

	/**
	 * Runs a call and swallows the failure, because the failure is not what is under test.
	 *
	 * <p>Every request in {@link #pacerIsSharedAcrossClients} is aimed at a closed port and throws
	 * — but it throws <em>after</em> {@code pace()} has already slept, which is what makes the
	 * elapsed time measure the pacer rather than the network.
	 */
	private static void attempt(Runnable call) {
		try {
			call.run();
		}
		catch (RuntimeException expected) {
			// The port is closed on purpose. See above.
		}
	}
}
