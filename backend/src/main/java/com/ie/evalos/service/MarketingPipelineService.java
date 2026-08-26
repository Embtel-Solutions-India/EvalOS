package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.ie.evalos.common.DateRange;
import com.ie.evalos.integration.GhlPipelineClient;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * GHL's marketing funnels, counted and totalled for the GM's marketing view.
 *
 * <p><strong>Read-only, and outside the brand-scoping rule rather than exempt from it.</strong>
 * Every other query in EvalOS filters by {@code brand_id} because it reads EvalOS rows that
 * belong to a brand. This reads no EvalOS rows at all: it reads the one GHL sub-account named by
 * {@code evalos.ghl.location-id}, which is a <em>global</em> setting with no link to any brand.
 *
 * <p><strong>Note what that does and does not mean, because the first version of this comment
 * got it wrong.</strong> It claimed the brands <em>share</em> one GHL sub-account, making the
 * figure "cross-brand by construction". They do not — each brand has its own sub-account, and
 * the first real credential is one brand's. So these figures are <em>one</em> brand's funnel;
 * EvalOS simply has no mapping from a location to a brand, so it cannot say which, cannot label
 * it, and cannot filter by it.
 *
 * <p>The endpoint is still GM-only, for the corrected reason: an <em>unattributable</em> figure
 * must not be shown to a role locked to a single brand, because neither they nor the server can
 * tell whether it is theirs. <strong>Do not add a {@code brandId} parameter to make it look
 * scoped</strong> — it would narrow nothing while implying it had. Unit 25 puts the location on
 * {@code brand} and closes this properly.
 *
 * <p>This is a marketing <em>reading</em>, not marketing work. EvalOS still does no marketing,
 * no sales and no invoicing — it does not create a lead, move a stage, or send a campaign. What
 * it does is let the GM see the top of the funnel beside the production numbers that come out of
 * the bottom of it, which is a question they could previously only answer by leaving the app.
 *
 * <p><strong>Two funnels, one service, and that is the whole of the second one.</strong> The
 * Google Ads pipeline and the email marketing pipeline live in the same GHL location, hold the
 * same stage shape and answer the same question, so a second service reading a second pipeline
 * would have been this class with one string changed. What varies is the configured name, which
 * is why {@link Funnel} is a key into configuration and not a subclass, a strategy or a second
 * bean.
 *
 * <p><strong>The whole payload is cached for one TTL and shared by every caller.</strong> Not an
 * optimisation: without it, five open dashboards polling their tiles are five GHL reads a
 * minute, each of them a multi-page pagination, and GHL's rate limit becomes an EvalOS outage.
 * The cache is per instance and deliberately not distributed — a few seconds of skew between two
 * app instances on a funnel count is invisible, and a shared cache is infrastructure this buys
 * nothing from.
 */
@Service
public class MarketingPipelineService {

	private static final Logger log = LoggerFactory.getLogger(MarketingPipelineService.class);

	/**
	 * Which of GHL's pipelines a read is for.
	 *
	 * <p><strong>A closed vocabulary rather than a pipeline name on the query string.</strong> The
	 * configured location holds seven pipelines and most of them are other teams' — a name
	 * parameter would let any GM read every one of them, and would make what this screen shows an
	 * argument the caller chooses rather than a deployment decision. Each constant resolves to one
	 * configured name, so what EvalOS can see is fixed by configuration and what a caller may ask
	 * for is fixed by this enum. It is also what keeps the client's own refusal to list the other
	 * pipelines' names in an error meaningful.
	 */
	public enum Funnel {

		/** {@code evalos.ghl.ads-pipeline-name} — the paid-search sales funnel. */
		ADS,

		/** {@code evalos.ghl.email-pipeline-name} — the email marketing funnel. */
		EMAIL
	}

	/**
	 * What a stage <em>means</em>, worked out from its name.
	 *
	 * <p><strong>The stage name is the truth here, not GHL's {@code status} field.</strong> That
	 * field is unreliable in this account and provably so: 144 opportunities sit in the stage
	 * named "Won" while only 3 of them carry {@code status: "won"} — GHL leaves the rest as
	 * {@code open} because nobody pressed its separate win button. A funnel that reported 3 wins
	 * over a column of 144 would be arithmetically defensible and completely useless.
	 *
	 * <p>So a stage whose name matches one of GHL's own status words <strong>is</strong> that
	 * outcome, matched <strong>ignoring case and surrounding space</strong>: {@code Won}, {@code won}
	 * and {@code WON} are one thing, because they are one thing to the person who typed them.
	 *
	 * <p>Everything else is {@link #OPEN} — including {@code Cold}, which reads like an ending but
	 * is not one of GHL's four statuses and is not silently promoted into one. Applies to both
	 * funnels by construction: the rule is on the name, so no pipeline is special-cased.
	 */
	public enum Outcome {

		WON, LOST, ABANDONED,

		/** Still in flight, or a stage whose name is not a status word at all. */
		OPEN;

		/** The outcome a stage's name spells, or {@link #OPEN} when it spells none of them. */
		static Outcome ofStageNamed(String stageName) {
			if (stageName != null) {
				for (Outcome outcome : values()) {
					if (outcome != OPEN && outcome.name().equalsIgnoreCase(stageName.trim())) {
						return outcome;
					}
				}
			}
			return OPEN;
		}
	}

	/**
	 * Whether the money and source figures are in this payload, on their way, or not coming.
	 *
	 * <p><strong>Three states because there are three, and collapsing them lies.</strong> The
	 * boolean this replaced could not tell "we have not totalled this yet" from "we will not" —
	 * and those ask the reader for opposite things: wait, versus narrow the period.
	 *
	 * <p>Counts are exact in all three. Only the sum and the group-by are ever missing, because
	 * only those need every row.
	 */
	public enum Detail {

		/** Money and sources are computed and present. */
		READY,

		/**
		 * Too many rows to total inside the request, so a background read is running and this
		 * payload carries counts only. The same window asked for again returns {@link #READY}
		 * once that finishes — the screen polls rather than blocking a request thread on it.
		 */
		TOTALLING,

		/**
		 * They will not arrive: the window is past {@link #DETAIL_ROW_CEILING}, or the background
		 * read failed. Distinct from {@code TOTALLING} so the screen stops waiting and says so.
		 */
		UNAVAILABLE
	}

	/**
	 * One stage of the funnel: how many deals stand in it and what they are worth.
	 *
	 * @param deals    how many, from GHL's own match count. <strong>Always exact</strong>, whatever
	 *                 the period holds — it costs one request per stage rather than one per
	 *                 hundred deals
	 * @param value    what they are worth. <strong>Null when the period was too large to total</strong>
	 *                 — see {@code detail}. Null, never zero: "not counted" and "worth
	 *                 nothing" are different claims and the screen must not merge them
	 * @param sharePct share of the pipeline's deals, rounded. <strong>Null when the pipeline is
	 *                 empty, never zero</strong> — the codebase's rule for every rate: no deals
	 *                 at all is not the same claim as 0% of them being here
	 * @param outcome  what this stage means, from its name — see {@link Outcome}. Sent rather than
	 *                 derived in the browser so the rule lives in one place
	 */
	public record StageFunnel(String stageId, String name, int deals, BigDecimal value, Integer sharePct,
			Outcome outcome) {
	}

	/**
	 * Where the deals came from, as GHL recorded it.
	 *
	 * <p>{@code source} is GHL's own free-text opportunity source, not an EvalOS
	 * {@code SourceChannel}. Passed through verbatim rather than mapped onto the enum: the enum
	 * describes how a <em>case</em> reached EvalOS, and forcing a marketing string into it would
	 * either drop the values that do not fit or quietly widen a domain vocabulary to hold
	 * whatever anybody typed into GHL.
	 *
	 * <p><strong>Verbatim, but grouped case-insensitively.</strong> These strings are typed by
	 * hand into campaigns and forms over months, so "Application Form" and "Application form" are
	 * the same source with two spellings — and splitting them into two rows halves a figure for a
	 * reason that is invisible on screen. The <em>first</em> spelling seen is the one displayed,
	 * because inventing a canonical casing would show a label that exists nowhere in GHL.
	 */
	public record SourceRow(String source, int deals, BigDecimal value) {
	}

	/**
	 * @param readAt when GHL was actually asked. Sent so the screen can say how old the figures
	 *               are — a cached number with no timestamp reads as live when it is not
	 * @param range  the period these figures cover, echoed back. The screen shows it so the
	 *               numbers can never be read against the wrong window — if the control and the
	 *               payload ever disagree, the payload is what the figures actually mean
	 * @param totalValue what the period's deals are worth, or <strong>null when the period was too
	 *                  large to total</strong>. See {@code detail}
	 * @param detail whether the money and source breakdown is present, coming, or not coming.
	 *                  <p><strong>The counts never depend on it.</strong> {@code totalDeals} and
	 *                  every stage's {@code deals} come from GHL's own match count and are exact
	 *                  for any period. A <em>sum</em> and a <em>group-by</em> are different: GHL
	 *                  aggregates neither, so they need every row, and the email pipeline's year is
	 *                  11,443 of them — 115 cursor pages that cannot be parallelised, which at
	 *                  GHL's 100-per-10s limit is ~13s of pacing minimum and past the browser's own
	 *                  15s timeout.
	 *                  <p>So above {@link #INLINE_ROW_BUDGET} the payload comes back
	 *                  {@link Detail#TOTALLING} with {@code totalValue} and each stage's
	 *                  {@code value} null and {@code sources} empty, a background read starts, and
	 *                  the same window answers {@link Detail#READY} once it lands. **A partial total
	 *                  is never shown** — a sum over the first 5,000 of 11,443 rows looks exactly
	 *                  like a real number
	 * @param from   first day of the created-at window, inclusive
	 * @param to     last day of it, inclusive. Sent because an empty chart has to be able to say
	 *               <em>which</em> days found nothing, and computing that in the browser would put
	 *               the window arithmetic in two places
	 */
	public record MarketingPipeline(String pipelineName, int totalDeals, BigDecimal totalValue,
			List<StageFunnel> stages, List<SourceRow> sources, Instant readAt,
			String range, LocalDate from, LocalDate to, Detail detail) {

		/** The same figures, but with the money and sources declared not coming. */
		MarketingPipeline unavailable() {
			return new MarketingPipeline(pipelineName, totalDeals, null, stages, List.of(), readAt,
					range, from, to, Detail.UNAVAILABLE);
		}
	}

	/** What a blank or absent GHL source is called, rather than an empty row label. */
	private static final String UNATTRIBUTED = "Unattributed";

	/**
	 * How many opportunity rows this will read <em>inside the request</em> to total the money and
	 * sources.
	 *
	 * <p>Ten pages, so a little over a second at the client's pacing. Counts spend none of it —
	 * those come from {@link GhlPipelineClient#countIn} and are exact at any size — so this bounds
	 * only the extra work a sum and a group-by need.
	 *
	 * <p><strong>This is now a latency line, not a capability one.</strong> It used to be the
	 * point past which the figures did not exist; above it the answer was "too many to total". They
	 * exist now — the read just moves to a background thread and the screen picks it up on its next
	 * poll. So the only question this decides is whether the caller waits ~1s or gets counts
	 * immediately and money shortly after.
	 */
	private static final int INLINE_ROW_BUDGET = 1_000;

	/**
	 * How many rows this will read <strong>at all</strong>, background included.
	 *
	 * <p>~1,000 pages, which at the client's 110ms pacing is about two minutes of one background
	 * thread. Well past both real pipelines — the email funnel's worst window is 11,443 — and there
	 * to stop a misconfigured location pointing this at something enormous and pinning a thread on
	 * it for an hour.
	 *
	 * <p>Checked against GHL's own count before a single page is fetched, so going over costs one
	 * request per stage and nothing more. Over it the payload says {@link Detail#UNAVAILABLE},
	 * which asks the reader to narrow the period rather than to wait for something that is not
	 * coming.
	 */
	private static final int DETAIL_ROW_CEILING = 100_000;

	private final GhlPipelineClient ghl;
	private final Map<Funnel, String> pipelineNames;
	private final Duration cacheTtl;

	/**
	 * One cached payload <strong>per funnel and period</strong>, and neither half of the key is
	 * optional.
	 *
	 * <p>This was a single {@code AtomicReference} while there was one funnel and no period
	 * selector. Each time a dimension was added, an unkeyed slot would have served whichever
	 * combination was fetched first to every other one — Month's figures under a Year label, or
	 * the ads funnel under the email screen's heading, for a whole TTL, with no error anywhere.
	 * That is the worst failure this screen has: confidently wrong numbers. Bounded by
	 * construction, since both halves are small enums.
	 */
	private final Map<CacheKey, MarketingPipeline> cached = new ConcurrentHashMap<>();

	private record CacheKey(Funnel funnel, DateRange range) {
	}

	/**
	 * The windows whose background total is already running, so a screen polling every few seconds
	 * queues one read rather than one per poll.
	 *
	 * <p>Entries are added before submission and removed in a {@code finally}, so a read that
	 * throws does not wedge the key permanently — the next caller past the TTL starts a fresh one.
	 */
	private final java.util.Set<CacheKey> totalling = ConcurrentHashMap.newKeySet();

	/**
	 * Where a window too large to total inline gets totalled.
	 *
	 * <p><strong>One thread, and that is a rate-limit decision rather than a load one.</strong>
	 * GHL's 100-per-10s ceiling is per location and every one of these reads points at the same
	 * location, so two background reads running at once do not go twice as fast — the client's
	 * pacing simply makes them share the same budget, while both take twice as long and each looks
	 * stuck. Serialising them means the first window asked for is the first one finished.
	 *
	 * <p>Daemon, so a shutdown is never held open by a read whose result nobody is waiting for.
	 */
	private final ExecutorService totaller = Executors.newSingleThreadExecutor((runnable) -> {
		Thread thread = new Thread(runnable, "ghl-totaller");
		thread.setDaemon(true);
		return thread;
	});

	@PreDestroy
	void stopTotalling() {
		totaller.shutdownNow();
	}

	MarketingPipelineService(GhlPipelineClient ghl,
			@Value("${evalos.ghl.ads-pipeline-name}") String adsPipelineName,
			@Value("${evalos.ghl.email-pipeline-name}") String emailPipelineName,
			@Value("${evalos.ghl.cache-ttl}") Duration cacheTtl) {
		this.ghl = ghl;
		this.pipelineNames = new EnumMap<>(Map.of(Funnel.ADS, adsPipelineName, Funnel.EMAIL, emailPipelineName));
		this.cacheTtl = cacheTtl;
	}

	/**
	 * The funnel, from cache when it is fresh and from GHL when it is not.
	 *
	 * <p>Two callers racing past a stale entry both call GHL and the second write wins. That is
	 * left unguarded on purpose: the alternative is holding a lock across a network call, which
	 * turns one slow upstream request into every dashboard blocking on it. Two identical reads
	 * is the cheaper failure.
	 */
	public MarketingPipeline forCaller(Funnel funnel, DateRange range) {
		CacheKey key = new CacheKey(funnel, range);
		MarketingPipeline current = cached.get(key);
		if (current != null && Duration.between(current.readAt(), Instant.now()).compareTo(cacheTtl) < 0) {
			return current;
		}
		MarketingPipeline fresh = read(funnel, range, false);
		// Only a successful read replaces the entry. A GhlUnavailableException propagates from
		// read() and leaves the previous value alone — but it is NOT served, because a failed
		// refresh must not be reported as a live figure. The screen shows the error instead.
		cached.put(key, fresh);
		if (fresh.detail() == Detail.TOTALLING) {
			startTotalling(key, funnel, range);
		}
		return fresh;
	}

	/**
	 * Reads the money and sources for a window too large to do inline, and replaces the cached
	 * payload with the complete one.
	 *
	 * <p><strong>The cache is the delivery mechanism, not a side effect.</strong> There is no
	 * queue, no job table and nothing for the client to hold onto: the screen polls the same URL
	 * it already polls, and one of those polls happens to be the one where the entry has become
	 * {@link Detail#READY}. That is why this needs no new endpoint and survives a browser refresh
	 * — the state lives with the figures rather than with the caller.
	 *
	 * <p>A failure is written back as {@link Detail#UNAVAILABLE} rather than left as
	 * {@code TOTALLING}, because a screen polling for something that already failed would wait
	 * forever. The entry still ages out on the normal TTL, so the next reader retries.
	 */
	private void startTotalling(CacheKey key, Funnel funnel, DateRange range) {
		if (!totalling.add(key)) {
			return;
		}
		totaller.execute(() -> {
			try {
				log.info("Totalling {} {} in the background", funnel, range.wireName());
				cached.put(key, read(funnel, range, true));
			}
			catch (RuntimeException ex) {
				log.error("Background total for {} {} failed", funnel, range.wireName(), ex);
				MarketingPipeline pending = cached.get(key);
				if (pending != null) {
					cached.put(key, pending.unavailable());
				}
			}
			finally {
				totalling.remove(key);
			}
		});
	}

	/**
	 * @param totalWhateverTheSize read every row for the money and sources even when there are too
	 *                             many to do inside a request. True only on the background thread
	 */
	private MarketingPipeline read(Funnel funnel, DateRange range, boolean totalWhateverTheSize) {
		Instant now = Instant.now();
		// **The window is whole days in the business's own zone, not UTC's.** `BusinessCalendar`
		// already owns "what day is it here" for every SLA in the app, and GHL's filter is
		// date-only — so resolving the edges anywhere else would make "today" mean a different day
		// to this screen than to the rest of EvalOS.
		LocalDate to = LocalDate.ofInstant(now, BusinessCalendar.ZONE);
		LocalDate from = LocalDate.ofInstant(range.startFrom(now), BusinessCalendar.ZONE);

		GhlPipelineClient.Pipeline pipeline = ghl.pipelineNamed(pipelineNames.get(funnel));

		// **Counts first, and they cost one request per stage whatever the period holds.** This
		// is the whole reason a Year on a five-figure pipeline renders at all: it used to page
		// every row to count them — 115 requests on the email funnel — and the browser gave up at
		// 15s. Ordered by GHL's own `position` here so the funnel is built once, in order.
		List<GhlPipelineClient.Pipeline.Stage> stages = pipeline.stages().stream()
				.sorted(Comparator.comparingInt(GhlPipelineClient.Pipeline.Stage::position))
				.toList();
		Map<String, Integer> deals = new LinkedHashMap<>();
		for (GhlPipelineClient.Pipeline.Stage stage : stages) {
			deals.put(stage.id(), ghl.countIn(pipeline.id(), stage.id(), from, to));
		}
		// The pipeline's total is the sum of its stages rather than a seventh call for it. Every
		// opportunity stands in exactly one stage, so the sum IS the total — and taking it this
		// way means the parts can never fail to add up to the whole on screen.
		int totalDeals = deals.values().stream().mapToInt(Integer::intValue).sum();

		// **The rows are only for money and sources.** GHL aggregates neither, so both need every
		// row — one request per hundred, cursor-paged and therefore sequential. Small windows pay
		// that inline; large ones come back TOTALLING and a background thread pays it. Either way a
		// partial total is never shown: a sum over whichever fraction fitted reads exactly like a
		// real one.
		Detail detail = detailFor(totalDeals, totalWhateverTheSize);
		List<GhlPipelineClient.Opportunity> opportunities = detail == Detail.READY
				? ghl.opportunitiesIn(pipeline.id(), from, to)
				: List.of();

		return new MarketingPipeline(pipeline.name(), totalDeals,
				detail == Detail.READY ? sumOf(opportunities) : null,
				funnel(stages, deals, opportunities, totalDeals, detail == Detail.READY),
				sources(opportunities), Instant.now(),
				range.wireName(), from, to, detail);
	}

	/** Total it now, hand it to the background thread, or refuse it outright. */
	private static Detail detailFor(int totalDeals, boolean totalWhateverTheSize) {
		if (totalDeals > DETAIL_ROW_CEILING) {
			return Detail.UNAVAILABLE;
		}
		return totalWhateverTheSize || totalDeals <= INLINE_ROW_BUDGET ? Detail.READY : Detail.TOTALLING;
	}

	/** What a list of opportunities is worth. Unpriced ones count as nothing, not as a failure. */
	private static BigDecimal sumOf(List<GhlPipelineClient.Opportunity> opportunities) {
		return opportunities.stream()
				.map(MarketingPipelineService::valueOf)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/**
	 * Every stage the pipeline has, in GHL's own {@code position} order.
	 *
	 * <p><strong>An empty stage is a row, not an omission.</strong> A funnel that silently drops
	 * the stages nobody is in looks shorter than it is, and "nothing is sitting in Warm" is one
	 * of the more useful things this screen can say.
	 *
	 * <p>The stage list comes from GHL and no stage name is special-cased here. That matters:
	 * both pipelines happen to end Won / Cold / Lost today, and hard-coding those would make a
	 * rename in GHL a silent hole in the screen.
	 *
	 * <p><strong>The count and the value come from different places, on purpose.</strong> {@code
	 * deals} is GHL's own match count, so it is exact for a period of any size; {@code value} is
	 * summed from rows and is therefore null when the period was too large to read them. A stage
	 * showing a real count beside a blank value is the honest rendering of that, and much better
	 * than a value summed over whichever rows happened to fit.
	 */
	private static List<StageFunnel> funnel(List<GhlPipelineClient.Pipeline.Stage> stages,
			Map<String, Integer> deals, List<GhlPipelineClient.Opportunity> opportunities,
			int totalDeals, boolean detailAvailable) {
		return stages.stream()
				.map((stage) -> {
					int count = deals.getOrDefault(stage.id(), 0);
					BigDecimal value = detailAvailable
							? sumOf(opportunities.stream()
									.filter((opportunity) -> stage.id().equals(opportunity.pipelineStageId()))
									.toList())
							: null;
					return new StageFunnel(stage.id(), stage.name(), count, value,
							totalDeals == 0 ? null : Math.round(count * 100f / totalDeals),
							Outcome.ofStageNamed(stage.name()));
				})
				.toList();
	}

	/**
	 * Sources by weight of deals, heaviest first, with value breaking a tie.
	 *
	 * <p>Keyed on the lower-cased name so two spellings of one source are one row; the row keeps
	 * whichever spelling arrived first as its label. {@code Locale.ROOT} rather than the default
	 * locale, because a Turkish-locale JVM lower-cases {@code I} to a dotless {@code ı} and would
	 * split exactly the rows this is joining.
	 */
	private static List<SourceRow> sources(List<GhlPipelineClient.Opportunity> opportunities) {
		Map<String, SourceRow> byName = new LinkedHashMap<>();
		for (GhlPipelineClient.Opportunity opportunity : opportunities) {
			String name = opportunity.source() == null || opportunity.source().isBlank()
					? UNATTRIBUTED
					: opportunity.source().trim();
			byName.merge(name.toLowerCase(Locale.ROOT), new SourceRow(name, 1, valueOf(opportunity)),
					(a, b) -> new SourceRow(a.source(), a.deals() + b.deals(), a.value().add(b.value())));
		}
		return byName.values().stream()
				.sorted(Comparator.comparingInt(SourceRow::deals).reversed()
						.thenComparing(SourceRow::value, Comparator.reverseOrder()))
				.toList();
	}

	/** GHL sends no {@code monetaryValue} on an opportunity nobody priced. That is zero, not a 500. */
	private static BigDecimal valueOf(GhlPipelineClient.Opportunity opportunity) {
		return opportunity.monetaryValue() == null ? BigDecimal.ZERO : opportunity.monetaryValue();
	}
}
