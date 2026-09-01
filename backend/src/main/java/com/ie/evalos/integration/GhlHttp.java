package com.ie.evalos.integration;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * The single HTTP door to GHL: one {@link RestClient}, one rate limiter, one error mapping,
 * shared by every client that talks to the GHL public API.
 *
 * <p><strong>This exists because the rate limit is per location and a limiter is per
 * object.</strong> {@link GhlPipelineClient} used to own its own {@code RestClient} and its own
 * pacer, which was correct while it was the only caller. GHL allows <strong>100 requests per 10
 * seconds per location</strong>, so a second client bean would have meant two pacers governing one
 * location: each correctly under the limit, and the pair reliably over it. No amount of care
 * inside either client could fix that, because neither can see the other's traffic.
 *
 * <p>So this is an <strong>extraction, not an abstraction</strong>. No interface, no strategy, no
 * per-caller configuration. It is back to one caller now that the sales desk is gone, and it stays
 * extracted rather than folded back in: the limit is a property of the location, not of whoever
 * happens to be reading it this month, and re-merging it is how the next client silently gets a
 * pacer of its own. {@code GhlHttpTest} pins the shared-pacer property across two client
 * instances so that stays true.
 *
 * <p><strong>Read-only, and the absence is the guarantee.</strong> There is no {@code post},
 * {@code put} or {@code delete} here: EvalOS reads GHL and writes nothing back. Those verbs
 * existed for the sales desk, which is gone, and they went with it rather than staying
 * present-and-unused — a capability nothing calls is one somebody reaches for without deciding to.
 * Note what this does <em>not</em> rest on: the credential is still
 * {@code opportunities.write} + {@code contacts.write}, so this is held by code alone, which is
 * why {@code GhlHttpTest} asserts it rather than a comment claiming it.
 */
@Component
public class GhlHttp {

	private static final Logger log = LoggerFactory.getLogger(GhlHttp.class);

	/**
	 * The gap this client leaves between two GHL requests, so a long read cannot trip the rate
	 * limit.
	 *
	 * <p><strong>GHL allows 100 requests per 10 seconds per location</strong> (its own OAuth FAQ),
	 * which is one every 100ms. 110ms leaves a little headroom for clock granularity and for the
	 * fact that the limit is shared with anything else pointed at this location.
	 *
	 * <p>Spaced rather than a token bucket on purpose: a bucket lets 100 requests go at once and
	 * then stalls for ten seconds, which is the same average and a much worse neighbour.
	 */
	static final Duration MIN_REQUEST_INTERVAL = Duration.ofMillis(110);

	private final RestClient reads;
	private final String locationId;
	private final boolean configured;

	/**
	 * When the next request may go out. Guarded by {@code this} because the limit is per
	 * <em>location</em>, so it is shared by every caller and every thread — a per-thread,
	 * per-call or <em>per-client</em> limiter would let two concurrent readers each stay under the
	 * limit while together being over it.
	 */
	private Instant nextRequestAt = Instant.EPOCH;

	GhlHttp(@Value("${evalos.ghl.base-url}") String baseUrl,
			@Value("${evalos.ghl.api-version}") String apiVersion,
			@Value("${evalos.ghl.token:}") String token,
			@Value("${evalos.ghl.location-id:}") String locationId,
			@Value("${evalos.ghl.timeout}") Duration timeout) {
		this.locationId = locationId;
		this.configured = !token.isBlank() && !locationId.isBlank();

		if (!configured) {
			log.warn("No GHL API token or location configured — the GHL-backed screens will "
					+ "answer 502. Set GHL_API_TOKEN and GHL_LOCATION_ID to enable them.");
		}
		else {
			// **What this line exists for.** A 401 from GHL has two causes that look identical
			// from the outside: a token that is wrong or truncated, and a perfectly good token
			// pointed at a location it is not authorised for. Working that out cost two restarts,
			// because nothing said which location the JVM had actually resolved — an environment
			// variable silently overrides the profile default, and a stale one in the shell is
			// invisible.
			//
			// The location id is logged in full: it is an identifier that appears in every GHL
			// URL and grants nothing on its own. The token is logged as a LENGTH ONLY — enough to
			// catch a truncated or empty-quoted paste, and never the value. A prefix would leak a
			// little for no extra diagnostic value, since length already separates the failures
			// that actually happen.
			log.info("GHL configured: locationId={}, token length={}", locationId, token.length());
		}

		this.reads = build(baseUrl, apiVersion, token, timeout);
	}

	/** The GHL sub-account every request is scoped to. One per deployment until Unit 25. */
	public String locationId() {
		return locationId;
	}

	/** Whether a token and location are present. False means every call here answers 502. */
	public boolean isConfigured() {
		return configured;
	}

	public <T> T get(Class<T> type, Function<UriBuilder, URI> uri) {
		return call(type, () -> reads.get().uri(uri).retrieve().body(type));
	}

	// There is deliberately no post(), put() or delete(). See the class comment: EvalOS reads GHL.

	private <T> T call(Class<T> type, java.util.function.Supplier<T> request) {
		if (!configured) {
			// Both variable names are echoed, for the reason GoogleDriveConfig's boot message
			// echoes its two: whoever sees this is provisioning an environment and needs to know
			// which one to set. Neither name is a secret, and the token's *value* never appears
			// here — it is a default header on the client and is in no message this class writes.
			throw new GhlUnavailableException(
					"GHL is not configured in this environment. Set GHL_API_TOKEN and GHL_LOCATION_ID.");
		}
		pace();
		try {
			T body = request.get();
			// A caller asking for a payload and getting none has nothing to show, so that is a 502.
			// The `Void.class` exemption survives the sales desk it was written for: a caller that
			// binds no payload has said it wants none, and there is no reading of an empty body
			// that makes it an error for them.
			if (body == null && type != Void.class) {
				throw new GhlUnavailableException("GHL returned an empty response");
			}
			return body;
		}
		// RestClientException covers the refused request, the transport failure and the timeout
		// set above — exactly the cases a 502 describes. A RuntimeException from anywhere else is
		// our bug and is left to propagate: answering 502 would tell the reader to retry
		// something no retry can fix, and hide a defect behind an upstream-fault status.
		catch (RestClientException ex) {
			// **The upstream status goes in the message, and that is deliberate.** Without it a 401
			// (token missing a scope), a 404 (wrong location) and a timeout collapse into one
			// indistinguishable string, and the first live attempt is spent guessing between them.
			// A status code is not a secret and it is the one fact that separates "fix the grant"
			// from "fix the id" from "try again".
			//
			String upstream;
			if (ex instanceof RestClientResponseException refused) {
				upstream = "GHL refused the read with HTTP " + refused.getStatusCode().value();
				// The token is never logged — it is a default header and appears in no message this
				// class writes. GHL's response *body* is logged, because that is where the actual
				// reason lives ("scope not authorized" and the like) and it is server-side only:
				// it never reaches the API response.
				log.error("GHL refused a read: HTTP {} body={}", refused.getStatusCode().value(),
						refused.getResponseBodyAsString(), ex);
			}
			else {
				upstream = "GHL did not answer the read";
				log.error("GHL read failed with no response", ex);
			}
			throw new GhlUnavailableException(upstream, ex);
		}
	}

	/**
	 * Waits, if it has to, until this request's turn under {@link #MIN_REQUEST_INTERVAL}.
	 *
	 * <p>The slot is claimed inside the lock and the sleep happens outside it, so N waiting
	 * threads take N distinct slots and go out spaced rather than all waking together and firing
	 * at once. Sleeping while holding the lock would serialise the <em>waiting</em> too and make
	 * the last caller wait for the sum of everyone else's sleeps.
	 */
	private void pace() {
		Instant slot;
		synchronized (this) {
			Instant now = Instant.now();
			slot = nextRequestAt.isAfter(now) ? nextRequestAt : now;
			nextRequestAt = slot.plus(MIN_REQUEST_INTERVAL);
		}
		Duration wait = Duration.between(Instant.now(), slot);
		if (wait.isNegative() || wait.isZero()) {
			return;
		}
		try {
			Thread.sleep(wait);
		}
		catch (InterruptedException ex) {
			// Restore the flag and stop: the only thing that interrupts this is shutdown, and a
			// swallowed interrupt there means the JVM waits on a read nobody is going to read.
			Thread.currentThread().interrupt();
			throw new GhlUnavailableException("Interrupted while pacing GHL requests", ex);
		}
	}

	private static RestClient build(String baseUrl, String apiVersion, String token, Duration timeout) {
		return RestClient.builder()
				.baseUrl(baseUrl)
				// Bounded because these calls sit on a request path: an outbound call with the
				// library's own defaults is how "one bounded request" quietly becomes long-lived
				// work (invariant 6).
				.requestFactory(bounded(timeout))
				.defaultHeader("Authorization", "Bearer " + token)
				// GHL's public API is versioned by header, not by path. Pinned in configuration
				// so a version bump is an environment change and not a build.
				.defaultHeader("Version", apiVersion)
				.defaultHeader("Accept", "application/json")
				.build();
	}

	private static ClientHttpRequestFactory bounded(Duration timeout) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(timeout);
		factory.setReadTimeout(timeout);
		return factory;
	}
}
