package com.ie.evalos.integration;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * The one thing EvalOS reads out of GHL's public API: a sales pipeline and the opportunities
 * standing in it.
 *
 * <p><strong>This is a read and nothing else</strong> — no writes, no stage moves, no
 * opportunity creation. GHL stays the front of house (leads, sales, invoicing) and EvalOS
 * takes custody at Handoff A; the marketing view this client feeds is a <em>window</em> onto
 * GHL's own funnel, not a second copy of it. That is deliberate and is the boundary to keep:
 * the moment something here writes back, two systems own one pipeline.
 *
 * <p><strong>Called inline from a request path, which the standards normally forbid.</strong>
 * The rule in {@code code-standards.md} is about a <em>lifecycle side effect</em> — those go
 * through a domain event so they cannot be lost. A dashboard read has nothing to lose: if this
 * call fails the screen says so and nothing in EvalOS is left half-done. Two things keep it
 * inside invariant 6's "one bounded request": the timeout below, and the service's cache, so a
 * room full of open dashboards is not a room full of GHL calls.
 *
 * <p>Nothing is persisted. There is no {@code ghl_opportunity} table and there should not be —
 * a stage a salesperson dragged five seconds ago would already be wrong in it, and the contact
 * snapshots EvalOS <em>does</em> hold are the ones a case needs, arriving by webhook.
 */
@Component
public class GhlPipelineClient {

	private static final Logger log = LoggerFactory.getLogger(GhlPipelineClient.class);

	/** GHL's page maximum on the opportunity search. Fewer pages is fewer round trips. */
	private static final int PAGE_SIZE = 100;

	/**
	 * The only format GHL's {@code date}/{@code endDate} accept.
	 *
	 * <p>Date-only, so the window's edges are whole days in whatever zone GHL resolves them
	 * against. The caller decides which days those are — see {@code MarketingPipelineService},
	 * which uses {@code BusinessCalendar.ZONE} so "today" means the business's today rather
	 * than UTC's.
	 */
	private static final DateTimeFormatter GHL_DATE = DateTimeFormatter.ofPattern("MM-dd-yyyy");

	/**
	 * Hard stop on the pagination loop — a runaway guard, and only that.
	 *
	 * <p><strong>Raised from 50, which was silently truncating.</strong> 50 pages is 5,000 rows,
	 * and the email funnel's year is 11,443 — so had the caller ever asked for that window, this
	 * loop would have returned the first 5,000 and logged a warning nobody reads, and the screen
	 * would have shown a total 56% short of the truth wearing no mark of it. A cap below what the
	 * caller may legitimately ask for is not a guard, it is a wrong answer with a log line.
	 *
	 * <p>The real bound on how much this reads is {@code MarketingPipelineService}'s row ceiling,
	 * which is checked against GHL's own count <em>before</em> a single page is fetched. This
	 * only catches a cursor that never terminates, so it sits well above any real window.
	 *
	 * <p>Row paging is still never how the funnel is <em>counted</em> — {@link #countIn} gets that
	 * exactly in one request per stage. This is for the sum and the group-by, which GHL does not
	 * aggregate.
	 */
	private static final int MAX_PAGES = 1_500;

	/**
	 * The gap this client leaves between two GHL requests, so a long read cannot trip the rate
	 * limit.
	 *
	 * <p><strong>GHL allows 100 requests per 10 seconds per location</strong> (its own OAuth FAQ),
	 * which is one every 100ms. 110ms leaves a little headroom for clock granularity and for the
	 * fact that the limit is shared with anything else pointed at this location.
	 *
	 * <p><strong>This is what makes the Year window possible at all, and also what makes it
	 * slow.</strong> A year of the email funnel is 11,443 opportunities — 115 cursor pages, which
	 * cannot be parallelised because each page's cursor comes out of the one before it. So the
	 * floor is ~13s of pacing whatever else is true, which is past the browser's own timeout and
	 * why {@code MarketingPipelineService} does that read on a background thread instead.
	 *
	 * <p>Spaced rather than a token bucket on purpose: a bucket lets 100 requests go at once and
	 * then stalls for ten seconds, which is the same average and a much worse neighbour.
	 */
	private static final Duration MIN_REQUEST_INTERVAL = Duration.ofMillis(110);

	/** A pipeline and its stages. GHL carries the display order in {@code position}. */
	public record Pipeline(String id, String name, List<Stage> stages) {

		public record Stage(String id, String name, int position) {
		}
	}

	/**
	 * One opportunity, narrowed to the three fields the screen needs.
	 *
	 * <p>The narrowing is the point. GHL's search response also carries the contact's name,
	 * email, phone and tags on every row — none of which a stage count needs, and all of which
	 * would then be marketing PII sitting inside an EvalOS response. Binding only these three is
	 * what keeps it out of the payload, rather than a projection somebody has to remember.
	 *
	 * <p>{@code createdAt} was bound here briefly for a time-bucketed chart and removed with it.
	 * GHL's {@code date}/{@code endDate} already filter on that field server-side, so the window is
	 * applied without EvalOS ever reading the value — which keeps this projection at three.
	 *
	 * <p>{@code status} (open/won/lost/abandoned) is still deliberately absent: the stages this
	 * pipeline actually has include Won, Cold and Lost, so a status axis beside them would state
	 * the same fact twice and give two places for it to disagree.
	 */
	public record Opportunity(String pipelineStageId, BigDecimal monetaryValue, String source) {
	}

	private final RestClient client;
	private final String locationId;
	private final boolean configured;

	/**
	 * When the next request may go out. Guarded by {@code this} because the limit is per
	 * <em>location</em>, so it is shared by every caller and every thread — a per-thread or
	 * per-call limiter would let two concurrent reads each stay under the limit while together
	 * being over it.
	 */
	private Instant nextRequestAt = Instant.EPOCH;

	GhlPipelineClient(@Value("${evalos.ghl.base-url}") String baseUrl,
			@Value("${evalos.ghl.api-version}") String apiVersion,
			@Value("${evalos.ghl.token:}") String token,
			@Value("${evalos.ghl.location-id:}") String locationId,
			@Value("${evalos.ghl.timeout}") Duration timeout) {
		this.locationId = locationId;
		this.configured = !token.isBlank() && !locationId.isBlank();

		if (!configured) {
			log.warn("No GHL API token or location configured — the marketing pipeline view will "
					+ "answer 502. Set GHL_API_TOKEN and GHL_LOCATION_ID to enable it.");
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
			log.info("GHL read configured: locationId={}, token length={}", locationId, token.length());
		}

		this.client = RestClient.builder()
				.baseUrl(baseUrl)
				// Bounded for the reason the class comment gives: an outbound call on a request
				// path with the library's own defaults is how "one bounded request" quietly
				// becomes long-lived work.
				.requestFactory(bounded(timeout))
				.defaultHeader("Authorization", "Bearer " + token)
				// GHL's public API is versioned by header, not by path. Pinned in configuration
				// so a version bump is an environment change and not a build.
				.defaultHeader("Version", apiVersion)
				.defaultHeader("Accept", "application/json")
				.build();
	}

	/**
	 * The pipeline GHL knows by this name.
	 *
	 * <p><strong>Looked up by name rather than configured by id</strong>, because the id is a
	 * 20-character opaque string that means nothing to whoever provisions the environment while
	 * the name is what they can read in GHL. The trade is that renaming the pipeline over there
	 * breaks the view — which surfaces as the stated 502 below rather than as a silently empty
	 * funnel, and that is the direction to fail in.
	 *
	 * @throws GhlUnavailableException if GHL is not configured here, refused the request, or has
	 *                                no pipeline by that name
	 */
	public Pipeline pipelineNamed(String name) {
		// **`locationId`, camelCase — and it genuinely differs from the search endpoint below,
		// which demands snake_case.** Not an inconsistency to tidy up: GHL validates the two routes
		// with different DTOs, confirmed against the live API.
		//
		//   /opportunities/pipelines : camelCase `locationId`
		//                             snake_case -> 422 COMMON_LOCATION_ID_UNDEFINED
		//   /opportunities/search    : snake_case `location_id`, `pipeline_id`
		//                             camelCase -> 422 "property locationId should not exist"
		//
		// Both spellings are pinned in `GhlPipelineClientHttpTest`, so aligning either one to the
		// other fails the build. GHL's own `nextPageUrl` spells the search params camelCase, which
		// is what made the wrong guess look well-evidenced — only a live call settled it.
		PipelinesResponse response = get(PipelinesResponse.class,
				(uri) -> uri.path("/opportunities/pipelines").queryParam("locationId", locationId).build());

		return Optional.ofNullable(response.pipelines()).orElse(List.of()).stream()
				.filter((pipeline) -> name.equalsIgnoreCase(pipeline.name()))
				.findFirst()
				// The configured name is echoed because it came from this environment's own
				// configuration and is the thing to correct. The other pipelines' names are NOT
				// listed: they are other teams' funnels, and an error message is not a place to
				// enumerate them.
				.orElseThrow(() -> new GhlUnavailableException(
						"GHL has no pipeline named \"" + name + "\" in this location"));
	}

	/**
	 * Every opportunity standing in one pipeline, across as many pages as GHL needs.
	 *
	 * <p><strong>For the value and source breakdown only — never to count a stage.</strong>
	 * {@link #countIn} answers "how many" in one request; this one exists because a <em>sum</em>
	 * and a group-by need the rows themselves, and there is no GHL endpoint that aggregates them.
	 * The caller is responsible for only asking when the window is small: see
	 * {@code MarketingPipelineService.INLINE_ROW_BUDGET}.
	 *
	 * <p>Paged with {@code startAfter}/{@code startAfterId} rather than a page number, which is
	 * what GHL's own {@code nextPageUrl} uses — a cursor cannot skip or double-count a row that
	 * moved while the loop was running, and a salesperson dragging a card mid-read is the normal
	 * case here rather than the edge one.
	 *
	 * @param from first day of the created-at window, inclusive
	 * @param to   last day of it, inclusive. Both are dates and not instants because that is all
	 *             GHL's filter accepts
	 */
	public List<Opportunity> opportunitiesIn(String pipelineId, LocalDate from, LocalDate to) {
		List<Opportunity> all = new ArrayList<>();
		Long startAfter = null;
		String startAfterId = null;

		for (int page = 0; page < MAX_PAGES; page++) {
			Long cursor = startAfter;
			String cursorId = startAfterId;
			SearchResponse response = get(SearchResponse.class, (uri) -> {
				// **Three conventions on one endpoint, all verified against the live API.**
				// `location_id`/`pipeline_id` are snake_case (camelCase -> 422 "property
				// locationId should not exist"), while `date`/`endDate` and the cursor params are
				// camelCase (snake_case -> 422 "property start_date should not exist"). GHL is
				// simply inconsistent here; none of this is a typo to align.
				uri.path("/opportunities/search")
						.queryParam("location_id", locationId)
						.queryParam("pipeline_id", pipelineId)
						.queryParam("limit", PAGE_SIZE)
						// **`date`/`endDate` filter on the opportunity's `createdAt`** — confirmed
						// by narrowing to one month and getting back only rows created in it. So
						// the funnel becomes "opportunities *created* in this window, grouped by
						// the stage they are in now", which is the question a marketer is asking.
						// GHL wants mm-dd-yyyy; anything else is silently unfiltered, not refused.
						.queryParam("date", GHL_DATE.format(from))
						.queryParam("endDate", GHL_DATE.format(to));
				if (cursor != null && cursorId != null) {
					uri.queryParam("startAfter", cursor).queryParam("startAfterId", cursorId);
				}
				return uri.build();
			});

			List<Opportunity> found = Optional.ofNullable(response.opportunities()).orElse(List.of());
			all.addAll(found);

			// A short page is the last page. GHL returns exactly `limit` rows whether or not more
			// exist, so a final full page still costs one more request. `meta.total` is not used
			// to stop the loop even though `countIn` trusts it for counting: a count that is a
			// few rows stale is a fine count, while a *loop bound* that is a few rows short
			// silently drops the tail of the page it was reading.
			if (found.size() < PAGE_SIZE || response.meta() == null
					|| response.meta().startAfter() == null || response.meta().startAfterId() == null) {
				return all;
			}
			startAfter = response.meta().startAfter();
			startAfterId = response.meta().startAfterId();
		}

		log.warn("Stopped reading GHL pipeline {} at the {}-page cap with {} opportunities", pipelineId, MAX_PAGES,
				all.size());
		return all;
	}

	/**
	 * How many opportunities are in one stage of a pipeline, <strong>without reading them</strong>.
	 *
	 * <p><strong>This is the whole reason the Year view works.</strong> GHL reports the match
	 * count in {@code meta.total} on any search, so asking for a single row with the stage filter
	 * applied returns the exact figure in <em>one</em> request. Counting by pagination cost one
	 * request per hundred rows instead — 115 of them on the email pipeline's year, which timed the
	 * browser out at 15s before it ever finished.
	 *
	 * <p>So the funnel costs one request per stage regardless of how many deals are in it, and it
	 * is <strong>exact</strong>: nothing is capped, truncated or estimated.
	 *
	 * <p><strong>{@code pipeline_stage_id} is snake_case</strong>, like {@code location_id} and
	 * {@code pipeline_id} beside it and unlike {@code date}/{@code endDate}. Getting it wrong is an
	 * HTTP 422, which is the one merciful thing about this endpoint's naming — an unrecognised
	 * filter that was merely ignored would have every stage report the whole pipeline's count.
	 *
	 * <p>The trade is real and worth stating: {@code meta.total} is GHL's own count and can differ
	 * by a row or two from the rows a paginated read would return, if somebody moves a card
	 * between calls. A funnel is read for shape and magnitude, and a count that is one deal stale
	 * is a fine count — an unreadable screen is not.
	 *
	 * @param stageId the stage to count. Non-null: the pipeline total is the sum of its stages,
	 *                which is also what keeps the parts adding up to the whole on screen
	 */
	public int countIn(String pipelineId, String stageId, LocalDate from, LocalDate to) {
		SearchResponse response = get(SearchResponse.class, (uri) -> uri.path("/opportunities/search")
				.queryParam("location_id", locationId)
				.queryParam("pipeline_id", pipelineId)
				// **`pipeline_stage_id`, snake_case — with the same two snake_case names and the
				// same camelCase dates as the read above.** Verified against the live API, after
				// shipping the camelCase guess and getting HTTP 422 "property pipelineStageId
				// should not exist" on the first real call. It looked evidenced and was not: the
				// spelling had been checked through a tool that normalises parameter names before
				// sending, so what was tested was never what this client sends. Pinned in
				// `GhlPipelineClientHttpTest` so the guess cannot come back.
				.queryParam("pipeline_stage_id", stageId)
				// One row, because zero is not a limit GHL accepts and the count is in the meta
				// block either way. The row itself is parsed and thrown away — three fields.
				.queryParam("limit", 1)
				.queryParam("date", GHL_DATE.format(from))
				.queryParam("endDate", GHL_DATE.format(to))
				.build());

		// A window with no matches returns meta with a null total rather than a zero.
		return response.meta() == null || response.meta().total() == null ? 0 : response.meta().total();
	}

	private <T> T get(Class<T> type, java.util.function.Function<UriBuilder, java.net.URI> uri) {
		if (!configured) {
			// Both variable names are echoed, for the reason GoogleDriveConfig's boot message
			// echoes its two: whoever sees this is provisioning an environment and needs to know
			// which one to set. Neither name is a secret, and the token's *value* never appears
			// here — it is a default header on the client and is in no message this class writes.
			throw new GhlUnavailableException(
					"GHL is not configured in this environment, so no pipeline could be read. "
							+ "Set GHL_API_TOKEN and GHL_LOCATION_ID.");
		}
		pace();
		try {
			T body = client.get().uri(uri).retrieve().body(type);
			if (body == null) {
				throw new GhlUnavailableException("GHL returned an empty response");
			}
			return body;
		}
		// RestClientException covers the refused request, the transport failure and the timeout
		// set above — exactly the cases a 502 describes. A RuntimeException from anywhere else is
		// our bug and is left to propagate: answering 502 would tell the reader to retry
		// something no retry can fix, and hide a defect behind an upstream-fault status.
		catch (RestClientException ex) {
			// **The upstream status goes in the message, and that is a deliberate change.**
			// This used to say only "GHL did not answer the pipeline read", which collapsed a 401
			// (token missing the opportunities.readonly scope), a 404 (wrong location) and a
			// timeout into one indistinguishable string — and the first live attempt was spent
			// guessing between them. A status code is not a secret and it is the one fact that
			// separates "fix the grant" from "fix the id" from "try again".
			String upstream = ex instanceof RestClientResponseException refused
					? "GHL refused the pipeline read with HTTP " + refused.getStatusCode().value()
					: "GHL did not answer the pipeline read";

			// The token is never logged — it is a default header on the client and appears in no
			// message this class writes. GHL's response *body* is logged, because that is where
			// the actual reason lives ("scope not authorized" and the like) and it is server-side
			// only: it never reaches the API response.
			if (ex instanceof RestClientResponseException refused) {
				log.error("GHL refused the pipeline read: HTTP {} body={}", refused.getStatusCode().value(),
						refused.getResponseBodyAsString(), ex);
			}
			else {
				log.error("GHL pipeline read failed with no response", ex);
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
			throw new GhlUnavailableException("Interrupted while pacing GHL reads", ex);
		}
	}

	private static ClientHttpRequestFactory bounded(Duration timeout) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(timeout);
		factory.setReadTimeout(timeout);
		return factory;
	}

	// --- wire shapes -----------------------------------------------------------------
	//
	// Records rather than Maps, so a change in GHL's response is a compile error here and not a
	// ClassCastException three layers up. Unknown fields are ignored by Boot's default
	// ObjectMapper, which is what lets these stay this narrow.

	record PipelinesResponse(List<Pipeline> pipelines) {
	}

	record SearchResponse(List<Opportunity> opportunities, Meta meta) {

		/**
		 * GHL's cursor: {@code startAfter} is an epoch-millis sort key, not a page number.
		 *
		 * <p>{@code total} is the count of everything the search matched, not of the page — which
		 * is what {@link GhlPipelineClient#countIn} reads instead of paging.
		 */
		record Meta(Long startAfter, String startAfterId, Integer total) {
		}
	}
}
