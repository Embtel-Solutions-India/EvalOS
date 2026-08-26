package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import com.ie.evalos.common.DateRange;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlPipelineClient.Opportunity;
import com.ie.evalos.integration.GhlPipelineClient.Pipeline;
import com.ie.evalos.integration.GhlUnavailableException;
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
		return service(Duration.ofMinutes(5)).forCaller(Funnel.ADS, DateRange.MONTH);
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

	private MarketingPipelineService service(Duration ttl) {
		return new MarketingPipelineService(ghl, PIPELINE_NAME, EMAIL_PIPELINE_NAME, ttl);
	}

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

		var funnel = service(Duration.ofMinutes(5)).forCaller(Funnel.ADS, DateRange.MONTH);

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
		cached.forCaller(Funnel.ADS, DateRange.MONTH);
		cached.forCaller(Funnel.ADS, DateRange.MONTH);
		then(ghl).should(times(1)).opportunitiesIn(eq("pipe-1"), any(), any());

		// A zero TTL is always stale, so the second caller goes back out. Proves the freshness
		// check is a comparison and not a "have we ever read this" flag.
		MarketingPipelineService uncached = service(Duration.ZERO);
		uncached.forCaller(Funnel.ADS, DateRange.MONTH);
		uncached.forCaller(Funnel.ADS, DateRange.MONTH);
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
		service.forCaller(Funnel.ADS, DateRange.MONTH);
		service.forCaller(Funnel.ADS, DateRange.YEAR);
		// Two distinct windows, so two reads even though both are inside the TTL.
		then(ghl).should(times(2)).opportunitiesIn(eq("pipe-1"), any(), any());

		// And each is now cached under its own key rather than evicting the other.
		service.forCaller(Funnel.ADS, DateRange.MONTH);
		service.forCaller(Funnel.ADS, DateRange.YEAR);
		then(ghl).should(times(2)).opportunitiesIn(eq("pipe-1"), any(), any());

		// The payload states which window it describes, so the screen can never label it wrongly.
		assertThat(service.forCaller(Funnel.ADS, DateRange.YEAR).range()).isEqualTo("year");
		assertThat(service.forCaller(Funnel.ADS, DateRange.MONTH).range()).isEqualTo("month");
	}

	/**
	 * <strong>The cache is keyed by funnel as well, for exactly the reason it is keyed by period.</strong>
	 *
	 * <p>Two funnels share this service and every field of the payload except the numbers — same
	 * stage names, same sources, same shape. So the ads funnel served under the email screen's
	 * heading would look entirely plausible for a whole TTL: nothing on screen and nothing in a
	 * log would contradict it. Asserted by reading both and proving each pipeline was looked up by
	 * its own name.
	 */
	@Test
	void cachesEachFunnelSeparatelySoOnePipelineNeverAnswersForAnother() {
		Pipeline email = new Pipeline("pipe-2", EMAIL_PIPELINE_NAME,
				List.of(new Pipeline.Stage("new", "New Lead", 0)));
		List<Opportunity> ads = List.of(at("new", "1000", "Google Ads"));
		List<Opportunity> emails = List.of(at("new", "2000", "Email"), at("new", "3000", "Email"));
		given(ghl.pipelineNamed(PIPELINE_NAME)).willReturn(PIPELINE);
		given(ghl.pipelineNamed(EMAIL_PIPELINE_NAME)).willReturn(email);
		stubCounts("pipe-1", PIPELINE, ads);
		stubCounts("pipe-2", email, emails);
		given(ghl.opportunitiesIn(eq("pipe-1"), any(), any())).willReturn(ads);
		given(ghl.opportunitiesIn(eq("pipe-2"), any(), any())).willReturn(emails);

		MarketingPipelineService service = service(Duration.ofMinutes(5));

		assertThat(service.forCaller(Funnel.ADS, DateRange.MONTH).totalDeals()).isEqualTo(1);
		assertThat(service.forCaller(Funnel.EMAIL, DateRange.MONTH).totalDeals()).isEqualTo(2);
		// The name is the routing, so each funnel must have resolved its own configured one.
		assertThat(service.forCaller(Funnel.EMAIL, DateRange.MONTH).pipelineName()).isEqualTo(EMAIL_PIPELINE_NAME);
		assertThat(service.forCaller(Funnel.ADS, DateRange.MONTH).pipelineName()).isEqualTo(PIPELINE_NAME);

		// Both inside one TTL: two reads total, one per funnel, and neither evicted the other.
		then(ghl).should(times(1)).opportunitiesIn(eq("pipe-1"), any(), any());
		then(ghl).should(times(1)).opportunitiesIn(eq("pipe-2"), any(), any());
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
		var month = service.forCaller(Funnel.ADS, DateRange.MONTH);
		var year = service.forCaller(Funnel.ADS, DateRange.YEAR);

		// Same end, earlier start. `to` is "today" in the business's zone for both.
		assertThat(year.to()).isEqualTo(month.to());
		assertThat(year.from()).isBefore(month.from());
		// 30 and 365 days back, which is the vocabulary DateRange owns.
		assertThat(month.from()).isEqualTo(month.to().minusDays(30));
		assertThat(year.from()).isEqualTo(year.to().minusDays(365));
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

		var funnel = service(Duration.ofMinutes(5)).forCaller(Funnel.ADS, DateRange.YEAR);

		// Exact, and adding up: the total is the sum of the stages rather than a separate figure
		// that could disagree with them on screen.
		assertThat(funnel.totalDeals()).isEqualTo(11_432);
		assertThat(funnel.stages()).extracting(MarketingPipelineService.StageFunnel::deals)
				.containsExactly(11_364, 20, 48, 0);
		assertThat(funnel.stages()).extracting(MarketingPipelineService.StageFunnel::sharePct)
				.containsExactly(99, 0, 0, 0);

		// **Not one row was read.** This is the whole point — 11,432 rows is 115 requests.
		then(ghl).should(never()).opportunitiesIn(anyString(), any(), any());
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

		var funnel = service(Duration.ofMinutes(5)).forCaller(Funnel.ADS, DateRange.YEAR);

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
		assertThat(service.forCaller(Funnel.ADS, DateRange.YEAR).detail()).isEqualTo(Detail.TOTALLING);

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

		var funnel = service(Duration.ofMinutes(5)).forCaller(Funnel.ADS, DateRange.YEAR);

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
		service.forCaller(Funnel.ADS, DateRange.YEAR);

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
			var funnel = service.forCaller(Funnel.ADS, DateRange.YEAR);
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
		assertThat(service.forCaller(Funnel.ADS, DateRange.MONTH).totalDeals()).isEqualTo(1);

		// GHL then goes down. The screen must say so — a figure kept from the last good read and
		// presented without a failure is the "looks live and is not" failure the cache would
		// otherwise introduce.
		willThrow(new GhlUnavailableException("down")).given(ghl).opportunitiesIn(eq("pipe-1"), any(), any());
		assertThatThrownBy(() -> service.forCaller(Funnel.ADS, DateRange.MONTH)).isInstanceOf(GhlUnavailableException.class);
	}

	@Test
	void doesNotSearchForOpportunitiesWhenThePipelineIsNotThere() {
		// A misconfigured or renamed pipeline fails at the lookup, before a paginated search is
		// spent on an id that does not exist.
		willThrow(new GhlUnavailableException("no such pipeline")).given(ghl).pipelineNamed(PIPELINE_NAME);

		assertThatThrownBy(() -> service(Duration.ofMinutes(5)).forCaller(Funnel.ADS, DateRange.MONTH))
				.isInstanceOf(GhlUnavailableException.class);
		then(ghl).should(never()).opportunitiesIn(anyString(), any(), any());
	}
}
