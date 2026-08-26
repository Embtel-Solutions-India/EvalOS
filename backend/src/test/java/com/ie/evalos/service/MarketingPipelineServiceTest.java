package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.ie.evalos.common.DateWindow;
import com.ie.evalos.domain.GhlFunnelCache;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlPipelineClient.Opportunity;
import com.ie.evalos.integration.GhlPipelineClient.Pipeline;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.repository.GhlFunnelCacheRepository;
import com.ie.evalos.service.MarketingPipelineService.Detail;
import com.ie.evalos.service.MarketingPipelineService.Funnel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * The funnel, and the four ways it can quietly stop meaning what it says: a stage silently
 * dropped, an empty pipeline reported as 0% everywhere, an unpriced opportunity taking the total
 * down with it, and a cache that turns a failed read into a stale one presented as live.
 */
class MarketingPipelineServiceTest {

	private static final String PIPELINE_NAME = "Google ADS Pipeline";

	/** The second configured funnel. A different name, so a mix-up shows up as a wrong lookup. */
	private static final String EMAIL_PIPELINE_NAME = "Shivangi's Email Marketing";

	/** The third: the sales funnel. Same reason for a distinct name as the second. */
	private static final String SALES_PIPELINE_NAME = "Aditya's pipeline";

	/** Deliberately out of position order, so the service is doing the sorting and not the list. */
	private static final Pipeline PIPELINE = new Pipeline("pipe-1", PIPELINE_NAME, List.of(
			new Pipeline.Stage("won", "Won", 2),
			new Pipeline.Stage("new", "New Lead", 0),
			new Pipeline.Stage("warm", "Warm", 1),
			new Pipeline.Stage("lost", "Lost", 3)));

	private final GhlPipelineClient ghl = mock(GhlPipelineClient.class);

	private MarketingPipelineService.MarketingPipeline read(List<Opportunity> opportunities) {
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(PIPELINE);
		stubCounts("pipe-1", PIPELINE, opportunities);
		given(ghl.opportunitiesIn(eq("pipe-1"), any(), any())).willReturn(opportunities);
		return service(Duration.ofMinutes(5)).forCaller(Funnel.ADS, window("month"));
	}

	/**
	 * Stands in for GHL's per-stage match count, derived from the same rows the row-read returns.
	 *
	 * <p>Deriving it rather than hard-coding it keeps the fake honest: the service now takes its
	 * counts from one call and its money from another, and a fixture where the two disagree would
	 * test a state GHL does not produce.
	 */
	private void stubCounts(String pipelineId, Pipeline pipeline, List<Opportunity> opportunities) {
		for (Pipeline.Stage stage : pipeline.stages()) {
			given(ghl.countIn(eq(pipelineId), eq(stage.id()), any(), any())).willReturn(
					(int) opportunities.stream()
							.filter((opportunity) -> stage.id().equals(opportunity.pipelineStageId()))
							.count());
		}
	}

	/**
	 * The cache table, faked in memory <strong>for the test only</strong>.
	 *
	 * <p>Worth being explicit about, since the point of the change these tests cover is that the
	 * cache is no longer in memory: production reads and writes {@code ghl_funnel_cache} in
	 * Postgres. This stands in for the table so the service's caching *logic* — TTL, the
	 * compare-and-set, the totalling claim, the poll-until-READY handover — can be tested without a
	 * database. The real table is exercised by {@code LocalPostgresIntegrationTest}, which is now
	 * unskipped whenever a Postgres is reachable.
	 */
	private final Map<String, GhlFunnelCache> rows = new ConcurrentHashMap<>();

	/**
	 * A repository over {@link #rows}, honouring the two behaviours the service depends on: lookup
	 * by the whole window key, and last-write-wins on save.
	 *
	 * <p>It does <strong>not</strong> simulate optimistic-lock failures. Those are a database
	 * guarantee, so asserting them here would be asserting the fake; what belongs at this level is
	 * that the service asks for the row it is about to overwrite.
	 */
	private GhlFunnelCacheRepository cacheTable() {
		GhlFunnelCacheRepository table = mock(GhlFunnelCacheRepository.class);
		given(table.findByFunnelAndWindowKey(anyString(), anyString())).willAnswer(
				(call) -> Optional.ofNullable(rows.get(key(call.getArgument(0), call.getArgument(1)))));
		given(table.saveAndFlush(any(GhlFunnelCache.class))).willAnswer((call) -> {
			GhlFunnelCache row = call.getArgument(0);
			rows.put(key(row.getFunnel(), row.getWindowKey()), row);
			return row;
		});
		return table;
	}

	private static String key(String funnel, String windowKey) {
		return funnel + "|" + windowKey;
	}

	/**
	 * An {@code ObjectMapper} shaped like Spring Boot's: the payload carries {@code Instant} and
	 * {@code LocalDate}, so without {@code JavaTimeModule} every cache write would fail and every
	 * read would silently become a miss — the tests would pass while caching nothing.
	 */
	private static ObjectMapper json() {
		return JsonMapper.builder().addModule(new JavaTimeModule()).build();
	}

	private MarketingPipelineService service(Duration ttl) {
		return new MarketingPipelineService(ghl, cacheTable(), json(), PIPELINE_NAME, EMAIL_PIPELINE_NAME,
				SALES_PIPELINE_NAME, ttl);
	}

	/**
	 * A resolved window, on a <strong>fixed</strong> clock.
	 *
	 * <p>Pinned to a Wednesday mid-month so every named range is a distinct, non-degenerate window:
	 * on the 1st "this month" is one day wide and equal to "today", which would let a service bug
	 * that confused two ranges pass. {@code DateWindowTest} owns the boundary cases; this file only
	 * needs windows that differ from each other.
	 */
	private static DateWindow window(String range) {
		return DateWindow.of(range, null, null, CLOCK);
	}

	/** An explicit window, for the cache-collision case that only {@code custom} can create. */
	private static DateWindow custom(String from, String to) {
		return DateWindow.of("custom", from, to, CLOCK);
	}

	private static final java.time.Clock CLOCK = java.time.Clock.fixed(
			java.time.LocalDate.parse("2026-08-26").atTime(12, 0)
					.atZone(BusinessCalendar.ZONE).toInstant(),
			BusinessCalendar.ZONE);

	private static Opportunity at(String stageId, String amount, String source) {
		return new Opportunity(stageId, amount == null ? null : new BigDecimal(amount), source);
	}

	@Test
	void ordersStagesByGhlsOwnPositionAndKeepsTheEmptyOnes() {
		var funnel = read(List.of(
				at("new", "1000", "Google Ads"),
				at("warm", "2000", "Google Ads"),
				at("warm", "500", "Google Ads")));

		// Position order, not the order GHL happened to list them in.
		assertThat(funnel.stages()).extracting(MarketingPipelineService.StageFunnel::name)
				.containsExactly("New Lead", "Warm", "Won", "Lost");

		// **The empty stages are rows.** A funnel that drops the stages nobody is in looks
		// shorter than it is, and "nothing is sitting in Won" is the useful half of this screen.
		assertThat(funnel.stages()).extracting(MarketingPipelineService.StageFunnel::deals)
				.containsExactly(1, 2, 0, 0);
		assertThat(funnel.stages()).extracting(MarketingPipelineService.StageFunnel::value)
				.containsExactly(new BigDecimal("1000"), new BigDecimal("2500"), BigDecimal.ZERO, BigDecimal.ZERO);
		assertThat(funnel.stages()).extracting(MarketingPipelineService.StageFunnel::sharePct)
				.containsExactly(33, 67, 0, 0);

		assertThat(funnel.totalDeals()).isEqualTo(3);
		assertThat(funnel.totalValue()).isEqualByComparingTo("3500");
	}

	@Test
	void reportsNoShareRatherThanZeroPercentOnAnEmptyPipeline() {
		var funnel = read(List.of());

		// Null, not 0. An empty pipeline is not the claim that 0% of its deals are in New Lead —
		// the same rule every other rate in this app follows, and the screen renders an em dash
		// for it rather than a row of noughts that reads as a collapse.
		assertThat(funnel.stages()).extracting(MarketingPipelineService.StageFunnel::sharePct)
				.containsOnlyNulls();
		assertThat(funnel.totalDeals()).isZero();
		assertThat(funnel.totalValue()).isEqualByComparingTo("0");
	}

	@Test
	void groupsSourcesByWeightAndNamesTheUnattributedOnes() {
		var funnel = read(List.of(
				at("new", "1000", "Google Ads"),
				at("warm", "3000", "Referral"),
				at("warm", "1000", "Google Ads"),
				at("won", "9000", "  "),
				at("won", "500", null)));

		assertThat(funnel.sources()).extracting(MarketingPipelineService.SourceRow::source,
				MarketingPipelineService.SourceRow::deals, MarketingPipelineService.SourceRow::value)
				.containsExactly(
						// Blank and absent are one bucket, and it has a name rather than an empty
						// label — an unlabelled row reads as a rendering fault.
						tuple("Unattributed", 2, new BigDecimal("9500")),
						tuple("Google Ads", 2, new BigDecimal("2000")),
						tuple("Referral", 1, new BigDecimal("3000")));
	}

	/**
	 * <strong>Two spellings of one source are one row.</strong>
	 *
	 * <p>These strings are typed by hand into campaigns and forms over months, so the same source
	 * arrives cased differently — this account already holds "Application Form" and "Application
	 * form--…". Two rows for one source halves a figure for a reason nothing on screen explains.
	 * The label keeps the <em>first</em> spelling seen: a canonical casing invented here would
	 * show the reader a string that exists nowhere in GHL.
	 */
	@Test
	void groupsSourcesThatDifferOnlyInCase() {
		var funnel = read(List.of(
				at("new", "1000", "Google Ads"),
				at("warm", "2000", "google ads"),
				at("won", "3000", "GOOGLE ADS"),
				at("new", "500", "  Google Ads  ")));

		assertThat(funnel.sources()).extracting(MarketingPipelineService.SourceRow::source,
				MarketingPipelineService.SourceRow::deals, MarketingPipelineService.SourceRow::value)
				.containsExactly(tuple("Google Ads", 4, new BigDecimal("6500")));
	}

	/**
	 * <strong>A stage named for an outcome IS that outcome, whatever its casing.</strong>
	 *
	 * <p>GHL's own {@code status} field cannot be used for this: 144 opportunities in this
	 * account sit in the stage named "Won" while only 3 carry {@code status: "won"}, because the
	 * rest were dragged into the column without anyone pressing GHL's separate win button. The
	 * stage is what the salesperson actually did.
	 *
	 * <p>{@code Cold} is the case that proves the rule is a match and not a vibe: it reads like an
	 * ending, it is not one of GHL's four status words, and it stays {@code OPEN}.
	 */
	@Test
	void readsAStagesOutcomeFromItsNameIgnoringCase() {
		Pipeline mixedCase = new Pipeline("pipe-3", PIPELINE_NAME, List.of(
				new Pipeline.Stage("s1", "New Lead", 0),
				new Pipeline.Stage("s2", "won", 1),
				new Pipeline.Stage("s3", "LOST", 2),
				new Pipeline.Stage("s4", " Abandoned ", 3),
				new Pipeline.Stage("s5", "Cold", 4)));
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(mixedCase);
		given(ghl.opportunitiesIn(eq("pipe-3"), any(), any())).willReturn(List.of());

		var funnel = service(Duration.ofMinutes(5)).forCaller(Funnel.ADS, window("month"));

		assertThat(funnel.stages()).extracting(MarketingPipelineService.StageFunnel::outcome)
				.containsExactly(MarketingPipelineService.Outcome.OPEN,
						MarketingPipelineService.Outcome.WON,
						MarketingPipelineService.Outcome.LOST,
						MarketingPipelineService.Outcome.ABANDONED,
						// Not a GHL status word, so not promoted into one.
						MarketingPipelineService.Outcome.OPEN);
	}

	@Test
	void countsAnUnpricedOpportunityAsZeroRatherThanFailing() {
		// GHL sends no monetaryValue on an opportunity nobody priced. It is still a deal.
		var funnel = read(List.of(at("new", null, "Google Ads"), at("new", "1000", "Google Ads")));

		assertThat(funnel.stages().getFirst().deals()).isEqualTo(2);
		assertThat(funnel.stages().getFirst().value()).isEqualByComparingTo("1000");
	}

	@Test
	void asksGhlOnceWithinTheTtlAndAgainAfterIt() {
		List<Opportunity> one = List.of(at("new", "1000", "Google Ads"));
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(PIPELINE);
		stubCounts("pipe-1", PIPELINE, one);
		given(ghl.opportunitiesIn(eq("pipe-1"), any(), any())).willReturn(one);

		// The cache is the rate limiter, not an optimisation: without it, every open dashboard is
		// its own multi-page GHL read and GHL's rate limit becomes an EvalOS outage.
		MarketingPipelineService cached = service(Duration.ofMinutes(5));
		cached.forCaller(Funnel.ADS, window("month"));
		cached.forCaller(Funnel.ADS, window("month"));
		then(ghl).should(times(1)).opportunitiesIn(eq("pipe-1"), any(), any());

		// A zero TTL is always stale, so the second caller goes back out. Proves the freshness
		// check is a comparison and not a "have we ever read this" flag.
		MarketingPipelineService uncached = service(Duration.ZERO);
		uncached.forCaller(Funnel.ADS, window("month"));
		uncached.forCaller(Funnel.ADS, window("month"));
		then(ghl).should(times(3)).opportunitiesIn(eq("pipe-1"), any(), any());
	}

	/**
	 * <strong>The cache is keyed by period, and this is the test that matters most in this file.</strong>
	 *
	 * <p>The cache was a single slot while the funnel had no period selector. Adding a range
	 * without keying it would have served whichever window was fetched first to every other one —
	 * Month's figures sitting under a Year label for a whole TTL, with no error and nothing on
	 * screen to hint at it. That is the worst class of bug this screen can have: confidently wrong
	 * numbers. Asserted by asking for two ranges and proving GHL was asked twice.
	 */
	@Test
	void cachesEachPeriodSeparatelySoOneWindowNeverAnswersForAnother() {
		List<Opportunity> one = List.of(at("new", "1000", "Google Ads"));
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(PIPELINE);
		stubCounts("pipe-1", PIPELINE, one);
		given(ghl.opportunitiesIn(eq("pipe-1"), any(), any())).willReturn(one);

		MarketingPipelineService service = service(Duration.ofMinutes(5));
		service.forCaller(Funnel.ADS, window("month"));
		service.forCaller(Funnel.ADS, window("year"));
		// Two distinct windows, so two reads even though both are inside the TTL.
		then(ghl).should(times(2)).opportunitiesIn(eq("pipe-1"), any(), any());

		// And each is now cached under its own key rather than evicting the other.
		service.forCaller(Funnel.ADS, window("month"));
		service.forCaller(Funnel.ADS, window("year"));
		then(ghl).should(times(2)).opportunitiesIn(eq("pipe-1"), any(), any());

		// The payload states which window it describes, so the screen can never label it wrongly.
		assertThat(service.forCaller(Funnel.ADS, window("year")).range()).isEqualTo("year");
		assertThat(service.forCaller(Funnel.ADS, window("month")).range()).isEqualTo("month");
	}

	/**
	 * <strong>The cache is keyed by funnel as well, for exactly the reason it is keyed by period.</strong>
	 *
	 * <p>Three funnels share this service and every field of the payload except the numbers — same
	 * stage names, same sources, same shape. So the ads funnel served under the email screen's
	 * heading would look entirely plausible for a whole TTL: nothing on screen and nothing in a
	 * log would contradict it. Asserted by reading all three and proving each pipeline was looked
	 * up by its own name.
	 *
	 * <p><strong>The sales funnel is in here rather than in a test of its own</strong> because the
	 * failure this guards is a <em>collision</em>, and a collision needs every occupant of the key
	 * space present at once. A separate test asserting SALES works in isolation would pass with the
	 * enum key dropped from the cache lookup entirely.
	 */
	@Test
	void cachesEachFunnelSeparatelySoOnePipelineNeverAnswersForAnother() {
		Pipeline email = new Pipeline("pipe-2", EMAIL_PIPELINE_NAME,
				List.of(new Pipeline.Stage("new", "New Lead", 0)));
		Pipeline sales = new Pipeline("pipe-3", SALES_PIPELINE_NAME,
				List.of(new Pipeline.Stage("new", "New Lead", 0)));
		List<Opportunity> ads = List.of(at("new", "1000", "Google Ads"));
		List<Opportunity> emails = List.of(at("new", "2000", "Email"), at("new", "3000", "Email"));
		List<Opportunity> deals = List.of(at("new", "4000", "Referral"), at("new", "5000", "Referral"),
				at("new", "6000", "Referral"));
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(PIPELINE);
		given(ghl.pipelineNamed(EMAIL_PIPELINE_NAME)).willReturn(email);
		given(ghl.pipelineNamed(SALES_PIPELINE_NAME)).willReturn(sales);
		stubCounts("pipe-1", PIPELINE, ads);
		stubCounts("pipe-2", email, emails);
		stubCounts("pipe-3", sales, deals);
		given(ghl.opportunitiesIn(eq("pipe-1"), any(), any())).willReturn(ads);
		given(ghl.opportunitiesIn(eq("pipe-2"), any(), any())).willReturn(emails);
		given(ghl.opportunitiesIn(eq("pipe-3"), any(), any())).willReturn(deals);

		MarketingPipelineService service = service(Duration.ofMinutes(5));

		assertThat(service.forCaller(Funnel.ADS, window("month")).totalDeals()).isEqualTo(1);
		assertThat(service.forCaller(Funnel.EMAIL, window("month")).totalDeals()).isEqualTo(2);
		assertThat(service.forCaller(Funnel.SALES, window("month")).totalDeals()).isEqualTo(3);
		// The name is the routing, so each funnel must have resolved its own configured one.
		assertThat(service.forCaller(Funnel.EMAIL, window("month")).pipelineName()).isEqualTo(EMAIL_PIPELINE_NAME);
		assertThat(service.forCaller(Funnel.ADS, window("month")).pipelineName()).isEqualTo(PIPELINE_NAME);
		assertThat(service.forCaller(Funnel.SALES, window("month")).pipelineName()).isEqualTo(SALES_PIPELINE_NAME);

		// All inside one TTL: three reads total, one per funnel, and none evicted another.
		then(ghl).should(times(1)).opportunitiesIn(eq("pipe-1"), any(), any());
		then(ghl).should(times(1)).opportunitiesIn(eq("pipe-2"), any(), any());
		then(ghl).should(times(1)).opportunitiesIn(eq("pipe-3"), any(), any());
	}

	/**
	 * <strong>Two different custom windows are two different cache rows.</strong>
	 *
	 * <p>The failure this prevents is the one the funnel key already prevents on the other axis, and
	 * {@code custom} is what made it reachable: every custom period is <em>named</em> {@code custom},
	 * so a cache keyed by range name would give January's figures to a caller asking for March, for
	 * a whole TTL, with nothing on screen to contradict it — the payloads are identical in shape.
	 *
	 * <p>Asserted through the service rather than on {@code DateWindow.key()} alone (which
	 * {@code DateWindowTest} covers): what matters here is that the service actually threads the
	 * window into the lookup, and a service that keyed on {@code range().name()} would pass the
	 * key test and fail this one.
	 */
	@Test
	void twoDifferentCustomWindowsDoNotShareACacheRow() {
		List<Opportunity> january = List.of(at("new", "1000", "Google Ads"));
		List<Opportunity> march = List.of(at("new", "2000", "Google Ads"), at("new", "3000", "Google Ads"));
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(PIPELINE);
		// Keyed off the window handed to the client, so the stub answers per period rather than
		// per call order — a call-order stub would pass even if both reads used one window.
		for (Pipeline.Stage stage : PIPELINE.stages()) {
			given(ghl.countIn(eq("pipe-1"), eq(stage.id()), eq(java.time.LocalDate.parse("2026-01-01")), any()))
					.willReturn("new".equals(stage.id()) ? 1 : 0);
			given(ghl.countIn(eq("pipe-1"), eq(stage.id()), eq(java.time.LocalDate.parse("2026-03-01")), any()))
					.willReturn("new".equals(stage.id()) ? 2 : 0);
		}
		given(ghl.opportunitiesIn(eq("pipe-1"), eq(java.time.LocalDate.parse("2026-01-01")), any()))
				.willReturn(january);
		given(ghl.opportunitiesIn(eq("pipe-1"), eq(java.time.LocalDate.parse("2026-03-01")), any()))
				.willReturn(march);

		MarketingPipelineService service = service(Duration.ofMinutes(5));

		assertThat(service.forCaller(Funnel.ADS, custom("2026-01-01", "2026-01-31")).totalDeals()).isEqualTo(1);
		assertThat(service.forCaller(Funnel.ADS, custom("2026-03-01", "2026-03-31")).totalDeals()).isEqualTo(2);
		// Re-read inside the TTL: each window still answers for itself, so neither evicted nor
		// impersonated the other.
		assertThat(service.forCaller(Funnel.ADS, custom("2026-01-01", "2026-01-31")).totalDeals()).isEqualTo(1);
		assertThat(service.forCaller(Funnel.ADS, custom("2026-01-01", "2026-01-31")).from())
				.isEqualTo(java.time.LocalDate.parse("2026-01-01"));

		// Two windows, two GHL reads — and only two, so the cache is still doing its job.
		then(ghl).should(times(1)).opportunitiesIn(eq("pipe-1"), eq(java.time.LocalDate.parse("2026-01-01")), any());
		then(ghl).should(times(1)).opportunitiesIn(eq("pipe-1"), eq(java.time.LocalDate.parse("2026-03-01")), any());
	}

	/**
	 * A wider range really does ask GHL for a wider window — the point of the whole parameter.
	 *
	 * <p>Asserted on the dates handed to the client, because that is the only place the window
	 * becomes observable; the funnel numbers themselves come back from a stub.
	 */
	@Test
	void aWiderRangeAsksGhlForAnEarlierStartDate() {
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(PIPELINE);
		given(ghl.opportunitiesIn(eq("pipe-1"), any(), any())).willReturn(List.of());

		MarketingPipelineService service = service(Duration.ofMinutes(5));
		var today = service.forCaller(Funnel.ADS, window("today"));
		var week = service.forCaller(Funnel.ADS, window("week"));
		var month = service.forCaller(Funnel.ADS, window("month"));
		var year = service.forCaller(Funnel.ADS, window("year"));

		// Same end, earlier start. All four "this" ranges end today, which is what to-date means.
		assertThat(year.to()).isEqualTo(month.to());
		assertThat(year.from()).isBefore(month.from());

		// **Calendar boundaries, not day counts** — the change these ranges took. `month` used to
		// be `to.minusDays(29)` and is now the 1st; `week` used to be six days back and is now
		// Monday. On the pinned Wednesday 26 August 2026 those are different dates, which is the
		// point: a day-count implementation cannot produce them.
		assertThat(today.from()).isEqualTo(today.to());
		assertThat(week.from()).isEqualTo(java.time.LocalDate.parse("2026-08-24"));
		assertThat(month.from()).isEqualTo(java.time.LocalDate.parse("2026-08-01"));
		assertThat(year.from()).isEqualTo(java.time.LocalDate.parse("2026-01-01"));

		// And the one range that does not end today at all, which is why the service takes a
		// resolved window instead of deriving one from `Instant.now()`.
		var lastMonth = service.forCaller(Funnel.ADS, window("last-month"));
		assertThat(lastMonth.from()).isEqualTo(java.time.LocalDate.parse("2026-07-01"));
		assertThat(lastMonth.to()).isEqualTo(java.time.LocalDate.parse("2026-07-31"));
	}

	/**
	 * <strong>The counts never come from the rows, and this is the test that says so.</strong>
	 *
	 * <p>This is the fix for a real failure: the email marketing pipeline holds ~11,432
	 * opportunities over a year, counting them by pagination cost 115 sequential GHL requests, and
	 * the browser gave up at its 15s timeout — the Year view simply did not render. Counts now
	 * come from GHL's own match count, one request per stage, so they are exact at any size.
	 *
	 * <p>Asserted by making the counts enormous and proving **no row read happened at all**: the
	 * funnel is fully populated, and `opportunitiesIn` was never called.
	 */
	@Test
	void countsAHugePeriodWithoutReadingASingleRow() {
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(PIPELINE);
		given(ghl.countIn(eq("pipe-1"), eq("new"), any(), any())).willReturn(11_364);
		given(ghl.countIn(eq("pipe-1"), eq("warm"), any(), any())).willReturn(20);
		given(ghl.countIn(eq("pipe-1"), eq("won"), any(), any())).willReturn(48);
		// **Rows are stubbed EMPTY on purpose, and that is the assertion.** If any figure below were
		// derived from rows it would come back 0, because there are none to derive it from.
		given(ghl.opportunitiesIn(eq("pipe-1"), any(), any())).willReturn(List.of());

		var funnel = service(Duration.ofMinutes(5)).forCaller(Funnel.ADS, window("year"));

		// Exact, and adding up: the total is the sum of the stages rather than a separate figure
		// that could disagree with them on screen.
		assertThat(funnel.totalDeals()).isEqualTo(11_432);
		assertThat(funnel.stages()).extracting(MarketingPipelineService.StageFunnel::deals)
				.containsExactly(11_364, 20, 48, 0);
		assertThat(funnel.stages()).extracting(MarketingPipelineService.StageFunnel::sharePct)
				.containsExactly(99, 0, 0, 0);

		// **The counts survive having no rows at all**, which is the whole point: 11,432 rows would
		// be 115 sequential requests and the browser gave up at 15s, so the funnel above was built
		// from GHL's own match counts.
		//
		// This deliberately does NOT assert `never()).opportunitiesIn(...)`. It used to, and that
		// was a race it happened to win: a window this size returns TOTALLING and starts a
		// background reader, which then legitimately reads rows on another thread — so the
		// assertion was timing, not behaviour. `refusesRatherThanQueuesAWindowPastTheCeiling`
		// keeps a `never()` because a window past the ceiling starts no reader at all, which makes
		// it a real claim there.
		assertThat(funnel.detail()).isEqualTo(Detail.TOTALLING);
	}

	/**
	 * <strong>Above the row budget, money and sources are absent rather than partial.</strong>
	 *
	 * <p>GHL aggregates neither a sum nor a group-by, so both need every row. When there are too
	 * many, the honest answer is "not computed" — a total summed over whichever rows fitted looks
	 * exactly like a real total, and that is the confidently-wrong figure this screen keeps
	 * removing. **Null, never zero**: "not counted" and "worth nothing" are different claims.
	 */
	@Test
	void answersImmediatelyWithExactCountsAndTotalsInTheBackgroundOnAHugePeriod() {
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(PIPELINE);
		given(ghl.countIn(eq("pipe-1"), eq("new"), any(), any())).willReturn(11_364);
		given(ghl.opportunitiesIn(eq("pipe-1"), any(), any())).willReturn(List.of());

		var funnel = service(Duration.ofMinutes(5)).forCaller(Funnel.ADS, window("year"));

		assertThat(funnel.detail()).isEqualTo(Detail.TOTALLING);
		assertThat(funnel.totalValue()).isNull();
		assertThat(funnel.stages()).extracting(MarketingPipelineService.StageFunnel::value)
				.containsOnlyNulls();
		assertThat(funnel.sources()).isEmpty();
		// The count is still exact and still stated. Only the figures that need rows are missing.
		assertThat(funnel.totalDeals()).isEqualTo(11_364);
	}

	/**
	 * The point of the whole background arrangement: the same window, asked again, eventually
	 * carries the money — with no second endpoint and nothing held by the caller.
	 */
	@Test
	void fillsTheMoneyInOnALaterReadOfTheSameWindow() throws InterruptedException {
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(PIPELINE);
		given(ghl.countIn(eq("pipe-1"), eq("new"), any(), any())).willReturn(11_364);
		given(ghl.opportunitiesIn(eq("pipe-1"), any(), any()))
				.willReturn(List.of(at("new", "1000", "Google Ads"), at("new", "2000", "Referral")));

		var service = service(Duration.ofMinutes(5));
		assertThat(service.forCaller(Funnel.ADS, window("year")).detail()).isEqualTo(Detail.TOTALLING);

		var settled = pollUntilSettled(service);

		assertThat(settled.detail()).isEqualTo(Detail.READY);
		assertThat(settled.totalValue()).isEqualByComparingTo("3000");
		assertThat(settled.sources()).hasSize(2);
		// Still exact, and still GHL's own count rather than the rows that were read.
		assertThat(settled.totalDeals()).isEqualTo(11_364);
	}

	/**
	 * A window past the ceiling is refused outright rather than queued. A screen polling for
	 * figures nobody is computing waits forever, which is the worse failure.
	 */
	@Test
	void refusesRatherThanQueuesAWindowPastTheCeiling() {
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(PIPELINE);
		given(ghl.countIn(eq("pipe-1"), eq("new"), any(), any())).willReturn(250_000);

		var funnel = service(Duration.ofMinutes(5)).forCaller(Funnel.ADS, window("year"));

		assertThat(funnel.detail()).isEqualTo(Detail.UNAVAILABLE);
		assertThat(funnel.totalValue()).isNull();
		assertThat(funnel.totalDeals()).isEqualTo(250_000);
		// Not one row read: the ceiling is checked against GHL's own count first.
		then(ghl).should(never()).opportunitiesIn(anyString(), any(), any());
	}

	/**
	 * A background read that blows up must land as UNAVAILABLE, not stay TOTALLING — otherwise the
	 * screen polls for something that already failed until the TTL runs out.
	 */
	@Test
	void marksTheWindowUnavailableWhenTheBackgroundReadFails() throws InterruptedException {
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(PIPELINE);
		given(ghl.countIn(eq("pipe-1"), eq("new"), any(), any())).willReturn(11_364);
		willThrow(new GhlUnavailableException("GHL refused the pipeline read with HTTP 429"))
				.given(ghl).opportunitiesIn(eq("pipe-1"), any(), any());

		var service = service(Duration.ofMinutes(5));
		service.forCaller(Funnel.ADS, window("year"));

		assertThat(pollUntilSettled(service).detail()).isEqualTo(Detail.UNAVAILABLE);
	}

	/**
	 * Re-reads the window until the background thread has finished with it.
	 *
	 * <p>A poll rather than a latch because it is exactly what the screen does, so the test
	 * exercises the real handover — the cache — rather than a seam opened for it. Bounded so a
	 * regression that never settles fails in seconds instead of hanging the build.
	 */
	private MarketingPipelineService.MarketingPipeline pollUntilSettled(MarketingPipelineService service)
			throws InterruptedException {
		for (int attempt = 0; attempt < 100; attempt++) {
			var funnel = service.forCaller(Funnel.ADS, window("year"));
			if (funnel.detail() != Detail.TOTALLING) {
				return funnel;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("the background total never settled within 5s");
	}

	/** A period small enough to read keeps every figure it always had. */
	@Test
	void stillTotalsTheMoneyAndSourcesWhenThePeriodIsSmallEnoughToRead() {
		var funnel = read(List.of(at("new", "1000", "Google Ads"), at("warm", "2000", "Referral")));

		assertThat(funnel.detail()).isEqualTo(Detail.READY);
		assertThat(funnel.totalValue()).isEqualByComparingTo("3000");
		assertThat(funnel.sources()).hasSize(2);
		assertThat(funnel.stages().getFirst().value()).isEqualByComparingTo("1000");
	}

	@Test
	void neverServesTheOldFigureAfterAFailedRefresh() {
		List<Opportunity> one = List.of(at("new", "1000", "Google Ads"));
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(PIPELINE);
		stubCounts("pipe-1", PIPELINE, one);
		given(ghl.opportunitiesIn(eq("pipe-1"), any(), any())).willReturn(one);

		MarketingPipelineService service = service(Duration.ZERO);
		assertThat(service.forCaller(Funnel.ADS, window("month")).totalDeals()).isEqualTo(1);

		// GHL then goes down. The screen must say so — a figure kept from the last good read and
		// presented without a failure is the "looks live and is not" failure the cache would
		// otherwise introduce.
		willThrow(new GhlUnavailableException("down")).given(ghl).opportunitiesIn(eq("pipe-1"), any(), any());
		assertThatThrownBy(() -> service.forCaller(Funnel.ADS, window("month"))).isInstanceOf(GhlUnavailableException.class);
	}

	@Test
	void doesNotSearchForOpportunitiesWhenThePipelineIsNotThere() {
		// A misconfigured or renamed pipeline fails at the lookup, before a paginated search is
		// spent on an id that does not exist.
		willThrow(new GhlUnavailableException("no such pipeline")).given(ghl).pipelineNamed(PIPELINE_NAME);

		assertThatThrownBy(() -> service(Duration.ofMinutes(5)).forCaller(Funnel.ADS, window("month")))
				.isInstanceOf(GhlUnavailableException.class);
		then(ghl).should(never()).opportunitiesIn(anyString(), any(), any());
	}
}
