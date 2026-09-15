package com.ie.evalos.integration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one thing EvalOS reads out of GHL's public API: a sales pipeline and the opportunities
 * standing in it.
 *
 * <p><strong>This class is a read and nothing else</strong> — no writes, no stage moves, no
 * opportunity creation. It feeds the three funnel <em>windows</em> (Units 24, 26, 27), and
 * keeping it read-only is what makes those screens provably incapable of changing what they
 * display.
 *
 * <p><strong>And it is a fact about EvalOS again.</strong> Unit 29 briefly amended the invariant
 * this was quoting — a sales desk wrote to GHL through a client of its own — and that desk was
 * removed. {@link GhlHttp} now exposes no write verb at all, so the old sentence holds once more:
 * the moment something here writes back, two systems own one pipeline.
 *
 * <p>The {@code RestClient}, the rate limiter and the error mapping live in {@link GhlHttp}, and
 * stay there now that this is the only client again — the 100-req/10s limit belongs to the GHL
 * <em>location</em>, not to whichever bean is reading it, so a pacer folded back in here is one
 * the next client would silently duplicate.
 *
 * <p><strong>Called inline from a request path, which the standards normally forbid.</strong>
 * The rule in {@code code-standards.md} is about a <em>lifecycle side effect</em> — those go
 * through a domain event so they cannot be lost. A dashboard read has nothing to lose: if this
 * call fails the screen says so and nothing in EvalOS is left half-done. Two things keep it
 * inside invariant 6's "one bounded request": {@link GhlHttp}'s timeout, and the service's cache, so a
 * room full of open dashboards is not a room full of GHL calls.
 *
 * <p>No opportunity rows are persisted <em>by this client, or by any other</em>. There is no
 * {@code ghl_opportunity} table and there must not be —
 * a stage a salesperson dragged five seconds ago would already be wrong in it, and the contact
 * snapshots EvalOS <em>does</em> hold are the ones a case needs, arriving by webhook. Unit 29
 * promoted that decision from incidental to load-bearing: a remote control with no copy has
 * nothing to fall out of date.
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
	 * against. The caller decides which days those are — {@code DateWindow} resolves them in
	 * {@code BusinessCalendar.ZONE}, so "today" means the business's today rather than UTC's.
	 */
	private static final DateTimeFormatter GHL_DATE = DateTimeFormatter.ofPattern("MM-dd-yyyy");

	/**
	 * Hard stop on the pagination loop — a runaway guard, and only that.
	 *
	 * <p><strong>Raised from 50, which was silently truncating.</strong> 50 pages is 5,000 rows,
	 * and the email funnel's year was 11,443 — so had the caller ever asked for that window, this
	 * loop would have returned the first 5,000 and logged a warning nobody reads, and the screen
	 * would have shown a total 56% short of the truth wearing no mark of it. A cap below what the
	 * caller may legitimately ask for is not a guard, it is a wrong answer with a log line.
	 *
	 * <p><strong>It is now the only bound, and that is a change worth knowing.</strong> The funnel
	 * screens checked GHL's own count before fetching a page and refused to read a window over a
	 * row ceiling; they were removed on 2026-09-16 and that pre-flight went with them. The one
	 * caller left, {@code GmOverviewService}, reads a month of one desk's creations and a lookback
	 * of its <em>wins</em> — both small by construction — so the ceiling is unnecessary rather
	 * than missing. Add it back with the next caller that can ask for a year.
	 */
	private static final int MAX_PAGES = 1_500;

	/** A pipeline and its stages. GHL carries the display order in {@code position}. */
	public record Pipeline(String id, String name, List<Stage> stages) {

		public record Stage(String id, String name, int position) {
		}
	}

	/**
	 * One opportunity, narrowed to the fields a dashboard reads.
	 *
	 * <p>The narrowing is still the point. GHL's search response also carries the contact's name,
	 * email, phone, tags, attributions and custom fields on every row — none of which a count or a
	 * sum needs, and all of which would then be marketing PII sitting inside an EvalOS response.
	 * Binding only these is what keeps it out of the payload, rather than a projection somebody has
	 * to remember.
	 *
	 * <p><strong>It grew from three fields to eight for the GM overview, and each addition answers
	 * a question the three could not.</strong> {@code status} and {@code lastStatusChangeAt} are
	 * the pair behind "won this month": GHL's {@code date}/{@code endDate} filter on
	 * <em>createdAt</em> — verified against the live API, not inferred — so a deal opened in August
	 * and won in September is outside every window that asks for September's creations. Nothing but
	 * the status-change timestamp can place a win in time. {@code createdAt} is bound for the same
	 * reason in reverse: once a read spans six months of wins, the rows have to be re-bucketed here
	 * rather than by the query. {@code lastStageChangeAt} is how long a deal has sat where it is,
	 * which is the one thing a Kanban cannot show.
	 *
	 * <p>An earlier note here said {@code status} was "deliberately absent" because the funnel's own
	 * stages already include Won, Cold and Lost. That held for <em>one marketing pipeline whose
	 * stages happen to name outcomes</em>. It does not hold across a salesperson's pipeline, the
	 * intake funnel and the email funnel at once, which is what this record now serves — there the
	 * only common outcome axis is the one GHL keeps on every opportunity.
	 *
	 * <p>{@code assignedTo} is a GHL user id and EvalOS has no mapping from one to a
	 * {@code team_member} — see {@code SalesOpportunityController}. It is bound so a future mapping
	 * has something to map, and no figure is derived from it: per-salesperson numbers come from
	 * {@code team_member.ghl_pipeline_id}, which is a link EvalOS actually holds. <strong>GHL owns
	 * the field outright</strong> ({@code 00d} §6.2) — a round-robin automation reassigning a deal
	 * is not a conflict to be undone.
	 *
	 * <p><strong>{@code name}, {@code contactId} and {@code updatedAt} joined at Unit 44d</strong>,
	 * when this record stopped feeding only a group-by and started feeding a mirror. A board needs
	 * the first two to draw a card, and {@code updatedAt} is the timestamp Unit 45's conflict
	 * policy compares on — with the rule that a <em>null</em> is an explicit conflict rather than
	 * "EvalOS is newer", because it is GHL-supplied and nullable.
	 *
	 * <p>This also absorbed {@code GhlOpportunityClient.BoardOpportunity}, a second projection of
	 * the same endpoint's rows. Two records over one URL is one of them going stale.
	 */
	public record Opportunity(String id, String name, String contactId, String pipelineId,
			String pipelineStageId, String status, BigDecimal monetaryValue, String source,
			String assignedTo, java.time.Instant createdAt, java.time.Instant updatedAt,
			java.time.Instant lastStatusChangeAt, java.time.Instant lastStageChangeAt) {
	}

	private final GhlHttp http;

	GhlPipelineClient(GhlHttp http) {
		this.http = http;
	}

	/**
	 * Every pipeline on the configured location, id and name.
	 *
	 * <p>Exists for Unit 36's assignment screen: {@code team_member.ghl_pipeline_id} holds an
	 * opaque 20-character id, and asking a GM to paste one is how the wrong id gets pasted. A
	 * wrong id there is <em>silent</em> — the employee sees an empty board and no error — so the
	 * list is the difference between a typo and a choice.
	 *
	 * <p>Read-only, like everything else here. Invariant 2 is untouched by this unit.
	 *
	 * @throws GhlUnavailableException if GHL is not configured here or refused the request
	 */
	public List<Pipeline> pipelines() {
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
		PipelinesResponse response = http.get(PipelinesResponse.class,
				(uri) -> uri.path("/opportunities/pipelines").queryParam("locationId", http.locationId()).build());
		return Optional.ofNullable(response.pipelines()).orElse(List.of());
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
		String wanted = squashed(name);
		return pipelines().stream()
				// Empty `wanted` matches nothing on purpose: a blank or unset name property must
				// fall through to the 502 below rather than silently bind to a pipeline GHL
				// happens to have returned without a name.
				.filter((pipeline) -> !wanted.isEmpty() && wanted.equalsIgnoreCase(squashed(pipeline.name())))
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
	 * <p><strong>The rows themselves, because a sum and a group-by need them</strong> and there is
	 * no GHL endpoint that aggregates either. {@code countIn} used to answer "how many" in a
	 * single request for the funnel screens and went with them on 2026-09-16; nothing counts a
	 * stage any more. The caller is responsible for only asking for a window it can afford.
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
		return opportunitiesIn(pipelineId, from, to, null);
	}

	/**
	 * Every opportunity on a pipeline, with no window at all — what a mirror needs (Unit 44d).
	 *
	 * <p><strong>A date window is the wrong shape for a mirror.</strong> {@code date}/{@code endDate}
	 * filter on {@code createdAt}, so any window silently excludes the deals that have been open
	 * longest — which are exactly the ones a board must show and a drift audit must compare. The
	 * funnel screens could afford a window because they answered a question about a period; a
	 * mirror answers "what is there".
	 *
	 * <p>This is the read that replaced {@code GhlOpportunityClient}, a second client on the same
	 * endpoint with a narrower projection of the same rows.
	 */
	public List<Opportunity> allIn(String pipelineId) {
		return opportunitiesIn(pipelineId, null, null, null);
	}

	/**
	 * The same read, narrowed to one GHL status.
	 *
	 * <p><strong>Exists for one figure: "won this month".</strong> The window above filters on
	 * {@code createdAt}, so there is no window that means "won between these dates" — a deal
	 * opened in August and won in September is a September win and an August creation, and GHL
	 * offers no filter on {@code lastStatusChangeAt}. The only honest shape is to ask for the
	 * <em>wins</em> over a created-window wide enough to contain the sales cycle and bucket them
	 * here by the status-change timestamp. {@code status=won} is what keeps that read small:
	 * six months of wins is a fraction of six months of leads.
	 *
	 * @param status one of GHL's {@code open}, {@code won}, {@code lost}, {@code abandoned}, or
	 *               null for every status. Not validated here — the caller passes a constant, and
	 *               GHL answers 422 on anything else, which {@link GhlHttp} maps to a 502 naming
	 *               the refusal rather than to a silently unfiltered read.
	 */
	public List<Opportunity> opportunitiesIn(String pipelineId, LocalDate from, LocalDate to, String status) {
		List<Opportunity> all = new ArrayList<>();
		Long startAfter = null;
		String startAfterId = null;

		for (int page = 0; page < MAX_PAGES; page++) {
			Long cursor = startAfter;
			String cursorId = startAfterId;
			SearchResponse response = http.get(SearchResponse.class, (uri) -> {
				// **Three conventions on one endpoint, all verified against the live API.**
				// `location_id`/`pipeline_id` are snake_case (camelCase -> 422 "property
				// locationId should not exist"), while `date`/`endDate` and the cursor params are
				// camelCase (snake_case -> 422 "property start_date should not exist"). GHL is
				// simply inconsistent here; none of this is a typo to align.
				uri.path("/opportunities/search")
						.queryParam("location_id", http.locationId())
						.queryParam("pipeline_id", pipelineId)
						.queryParam("limit", PAGE_SIZE)
						// **`date`/`endDate` filter on the opportunity's `createdAt`** — confirmed
						// by narrowing to one month and getting back only rows created in it. So
						// the funnel becomes "opportunities *created* in this window, grouped by
						// the stage they are in now", which is the question a marketer is asking.
						// GHL wants mm-dd-yyyy; anything else is silently unfiltered, not refused.
						// Null dates omit the window entirely — `allIn` reads a whole pipeline, and
						// a mirror that filtered on createdAt would drop the oldest open deals.
						.queryParamIfPresent("date", java.util.Optional.ofNullable(from).map(GHL_DATE::format))
						.queryParamIfPresent("endDate", java.util.Optional.ofNullable(to).map(GHL_DATE::format));
				if (status != null) {
					uri.queryParam("status", status);
				}
				if (cursor != null && cursorId != null) {
					uri.queryParam("startAfter", cursor).queryParam("startAfterId", cursorId);
				}
				return uri.build();
			});

			List<Opportunity> found = Optional.ofNullable(response.opportunities()).orElse(List.of());
			all.addAll(found);

			// A short page is the last page. GHL returns exactly `limit` rows whether or not more
			// exist, so a final full page still costs one more request. `meta.total` is deliberately
			// NOT used to stop the loop: a loop bound that is a few rows short silently drops the
			// tail of the page it was reading, and GHL's total moves whenever somebody drags a card.
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

	private static String squashed(String name) {
		return name == null ? "" : name.strip().replaceAll("\\s+", " ");
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
		 * <p>{@code total} — the count of everything the search matched — is bound by nothing here
		 * any more. {@code countIn} read it to count a funnel stage in one request and went with
		 * the funnel screens on 2026-09-16.
		 */
		record Meta(Long startAfter, String startAfterId) {
		}
	}
}
