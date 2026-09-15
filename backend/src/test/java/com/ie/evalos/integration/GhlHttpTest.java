package com.ie.evalos.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@link GhlHttp} guarantees now that it writes.
 *
 * <p>Two of these are about what happens when a <em>second</em> client shows up; the other two
 * replaced the "no write verb" assertion that Unit 37 deliberately removed. Read
 * {@link #theVerbListIsClosed} and {@link #writeCallersAudit} together — the old guard is gone,
 * and these are what was put in its place rather than what was left behind.
 */
class GhlHttpTest {

	/** The HTTP verbs a caller could plausibly reach for. The class must expose exactly four. */
	private static final Set<String> HTTP_VERBS =
			Set.of("get", "post", "put", "patch", "delete", "head", "options", "remove");

	/** A class that holds the door: {@code GhlHttp http} as a field or a constructor parameter. */
	private static final Pattern HOLDS_GHL_HTTP = Pattern.compile("\\bGhlHttp\\s+\\w+");

	/** A call to one of the three write verbs. */
	private static final Pattern CALLS_WRITE_VERB = Pattern.compile("\\.(post|put|delete)\\s*\\(");

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
	 * <strong>The verb list is closed.</strong>
	 *
	 * <p>This test replaced "GhlHttp exposes no write verb", which was the guarantee until Unit
	 * 37. That assertion is gone because the position it defended is gone: Sales and Marketing
	 * work their opportunities from EvalOS now, and those writes go through this class.
	 *
	 * <p><strong>What is asserted instead is that adding the <em>next</em> verb is still a
	 * decision.</strong> The old test's real value was never "zero writes" — it was that no
	 * capability reaches GHL without somebody signing off on it. Pinning the exact set keeps
	 * that: a {@code patch} added because an endpoint looked like it wanted one fails the build
	 * and has to be argued for, exactly as {@code post} was.
	 *
	 * <p>Checked on {@code GhlHttp} rather than on its callers, for the same reason as before: a
	 * capability that is not here cannot be reached by accident from anywhere, whereas a
	 * caller-by-caller check passes until somebody adds a caller.
	 */
	@Test
	@DisplayName("GhlHttp exposes exactly get, post, put and delete — a fifth verb is a decision")
	void theVerbListIsClosed() {
		List<String> verbs = Stream.of(GhlHttp.class.getDeclaredMethods())
				.filter((method) -> Modifier.isPublic(method.getModifiers()))
				.map(Method::getName)
				.filter(HTTP_VERBS::contains)
				.distinct()
				.sorted()
				.toList();

		assertThat(verbs)
				.describedAs("Unit 37 opened this door deliberately and closed the list behind it. "
						+ "Adding a verb is a decision that needs its own sign-off — patch in "
						+ "particular is refused because GHL's API does not use it, and a verb no "
						+ "endpoint accepts is a capability nothing calls.")
				.containsExactly("delete", "get", "post", "put");
	}

	/**
	 * <strong>Every caller of a write verb writes an audit row.</strong>
	 *
	 * <p>This is the half of the replacement that carries the weight. Invariant 13 says every
	 * state transition writes an append-only audit entry, and moving the sales desk into EvalOS
	 * creates a brand new way to breach it: <strong>a mutation whose only trace is in GHL is
	 * invisible to EvalOS forever.</strong> Nobody can reconstruct who moved an opportunity, or
	 * when, from a system that never recorded it.
	 *
	 * <p>{@code GhlHttp} cannot audit on the caller's behalf — it is transport, and invariant 12's
	 * reasoning applies: it does not know what a write <em>means</em>, so any row it wrote would
	 * say nothing useful. The obligation therefore sits with callers, and this is what stops that
	 * from meaning "with nobody".
	 *
	 * <p><strong>The expected set is empty today, and that is the point.</strong> Unit 37 ships
	 * the door and no caller. The first unit to write to GHL has to come here, add itself, and in
	 * doing so notice the requirement — rather than discovering it in a post-incident review.
	 */
	@Test
	@DisplayName("every class calling a GHL write verb also reaches AuditService")
	void writeCallersAudit() throws IOException {
		Path main = Path.of("src", "main", "java");
		assertThat(Files.isDirectory(main)).as("main sources at %s", main.toAbsolutePath()).isTrue();

		List<String> unaudited;
		try (Stream<Path> sources = Files.walk(main)) {
			unaudited = sources
					.filter((path) -> path.toString().endsWith(".java"))
					// The door itself declares the verbs; it is not a caller of them.
					.filter((path) -> !path.getFileName().toString().equals("GhlHttp.java"))
					.filter((path) -> {
						String text = read(path);
						return HOLDS_GHL_HTTP.matcher(text).find() && CALLS_WRITE_VERB.matcher(text).find();
					})
					.filter((path) -> !read(path).contains("AuditService"))
					.map(Path::toString)
					.sorted()
					.toList();
		}

		assertThat(unaudited)
				.describedAs("A write to GHL that leaves no audit row in EvalOS is invisible here "
						+ "forever — invariant 13. If this class legitimately delegates the audit to "
						+ "a service above it, that service is the one that should hold the GhlHttp "
						+ "reference; if the write genuinely records nothing, say why in "
						+ "context/specs/00b-ghl-operational-programme.md before silencing this.")
				.isEmpty();
	}

	/**
	 * The scan above has to be able to fail, or it is decoration — the same rule
	 * {@code SegmentIsNotAnAccessKeyTest} applies to its own scan.
	 */
	@Test
	@DisplayName("the write-caller scan recognises a caller and ignores a reader")
	void theWriteCallerScanBites() {
		String writer = "private final GhlHttp http; void go() { http.post(X.class, u, body); }";
		assertThat(HOLDS_GHL_HTTP.matcher(writer).find() && CALLS_WRITE_VERB.matcher(writer).find()).isTrue();

		String reader = "private final GhlHttp http; void go() { http.get(X.class, u); }";
		assertThat(HOLDS_GHL_HTTP.matcher(reader).find() && CALLS_WRITE_VERB.matcher(reader).find()).isFalse();
	}

	private static String read(Path source) {
		try {
			return Files.readString(source, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not read " + source, ex);
		}
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

	// --- Unit 37: the write verbs -------------------------------------------

	/**
	 * <strong>Unit 45's first requirement: a failure says WHAT KIND it is, not just that it
	 * happened.</strong>
	 *
	 * <p>{@code 00d} §6.3: "{@code GhlHttp} flattens status into a message string, so 4xx, 5xx and
	 * timeout are indistinguishable. Only 5xx/timeout/408/429 are retriable." Everything downstream
	 * — the outbox, the delta sweep, the sync-status surface — has to make that decision, and
	 * parsing it back out of a sentence is how one of them gets it wrong.
	 *
	 * <p>The status stays in the message too. It was put there deliberately, and a reader tailing
	 * logs is not the same audience as the code branching on this.
	 */
	@Test
	void everyUpstreamStatusIsClassifiedRatherThanFlattenedIntoAString() throws Exception {
		try (RecordingGhl ghl = new RecordingGhl()) {
			ghl.status = 403;
			assertThatThrownBy(() -> ghl.client().get(Map.class, (uri) -> uri.path("/x").build()))
					.isInstanceOfSatisfying(GhlUnavailableException.class, (refused) -> {
						assertThat(refused.failure()).isEqualTo(GhlFailure.UNAUTHORIZED);
						assertThat(refused.status()).isEqualTo(403);
						// Not retriable, and it stops a queue rather than skipping one row: every
						// other pending write would fail on the same credential.
						assertThat(refused.isRetriable()).isFalse();
						assertThat(refused.failure().stopsEverything()).isTrue();
						assertThat(refused.getMessage()).contains("403");
					});

			ghl.status = 400;
			assertThatThrownBy(() -> ghl.client().get(Map.class, (uri) -> uri.path("/x").build()))
					.isInstanceOfSatisfying(GhlUnavailableException.class, (refused) -> {
						assertThat(refused.failure()).isEqualTo(GhlFailure.REFUSED);
						// The one a retry loop would get wrong: sending a malformed body again is
						// budget spent on a body that is still malformed.
						assertThat(refused.isRetriable()).isFalse();
					});

			ghl.status = 503;
			assertThatThrownBy(() -> ghl.client().get(Map.class, (uri) -> uri.path("/x").build()))
					.isInstanceOfSatisfying(GhlUnavailableException.class, (refused) -> {
						assertThat(refused.failure()).isEqualTo(GhlFailure.UPSTREAM_ERROR);
						assertThat(refused.isRetriable()).isTrue();
						assertThat(refused.failure().stopsEverything()).isFalse();
					});
		}
	}

	/** 408 is a 4xx and is still retriable — the one exception to "4xx means stop". */
	@Test
	void aRequestTimeoutIsRetriableDespiteBeingA4xx() {
		assertThat(GhlFailure.ofStatus(408)).isEqualTo(GhlFailure.NO_ANSWER);
		assertThat(GhlFailure.ofStatus(408).isRetriable()).isTrue();
		assertThat(GhlFailure.ofStatus(404).isRetriable()).isFalse();
	}

	/**
	 * <strong>A 429 pauses the whole location, not the caller that hit the wall.</strong>
	 *
	 * <p>The 100-requests-per-10-seconds budget belongs to the GHL location, so backing off one
	 * caller while the next fires immediately is not a back-off. The pacer this pushes forward is
	 * the same one every other client shares, which is why this is at the door rather than in a
	 * queue.
	 *
	 * <p>Asserted through the pacer's own field rather than by timing a second call, because a test
	 * that waits ten seconds to prove a back-off is a test somebody deletes.
	 */
	@Test
	void aRateLimitPushesTheSharedPacerForwardForEveryone() throws Exception {
		try (RecordingGhl ghl = new RecordingGhl()) {
			ghl.status = 429;
			ghl.retryAfter = "3";
			GhlHttp client = ghl.client();

			assertThatThrownBy(() -> client.get(Map.class, (uri) -> uri.path("/x").build()))
					.isInstanceOfSatisfying(GhlUnavailableException.class, (limited) -> {
						assertThat(limited.failure()).isEqualTo(GhlFailure.RATE_LIMITED);
						assertThat(limited.isRetriable()).isTrue();
						assertThat(limited.failure().stopsEverything()).isTrue();
					});

			Instant next = (Instant) org.springframework.test.util.ReflectionTestUtils
					.getField(client, "nextRequestAt");
			assertThat(next).isAfter(Instant.now().plusSeconds(2));
		}
	}

	/**
	 * An upstream header must not park a thread pool.
	 *
	 * <p>{@code Retry-After} is input from somebody else's server. Honouring an hour of it would
	 * hand a remote system the ability to stall every EvalOS request thread that touches GHL, so it
	 * is capped — and a value that is not a number of seconds falls back rather than throwing
	 * inside an error path.
	 */
	@Test
	void anAbsurdRetryAfterIsCappedAndAnUnparseableOneFallsBack() throws Exception {
		try (RecordingGhl ghl = new RecordingGhl()) {
			ghl.status = 429;
			ghl.retryAfter = "999999";
			GhlHttp client = ghl.client();
			assertThatThrownBy(() -> client.get(Map.class, (uri) -> uri.path("/x").build()))
					.isInstanceOf(GhlUnavailableException.class);
			Instant capped = (Instant) org.springframework.test.util.ReflectionTestUtils
					.getField(client, "nextRequestAt");
			assertThat(capped).isBefore(Instant.now().plus(GhlHttp.MAX_BACKOFF).plusSeconds(5));

			ghl.retryAfter = "Wed, 21 Oct 2026 07:28:00 GMT";
			GhlHttp second = ghl.client();
			assertThatThrownBy(() -> second.get(Map.class, (uri) -> uri.path("/x").build()))
					.isInstanceOf(GhlUnavailableException.class);
			Instant fallback = (Instant) org.springframework.test.util.ReflectionTestUtils
					.getField(second, "nextRequestAt");
			assertThat(fallback).isAfter(Instant.now().plusSeconds(5));
		}
	}

	/**
	 * An unconfigured environment is not a transport failure, and must never be retried.
	 *
	 * <p>Nothing left the JVM, so there is no queue state to protect and no upstream to be gentle
	 * with — {@link GhlFailure#NOT_CONFIGURED} is deliberately absent from
	 * {@code stopsEverything()}.
	 */
	@Test
	void anUnconfiguredEnvironmentIsItsOwnClassAndIsNotRetriable() {
		GhlHttp blank = new GhlHttp("http://127.0.0.1:1", "2021-07-28", "", "", Duration.ofMillis(200));

		assertThatThrownBy(() -> blank.get(Map.class, (uri) -> uri.path("/x").build()))
				.isInstanceOfSatisfying(GhlUnavailableException.class, (unset) -> {
					assertThat(unset.failure()).isEqualTo(GhlFailure.NOT_CONFIGURED);
					assertThat(unset.isRetriable()).isFalse();
					assertThat(unset.failure().stopsEverything()).isFalse();
				});
	}

	/**
	 * <strong>A write GHL accepted is never retriable, whatever else went wrong.</strong>
	 *
	 * <p>An empty body on a 2xx means GHL considered the call successful and EvalOS cannot name
	 * what it created. Repeating it writes twice — the failure {@code 00d} §6.1 is built around.
	 */
	@Test
	void anEmptyBodyOnASuccessfulWriteIsNotRetriable() {
		assertThat(GhlFailure.EMPTY_RESPONSE.isRetriable()).isFalse();
		assertThat(GhlFailure.EMPTY_RESPONSE.stopsEverything()).isFalse();
	}

	/**
	 * A tiny JDK {@code HttpServer} that records every request and answers however the test says.
	 *
	 * <p>The other tests here aim at a closed port, which is enough to prove pacing but cannot
	 * see <em>what</em> was sent. Writes need that: the method actually issued and, above all,
	 * <strong>how many times</strong>. {@code com.sun.net.httpserver} is in the JDK, so this costs
	 * no dependency and no Docker.
	 */
	private static final class RecordingGhl implements AutoCloseable {

		private final HttpServer server;
		private final List<String> methods = Collections.synchronizedList(new ArrayList<>());
		private volatile int status = 200;

		/** Sent as {@code Retry-After} when set — the header a 429 carries. */
		private volatile String retryAfter;

		RecordingGhl() throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", (exchange) -> {
				methods.add(exchange.getRequestMethod());
				byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				if (retryAfter != null) {
					exchange.getResponseHeaders().add("Retry-After", retryAfter);
				}
				exchange.sendResponseHeaders(status, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			});
			server.start();
		}

		GhlHttp client() {
			return new GhlHttp("http://127.0.0.1:" + server.getAddress().getPort(), "2021-07-28",
					"pit-test-token", "loc_test", Duration.ofSeconds(2));
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}

	@Test
	@DisplayName("post, put and delete issue their own HTTP methods")
	void theWriteVerbsIssueTheRightMethods() throws IOException {
		try (RecordingGhl ghl = new RecordingGhl()) {
			GhlHttp http = ghl.client();

			http.post(String.class, (uri) -> uri.path("/contacts").build(), Map.of("name", "A"));
			http.put(String.class, (uri) -> uri.path("/contacts/1").build(), Map.of("name", "B"));
			http.delete((uri) -> uri.path("/contacts/1").build());

			assertThat(ghl.methods).containsExactly("POST", "PUT", "DELETE");
		}
	}

	/**
	 * <strong>A failed write is attempted exactly once.</strong>
	 *
	 * <p>Reads do not retry and writes must not start. GHL marks its write operations as needing
	 * idempotency, and EvalOS has no key scheme yet (deliberately deferred to the first caller in
	 * Unit 38) — so a blind retry here is precisely how one opportunity becomes two, or one
	 * contact becomes two, with nothing to reconcile them by.
	 *
	 * <p>Asserted by counting requests rather than by reading the configuration, because a retry
	 * can arrive from the {@code RestClient} builder, an interceptor or Spring's defaults, and
	 * only the request count sees all three.
	 */
	@Test
	@DisplayName("a failing write is attempted once and never retried")
	void writesDoNotRetry() throws IOException {
		try (RecordingGhl ghl = new RecordingGhl()) {
			ghl.status = 500;
			GhlHttp http = ghl.client();

			assertThatThrownBy(() -> http.post(String.class, (uri) -> uri.path("/opportunities").build(),
					Map.of("name", "A")))
					.isInstanceOf(GhlUnavailableException.class)
					.hasMessageContaining("500");

			assertThat(ghl.methods)
					.describedAs("no idempotency key exists yet, so a retried write is a duplicate "
							+ "opportunity nothing can reconcile")
					.containsExactly("POST");
		}
	}

	/** The write path refuses just as loudly as the read path when nothing is configured. */
	@Test
	@DisplayName("an unconfigured environment refuses writes too, naming both variables")
	void unconfiguredRefusesWrites() {
		GhlHttp http = new GhlHttp("http://127.0.0.1:1", "2021-07-28", "", "", Duration.ofMillis(200));

		assertThatThrownBy(() -> http.post(String.class, (uri) -> uri.path("/x").build(), Map.of()))
				.isInstanceOf(GhlUnavailableException.class)
				.hasMessageContaining("GHL_API_TOKEN")
				.hasMessageContaining("GHL_LOCATION_ID");
		assertThatThrownBy(() -> http.put(String.class, (uri) -> uri.path("/x").build(), Map.of()))
				.isInstanceOf(GhlUnavailableException.class);
		assertThatThrownBy(() -> http.delete((uri) -> uri.path("/x").build()))
				.isInstanceOf(GhlUnavailableException.class);
	}

	/**
	 * <strong>Reads and writes share the one pacer.</strong>
	 *
	 * <p>GHL's 100-per-10-seconds is per <em>location</em>, not per verb. A write path with a
	 * limiter of its own is the same bug {@link #pacerIsSharedAcrossClients} guards against,
	 * arriving from the other direction — and it would be easy to introduce, because a write
	 * path is the natural place to "just" build a second client.
	 */
	@Test
	@DisplayName("writes are paced by the same limiter as reads")
	void readsAndWritesSharePacer() {
		GhlHttp http = configured();

		Instant start = Instant.now();
		for (int i = 0; i < 3; i++) {
			attempt(() -> http.get(String.class, (uri) -> uri.path("/anything").build()));
			attempt(() -> http.post(String.class, (uri) -> uri.path("/anything").build(), Map.of()));
		}
		Duration elapsed = Duration.between(start, Instant.now());

		assertThat(elapsed)
				.describedAs("six mixed read/write calls are five intervals; a write path with its "
						+ "own pacer would let the pair exceed GHL's per-location rate")
				.isGreaterThanOrEqualTo(GhlHttp.MIN_REQUEST_INTERVAL.multipliedBy(5));
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
