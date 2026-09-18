package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.PipelineStage;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * One board, for whoever is asking.
 *
 * <p><strong>There is one board and not two, which departs from the spec's wording.</strong>
 * `38-opportunity-reads-and-boards.md` §5 lists a Marketing board and a Sales board; they have
 * identical shape and identical semantics — "the pipeline I own, grouped by stage" — and differ
 * only in which role reaches them. Two services, two controllers and two payloads for one
 * question would be the same code twice, and the second copy is where they drift. The role gate
 * is the difference, and a gate is a line, not a class.
 *
 * <p><strong>It reads EvalOS rows and makes no GHL request at all</strong> — Unit 46, and the
 * headline `00c` §3 gives that unit. This method used to call {@code refreshIfStale} per pipeline
 * on every load, which was right while nothing else kept the mirror current. <strong>45d changed
 * the premise</strong>: a webhook absorbs a change within seconds, and {@code MIRROR_DELTA} is the
 * floor under it for pipelines nobody is looking at. So the refill went, and
 * {@code evalos.ghl.board-cache-ttl} went with it.
 *
 * <p><strong>The board got faster rather than staler.</strong> A desk edit writes the mirror row
 * first (Unit 46 §2) and queues the push, so a salesperson dragging a card sees it move without
 * waiting for a GHL round trip. {@code lastSynced} still travels on every board: it is now the only
 * staleness signal a reader has, which makes it load-bearing rather than decorative.
 *
 * <p><strong>Scoping is structural, not a predicate, and that is also a departure.</strong> The
 * cache carries no {@code brand_id} (see {@code V40}), so {@code ScopePredicate} cannot build its
 * usual brand-plus-axis clause over it. Rather than add a column that would hold one value and
 * only look like a scope, the pipeline ids come from the caller's own principal and every
 * repository finder requires them. A predicate is something a query can forget; a required
 * parameter is not.
 */
@Service
public class OpportunityBoardService {

	/**
	 * One stage of the board, with the deals standing in it.
	 *
	 * <p>{@code stageName} is resolved from GHL per request and never stored: a stage renamed in
	 * GHL shows renamed, and renaming changes nobody's access, because access is keyed on the
	 * pipeline id (Unit 36 §4).
	 */
	public record BoardColumn(String stageId, String stageName, int position, List<Deal> deals,
			BigDecimal total) {
	}

	/** One card. Deliberately not the cache row: no {@code fetchedAt}, no internal bookkeeping. */
	/**
	 * One card.
	 *
	 * <p>{@code updatedAt} is <strong>GHL's</strong> last-modified stamp, not ours: the cache also
	 * holds {@code fetched_at}, which is when EvalOS last read the row and would make every deal
	 * look touched on every refill. It is nullable because GHL does not always send it — a card
	 * with no stamp is "age unknown", which the reader must be shown rather than have guessed as
	 * "fresh".
	 *
	 * <p>Added 2026-09-14 for the Sales and Marketing desks: without it a board can draw deals but
	 * cannot answer "which of these has nobody touched", which is the question a pipeline screen
	 * exists to answer. The column was already in the cache and simply was not on the payload.
	 */
	public record Deal(String opportunityId, String name, String contactId, String status,
			BigDecimal amount, java.time.Instant updatedAt) {
	}

	/**
	 * The whole board.
	 *
	 * <p>{@code readAt} is on the payload because a cache the reader cannot date is a cache the
	 * reader has to trust blindly — the same reasoning the funnel screens' own age stamp carries.
	 */
	/**
	 * @param lastSyncedAt when the sync last confirmed these pipelines against GHL, or <strong>null
	 *                     when it never has</strong>. Never substituted with "now" — see {@code draw}
	 * @param stale          whether to tell the reader the sync is behind. True when
	 *                       {@code lastSyncedAt} is null, because never-synced is not fresh
	 * @param syncConfigured false when {@code evalos.ghl.sales-brand} is blank. <strong>A blank one
	 *                       makes every mirror a no-op</strong> — the pipeline sweep, the delta
	 *                       sweep and the reference sweep each log a warning and return zero — so
	 *                       the board is empty and unsynced for a reason that has nothing to do with
	 *                       the sync being behind. It travels so the screen can say which it is
	 *                       instead of blaming the sweep
	 */
	public record Board(List<BoardColumn> columns, int totalDeals, BigDecimal totalValue,
			Instant lastSyncedAt, boolean stale, boolean syncConfigured) {
	}

	/**
	 * The mirrored opportunities (Unit 44d).
	 *
	 * <p>This was a {@code GhlOpportunityClient} plus an {@code OpportunityCache} whose only write
	 * path was delete-all-then-insert-all per pipeline. Both are gone: the board reads EvalOS rows
	 * that are upserted in place, so a row keeps its identity across a refresh and a portal-born
	 * deal with no {@code ghl_id} yet survives one.
	 */
	private final OpportunityMirrorService deals;

	/**
	 * The mirrored pipelines, which is where stage names come from as of Unit 44a.
	 *
	 * <p>This was {@code GhlPipelineClient} — a live GHL read on every board render, to turn an
	 * opaque stage id into a word. The mirror holds GHL's id verbatim, so the same lookup is now
	 * local and the busiest screen in the app stops spending the location's request budget on
	 * structure that changes a few times a year.
	 */
	private final PipelineMirrorService mirroredPipelines;

	private final TeamMemberRepository teamMembers;
	/** Read only when the caller's token carries no pipelines — see {@code pipelinesFor}. */
	private final com.ie.evalos.repository.TeamMemberPipelineRepository assignments;
	private final Duration staleAfter;

	/**
	 * The brand that owns the configured GHL location, or null when none is configured.
	 *
	 * <p><strong>Resolved by {@link SellingBrand}, not parsed here.</strong> This class used to do
	 * it itself, with the only bespoke error handling among nine copies — which meant eight other
	 * services failed differently for the same typo. The resolver keeps the ruling that made that
	 * handling worth having (a bad value fails the boot, not the first board load) and adds what it
	 * could not: a brand <em>slug</em> resolves too, so a shared config file is not right in one
	 * database and silently wrong in the next.
	 */
	private final UUID sellingBrandId;

	OpportunityBoardService(OpportunityMirrorService deals, PipelineMirrorService mirroredPipelines,
			TeamMemberRepository teamMembers,
			com.ie.evalos.repository.TeamMemberPipelineRepository assignments,
			@Value("${evalos.ghl.board-stale-after}") Duration staleAfter,
			SellingBrand sellingBrand) {
		this.deals = deals;
		this.mirroredPipelines = mirroredPipelines;
		this.teamMembers = teamMembers;
		this.assignments = assignments;
		this.staleAfter = staleAfter;
		this.sellingBrandId = sellingBrand.id();
	}

	/**
	 * The caller's board.
	 *
	 * <p>A {@code SALES} or {@code MARKETING} caller sees the one pipeline their row names. The GM
	 * sees the union — see {@link #pipelinesFor}.
	 */
	public Board forCaller() {
		TenantContext caller = TenantContext.current();
		List<String> mine = pipelinesFor(caller);
		if (mine.isEmpty()) {
			// Fail closed, exactly as ScopePredicate's PIPELINE arm does: a principal with no
			// pipeline sees an empty board, never somebody else's. One re-login fixes a token
			// minted before V39, and that is the safe direction to be wrong in.
			return new Board(List.of(), 0, BigDecimal.ZERO, null, true, sellingBrandId != null);
		}

		return draw(mine, deals.onPipelines(mine));
	}

	/**
	 * Sync this caller's own pipelines now, then draw — <strong>the Refresh button</strong>.
	 *
	 * <p><strong>It reconciles the mirror; it is not a live board read.</strong> The distinction is
	 * the whole of Unit 46: the board is drawn from EvalOS rows either way, and what this does is
	 * bring those rows forward rather than bypass them. Nothing here reaches GHL on the reader's
	 * behalf — {@code refreshIfStale} writes the mirror, and the draw below reads it, exactly as an
	 * ordinary load does.
	 *
	 * <p><strong>A 30-second floor rather than an unconditional read.</strong> This is a button, and
	 * buttons get clicked twice; GHL's budget is per location and shared with every other desk, so
	 * a room of people refreshing must not become a room of paged list reads. Thirty seconds is
	 * short enough that a human pressing it after waiting still gets a real sync, and long enough
	 * that a double-click costs one.
	 *
	 * <p>Only the caller's own pipelines, so a salesperson's refresh cannot spend the budget on
	 * pipelines they cannot see. The background sweep is what covers the rest.
	 */
	public Board syncNow() {
		// **Pipelines first, and this is the correction that makes the button worth having.** It
		// used to refresh only opportunities — so in the one state a reader would actually press it
		// in, an empty mirror with nothing on the board, it could do nothing at all: there were no
		// mirrored pipelines to refresh deals *for*, and the only fix was a GM running a job by
		// hand. A button that cannot fix the problem it is offered for is worse than no button.
		//
		// **But only in that state, which the floor below could not express.** MANUAL_SYNC_FLOOR
		// guards the deal reads and not this one, so every press spent a paged GHL structure read
		// as well — on a location whose pipelines and stages change a few times a year and which
		// the hourly PIPELINE_MIRROR sweep already keeps current. Gating on "the mirror knows of no
		// live pipeline" keeps exactly the case the button was added for and drops the rest.
		if (mirroredPipelines.all().stream().noneMatch(com.ie.evalos.domain.Pipeline::isLive)) {
			mirroredPipelines.sync();
		}

		TenantContext caller = TenantContext.current();
		List<String> mine = pipelinesFor(caller);
		// ponytail: a GM's board is every live pipeline, so their press fans a live read over all of
		// them in the request thread, Master Pipeline included. MANUAL_SYNC_FLOOR bounds the repeat
		// cost, not the first one. If that press becomes slow enough to notice, the upgrade is to
		// hand it to the job runner and answer 202 rather than to cap the fan-out, because a
		// Refresh that silently syncs some of the board is worse than one that takes a moment.
		mine.forEach((pipelineId) -> deals.refreshIfStale(pipelineId, MANUAL_SYNC_FLOOR));
		return forCaller();
	}

	/**
	 * How recently a manual sync may already have happened for the button to be a no-op.
	 *
	 * <pre>
	 * ponytail: a constant, not a setting. It exists to absorb a double-click, and nobody is going
	 * to tune it — the number a deployment would actually want to change is the sweep interval.
	 * </pre>
	 */
	private static final Duration MANUAL_SYNC_FLOOR = Duration.ofSeconds(30);

	/**
	 * Which pipelines this caller may read.
	 *
	 * <p><strong>Open question P1 is decided here: the GM's union is the configured selling
	 * brand's, not every brand's.</strong> The reasoning is that every other screen in the app
	 * follows the brand switcher, and a board that silently spanned brands would be the one
	 * exception nobody was told about. In practice it is moot while Unit 36 §4a's single-brand
	 * ceiling holds — but "moot today" is not "undefined", and a screen whose behaviour is
	 * undefined for a state the UI can reach is a bug waiting for the second selling brand.
	 *
	 * <p>The union is a <em>query</em> rather than a predicate, which is why it lives here and
	 * not in {@code ScopePredicate}: {@code Tier.ALL} short-circuits, and "every sales pipeline"
	 * is a fact about the roster, not about the row being read.
	 *
	 * <p><strong>The GM's union is every LIVE MIRRORED pipeline, not the pipelines somebody is
	 * assigned to</strong> (2026-09-17). It was
	 * {@code teamMembers.findPipelinesOfActiveMembers(...)}, and that was wrong twice over:
	 *
	 * <ul>
	 * <li><strong>It hid every pipeline nobody owns.</strong> {@code 00d} §6.7 says outright that
	 * Case Delivery is "a pipeline no single person owns", and the location's Master Pipeline is
	 * another — deriving the GM's view from assignments makes exactly the pipelines that belong to
	 * the business rather than to a person the ones the GM cannot see.</li>
	 * <li><strong>It read a column Unit 44b superseded.</strong> That finder selects
	 * {@code team_member.ghl_pipeline_id}, the single-pipeline column replaced by
	 * {@code team_member_pipeline} at 44b — so once assignment moved to the join table, the query
	 * answered empty and the GM's board went blank with nothing logged.</li>
	 * </ul>
	 *
	 * <p>Asking the mirror instead fixes both by deleting the question: the GM is
	 * {@code Tier.ALL} and the mirror is the list of pipelines that exist.
	 */
	private List<String> pipelinesFor(TenantContext caller) {
		if (caller.role() == Role.GM) {
			return mirroredPipelines.all().stream()
					.filter(com.ie.evalos.domain.Pipeline::isLive)
					.map(com.ie.evalos.domain.Pipeline::getGhlId)
					.toList();
		}
		List<String> fromToken = caller.ghlPipelineIds();
		if (!fromToken.isEmpty()) {
			return fromToken;
		}
		// **Empty means "ask again", not "you have none".**
		//
		// D19b makes the set a claim carried in the token, read at sign-in — a deliberate staleness
		// bound, so a REASSIGNMENT takes effect on next sign-in. That is fine for a change. It is a
		// trap for a FIRST assignment: a desk signs in before the mirror has run, is granted its
		// pipeline minutes later by the backfill, and then sees an empty board for the rest of the
		// session with "ask a GM" as the only advice — which was the state on 2026-09-17.
		//
		// Re-reading only when the claim is empty keeps the bound where it earns its keep and
		// removes the trap. It widens nothing: it is the same member's own row, read by their own
		// id, and a member with no assignment still gets an empty list.
		return assignments.ghlIdsFor(caller.memberId());
	}

	/**
	 * Groups the rows into GHL's own stage order, so the board reads like the pipeline does.
	 *
	 * <p><strong>The stage names come from the mirror now, not from GHL</strong> (Unit 44a). This
	 * method used to call {@code GET /opportunities/pipelines} on <em>every board render</em> — an
	 * unpaginated network round trip, against a 100-per-10-seconds budget shared with every other
	 * desk, to turn an opaque stage id into a word. {@code pipeline_stage} holds GHL's id verbatim
	 * ({@code 00c} §2b), so the lookup is now a local read and the id resolves without a
	 * translation that could itself be wrong.
	 *
	 * <p>A stage the mirror has not seen still draws its column under the raw id, exactly as
	 * before: a card that vanishes is a card somebody goes looking for. The difference is that the
	 * fallback is now reached when the <em>sweep</em> is behind rather than when GHL is slow.
	 */
	private Board draw(List<String> pipelineIds, List<Opportunity> rows) {
		Map<String, MirroredStage> stages = new LinkedHashMap<>();
		mirroredPipelines.all().stream()
				.filter((pipeline) -> pipelineIds.contains(pipeline.getGhlId()))
				.flatMap((pipeline) -> mirroredPipelines.stagesOf(pipeline.getId()).stream())
				.filter(PipelineStage::isLive)
				.sorted(Comparator.comparingInt(PipelineStage::getPosition))
				.forEach((stage) -> stages.putIfAbsent(stage.getGhlId(),
						new MirroredStage(stage.getName(), stage.getPosition())));

		Map<String, List<Opportunity>> byStage = new LinkedHashMap<>();
		stages.keySet().forEach((stageId) -> byStage.put(stageId, new ArrayList<>()));
		rows.forEach((row) -> byStage.computeIfAbsent(row.getGhlStageId(), (key) -> new ArrayList<>())
				.add(row));

		List<BoardColumn> columns = byStage.entrySet().stream()
				.map((entry) -> {
					MirroredStage stage = stages.get(entry.getKey());
					List<Deal> deals = entry.getValue().stream()
							.map((row) -> new Deal(row.getGhlId(), row.getName(),
									row.getGhlContactId(), row.getStatus(), row.getAmount(),
									row.getGhlUpdatedAt()))
							.toList();
					return new BoardColumn(entry.getKey(),
							// A stage GHL no longer lists still holds cards until the next
							// refill. Showing the id beats dropping the column and the deals in
							// it: a card that vanishes is a card somebody goes looking for.
							stage == null ? entry.getKey() : stage.name(),
							stage == null ? Integer.MAX_VALUE : stage.position(),
							deals, sum(entry.getValue()));
				})
				.sorted(Comparator.comparingInt(BoardColumn::position))
				.toList();

		// When the mirror last agreed with GHL, asked of the pipelines rather than of the rows: an
		// empty pipeline has no row to carry a timestamp.
		//
		// **Null travels as null, and that is the correction.** This used to substitute `now()` for
		// "never synced", which told the reader the board was current at the one moment nothing had
		// ever been read — the precise thing a sync indicator exists to prevent. A pipeline that has
		// never been synced is also stale by definition: not knowing is not the same as being fresh.
		Instant lastSynced = deals.lastSynced(pipelineIds);

		// **`stale` changed meaning at Unit 46 and the flag was worth keeping for it.** It used to
		// mean "this render did not refill", a statement about one request. It now means "the sync
		// has not confirmed this mirror lately" — a statement about the sweep — and it is what the
		// board's "sync delayed" banner is drawn from.
		//
		// **The threshold is ONE missed pass, not three.** This comment said three, which is a
		// 15-minute window; `board-stale-after` defaults to 5m in every profile, equal to
		// JOBS_MIRROR_DELTA_INTERVAL, because the business chose the shorter number on 2026-09-17
		// over a proposed 15 — the reasoning, and the blink it accepts at the boundary, are
		// written out in `application.yml` beside the value. A comment claiming the opposite of the
		// configured default is worse than no comment: it is what a reader trusts instead of
		// looking.
		return new Board(columns, rows.size(), sum(rows), lastSynced,
				lastSynced == null
						|| Duration.between(lastSynced, Instant.now()).compareTo(staleAfter) >= 0,
				sellingBrandId != null);
	}

	/** Just the two fields a column header needs, so the board does not carry a whole entity. */
	private record MirroredStage(String name, int position) {
	}

	private static BigDecimal sum(List<Opportunity> rows) {
		return rows.stream()
				.map(Opportunity::getAmount)
				.filter((amount) -> amount != null)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}
}
