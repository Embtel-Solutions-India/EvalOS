package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.PipelineStage;
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
	public record BoardColumn(String stageId, String stageName, int position, boolean won, List<Deal> deals,
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
	/**
	 * <p>{@code source} and {@code service} were added 2026-09-23 for the card, and both come off the
	 * row itself: the deal's own {@code source}, else its {@code opportunity.lead_source} field;
	 * its {@code opportunity.service_requested} field, else the portal request it was opened
	 * from. Null means neither says — the card shows a placeholder rather than a guess.
	 */
	public record Deal(String opportunityId, String name, String contactId, String status,
			BigDecimal amount, java.time.Instant updatedAt, String source, String service,
			/** The mirrored name of the pipeline the deal is on — whose desk it is, on the GM's all-sales view. */
			String pipelineName) {
	}

	/**
	 * The GM's (and the Admin's) first drop-down option and default (2026-10-29): every SALES pipeline at once, the
	 * same union {@link #forCaller} draws. Not a GHL id — GHL's are 20-character ids, so this cannot collide.
	 */
	public static final String ALL_SALES = "all-sales";

	/** GHL's field keys for the two custom fields the card reads. Keys, not ids — see NewDealForm. */
	static final String SERVICE_FIELD = "opportunity.service_requested";
	static final String LEAD_SOURCE_FIELD = "opportunity.lead_source";

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
			Instant lastSyncedAt, boolean stale, boolean syncConfigured,
			/** The pipelines the caller may switch between; empty on the union board. */
			List<PipelineOption> pipelines,
			/** The one pipeline drawn, or null on the union board. */
			String pipelineId) {

		public Board(List<BoardColumn> columns, int totalDeals, BigDecimal totalValue, Instant lastSyncedAt, boolean stale,
				boolean syncConfigured) {
			this(columns, totalDeals, totalValue, lastSyncedAt, stale, syncConfigured, List.of(), null);
		}

		Board choosing(List<PipelineOption> options, String chosen) {
			return new Board(columns, totalDeals, totalValue, lastSyncedAt, stale, syncConfigured, options, chosen);
		}
	}

	/** A pipeline the board can switch to: GHL's id and its mirrored name. */
	public record PipelineOption(String id, String name) {
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
	private final SellingBrand sellingBrand;

	/** Where the two card fields are resolved from — one read each per board, never per card. */
	private final com.ie.evalos.repository.GhlCustomFieldRepository customFields;

	/** The configured MANUAL_SYNC_FLOOR_SECONDS; an Administrator's saved value wins (see {@link #manualSyncFloor}). */
	private final int manualSyncFloorSeconds;

	/** Absent only in hand-built tests, which keep the value they were given. */
	private com.ie.evalos.config.AppSettings settings;

	OpportunityBoardService(OpportunityMirrorService deals, PipelineMirrorService mirroredPipelines,
			com.ie.evalos.repository.TeamMemberPipelineRepository assignments,
			@Value("${evalos.ghl.board-stale-after}") Duration staleAfter,
			SellingBrand sellingBrand,
			com.ie.evalos.repository.GhlCustomFieldRepository customFields,
			@Value("${evalos.ghl.manual-sync-floor-seconds}") int manualSyncFloorSeconds) {
		this.deals = deals;
		this.mirroredPipelines = mirroredPipelines;
		this.assignments = assignments;
		this.customFields = customFields;
		this.staleAfter = staleAfter;
		this.sellingBrand = sellingBrand;
		this.manualSyncFloorSeconds = manualSyncFloorSeconds;
	}

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	void useSettings(com.ie.evalos.config.AppSettings settings) {
		this.settings = settings;
	}

	/**
	 * How recently a pipeline may already have been read for a Refresh to skip it.
	 *
	 * <p>A floor rather than an unconditional read: this is a button, and buttons get clicked twice. GHL's
	 * budget is per location and shared with every desk, so a double-click must cost one read. The
	 * Administrator's value from Settings when saved (D83), else {@code evalos.ghl.manual-sync-floor-seconds}.
	 */
	private Duration manualSyncFloor() {
		return Duration.ofSeconds(com.ie.evalos.config.AppSettings.intOr(settings,
				com.ie.evalos.config.Setting.MANUAL_SYNC_FLOOR_SECONDS, manualSyncFloorSeconds));
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
			return new Board(List.of(), 0, BigDecimal.ZERO, null, true, sellingBrand.id() != null);
		}

		// Only the GM's board folds same-named stages together: a salesperson drags cards, and a drop onto a
		// column that stands for several pipelines' stages would have no single stage to move the deal to.
		return draw(mine, deals.onPipelines(mine), caller.role().hasGmView());
	}

	/**
	 * One pipeline of the caller's, for the board page's tabs (a desk) or drop-down (the GM).
	 *
	 * <p><strong>The parameter narrows; it never widens.</strong> It must be one of
	 * {@link #choicesFor}, or this is a 403: a desk's own pipelines, or for the GM and the Admin every
	 * live mirrored pipeline. None asked for: the GM's Master Pipeline, a desk's first.
	 */
	public Board forPipeline(String requested) {
		return drawn(choose(TenantContext.current(), requested));
	}

	/** The Refresh button on one pipeline: syncs only that one, checked exactly as a read is, then draws it. */
	public Board syncNow(String requested) {
		TenantContext caller = TenantContext.current();
		// A desk's choices need no mirror (they come from the token), so a pipeline that is not theirs is refused
		// here, before the structure read below spends any of the location's GHL budget.
		if (!caller.role().hasGmView()) {
			choose(caller, requested);
		}
		// An empty mirror has no pipeline to offer, so read the structure first.
		syncStructureIfEmpty();
		Choice choice = choose(caller, requested);
		if (ALL_SALES.equals(choice.id())) {
			salesPipelines().forEach((id) -> deals.refreshIfStale(id, manualSyncFloor()));
		}
		else if (choice.id() != null) {
			deals.refreshIfStale(choice.id(), manualSyncFloor());
		}
		return drawn(choice);
	}

	private Board drawn(Choice choice) {
		if (choice.id() == null) {
			return new Board(List.of(), 0, BigDecimal.ZERO, null, true, sellingBrand.id() != null);
		}
		if (ALL_SALES.equals(choice.id())) {
			List<String> sales = salesPipelines();
			if (sales.isEmpty()) {
				return new Board(List.of(), 0, BigDecimal.ZERO, null, true, sellingBrand.id() != null)
						.choosing(choice.options(), ALL_SALES);
			}
			// Folded like the union read: a GM moves no card, so a column may stand for several pipelines' stages.
			return draw(sales, deals.onPipelines(sales), true).choosing(choice.options(), ALL_SALES);
		}
		List<String> one = List.of(choice.id());
		return draw(one, deals.onPipelines(one), false).choosing(choice.options(), choice.id());
	}

	private record Choice(List<PipelineOption> options, String id) {
	}

	/** The pipeline to draw: the one asked for if the caller holds it (else 403), or their default. */
	private Choice choose(TenantContext caller, String requested) {
		List<com.ie.evalos.domain.Pipeline> live = livePipelines();
		List<PipelineOption> options = choicesFor(caller, live);
		if (requested == null || requested.isBlank()) {
			return new Choice(options, defaultOf(options, live, caller));
		}
		if (options.stream().noneMatch((option) -> option.id().equals(requested))) {
			throw new ForbiddenException("That pipeline is not one of yours");
		}
		return new Choice(options, requested);
	}

	/** The caller's pipelines in GHL's order; one the mirror has not seen keeps its id as its name. */
	private List<PipelineOption> choicesFor(TenantContext caller, List<com.ie.evalos.domain.Pipeline> live) {
		if (caller.role().hasGmView()) {
			List<PipelineOption> all = new ArrayList<>();
			all.add(new PipelineOption(ALL_SALES, "All sales pipelines"));
			live.stream().map((p) -> new PipelineOption(p.getGhlId(), p.getName())).forEach(all::add);
			return all;
		}
		List<String> mine = pipelinesFor(caller);
		List<PipelineOption> options = new ArrayList<>(live.stream()
				.filter((p) -> mine.contains(p.getGhlId()))
				.map((p) -> new PipelineOption(p.getGhlId(), p.getName()))
				.toList());
		mine.stream().filter((id) -> options.stream().noneMatch((o) -> o.id().equals(id)))
				.forEach((id) -> options.add(new PipelineOption(id, id)));
		return options;
	}

	private List<com.ie.evalos.domain.Pipeline> livePipelines() {
		return mirroredPipelines.all().stream().filter(com.ie.evalos.domain.Pipeline::isLive).toList();
	}

	/**
	 * A desk starts on its first pipeline; the GM on {@link #ALL_SALES} (edited 2026-10-29 — it was the live
	 * pipeline tagged {@code MASTER}, which stays one pick away in the drop-down).
	 */
	private static String defaultOf(List<PipelineOption> options, List<com.ie.evalos.domain.Pipeline> live,
			TenantContext caller) {
		if (options.isEmpty()) {
			return null;
		}
		return caller.role().hasGmView() ? ALL_SALES : options.get(0).id();
	}

	/**
	 * Pipelines first, but only when the mirror knows of none.
	 *
	 * <p>Refresh used to refresh only opportunities — so in the one state a reader would actually press it
	 * in, an empty mirror with nothing on the board, it could do nothing at all. Gating on "the mirror knows of
	 * no live pipeline" keeps that case and drops the paged structure read every other press would spend on a
	 * location whose pipelines change a few times a year (the hourly PIPELINE_MIRROR sweep keeps it current).
	 */
	private void syncStructureIfEmpty() {
		if (mirroredPipelines.all().stream().noneMatch(com.ie.evalos.domain.Pipeline::isLive)) {
			mirroredPipelines.sync();
		}
	}

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
	 * {@code TeamMemberRepository.findPipelinesOfActiveMembers(...)}, and that was wrong twice
	 * over:
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
	 *
	 * <p><strong>This class no longer holds a {@code TeamMemberRepository} at all</strong>
	 * (2026-09-22). It kept one for five days after the logic left, and
	 * {@code OpportunityBoardServiceTest} guarded the fix with
	 * {@code verify(teamMembers, never())} — a behavioural assertion over a collaborator that was
	 * still injected. Not having the collaborator is the same guarantee made structurally: the
	 * roster cannot be consulted by a class that cannot reach it, and no test has to remember to
	 * check.
	 */
	private List<String> pipelinesFor(TenantContext caller) {
		if (caller.role().hasGmView()) {
			return salesPipelines();
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
	 * The GM's (and the Admin's read-only) board: the SALES pipelines only (D19e, amended 2026-10-08).
	 * Marketing funnels, Case Delivery, hiring and the Master Pipeline have their own screens. Still gated on
	 * the mirror, so a pipeline GHL stopped returning is not offered.
	 */
	private List<String> salesPipelines() {
		if (sellingBrand.id() == null) {
			return List.of();
		}
		java.util.Set<String> sales = new java.util.HashSet<>(assignments.salesGhlIds(sellingBrand.id()));
		return livePipelines().stream()
				.map(com.ie.evalos.domain.Pipeline::getGhlId)
				.filter(sales::contains)
				.toList();
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
	private Board draw(List<String> pipelineIds, List<Opportunity> rows, boolean foldSameNames) {
		List<PipelineStage> live = mirroredPipelines.all().stream()
				.filter((pipeline) -> pipelineIds.contains(pipeline.getGhlId()))
				.flatMap((pipeline) -> mirroredPipelines.stagesOf(pipeline.getId()).stream())
				.filter(PipelineStage::isLive)
				.sorted(Comparator.comparingInt(PipelineStage::getPosition))
				.toList();
		// stage id -> the column it is drawn in. Identity unless folding, where stages sharing a name
		// (case-insensitive) draw as one column, in the position of the first, so no name appears twice.
		Map<String, String> columnOf = foldedColumns(live, foldSameNames);
		Map<String, MirroredStage> stages = new LinkedHashMap<>();
		live.forEach((stage) -> stages.putIfAbsent(columnOf.get(stage.getGhlId()),
				new MirroredStage(stage.getName(), stage.getPosition(), stage.isWon())));

		Map<String, List<Opportunity>> byStage = new LinkedHashMap<>();
		stages.keySet().forEach((stageId) -> byStage.put(stageId, new ArrayList<>()));
		rows.forEach((row) -> byStage
				.computeIfAbsent(columnOf.getOrDefault(row.getGhlStageId(), row.getGhlStageId()),
						(key) -> new ArrayList<>())
				.add(row));

		// The card's service and source, resolved once for the whole board. Custom field values are
		// keyed by GHL field *id*, so the two keys are turned into ids first. The read is
		// brand-scoped to the selling brand, the only one a board draws.
		Map<String, String> fieldIds = new java.util.HashMap<>();
		if (sellingBrand.id() != null && !rows.isEmpty()) {
			customFields.findByBrandIdAndModelOrderByNameAsc(sellingBrand.id(),
					ReferenceMirrorService.OPPORTUNITY_MODEL)
					.forEach((field) -> {
						if (field.getFieldKey() != null) fieldIds.put(field.getFieldKey(), field.getGhlId());
					});
		}
		String serviceField = fieldIds.get(SERVICE_FIELD);
		String leadSourceField = fieldIds.get(LEAD_SOURCE_FIELD);
		Map<java.util.UUID, String> pipelineNames = new java.util.HashMap<>();
		mirroredPipelines.all().forEach((p) -> pipelineNames.put(p.getId(), p.getName()));

		List<BoardColumn> columns = byStage.entrySet().stream()
				.map((entry) -> {
					MirroredStage stage = stages.get(entry.getKey());
					List<Deal> deals = entry.getValue().stream()
							.map((row) -> new Deal(row.getGhlId(), row.getName(),
									row.getGhlContactId(), row.getStatus(), row.getAmount(),
									row.getGhlUpdatedAt(),
									firstOf(row.getSource(), row.getCustomFields().get(leadSourceField)),
									row.getCustomFields().get(serviceField),
									pipelineNames.get(row.getPipelineId())))
							.toList();
					return new BoardColumn(entry.getKey(),
							// A stage GHL no longer lists still holds cards until the next
							// refill. Showing the id beats dropping the column and the deals in
							// it: a card that vanishes is a card somebody goes looking for.
							stage == null ? entry.getKey() : stage.name(),
							stage == null ? Integer.MAX_VALUE : stage.position(),
							// pipeline_stage.won (V94), the same flag SalesDeskService wins a deal by.
							stage != null && stage.won(),
							// Open deals only, like the board total: a column's value is what is still in play.
							deals, sum(entry.getValue().stream().filter(OpportunityBoardService::isOpen).toList()));
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
		// **The totals are the OPEN deals** — every screen labels them "open" (the board header, the GM's
		// "Open deals", the desk tiles), and GHL's pipeline view counts open work. They used to be every
		// live row, so a pipeline with 85 open, 15 won and 7 lost read "107 open" and its value included
		// closed money. The cards themselves still show every status, as before.
		List<Opportunity> open = rows.stream().filter(OpportunityBoardService::isOpen).toList();
		return new Board(columns, open.size(), sum(open), lastSynced,
				lastSynced == null
						|| Duration.between(lastSynced, Instant.now()).compareTo(staleAfter) >= 0,
				sellingBrand.id() != null);
	}

	/** Maps every stage id to the id of the column that draws it; see {@link #draw}. Static so a test can pin it. */
	static Map<String, String> foldedColumns(List<PipelineStage> inPositionOrder, boolean fold) {
		Map<String, String> columnOf = new java.util.HashMap<>();
		Map<String, String> firstByName = new java.util.HashMap<>();
		for (PipelineStage stage : inPositionOrder) {
			String column = fold
					? firstByName.computeIfAbsent(stage.getName().trim().toLowerCase(java.util.Locale.ROOT),
							(name) -> stage.getGhlId())
					: stage.getGhlId();
			columnOf.putIfAbsent(stage.getGhlId(), column);
		}
		return columnOf;
	}

	/** Just the two fields a column header needs, so the board does not carry a whole entity. */
	private record MirroredStage(String name, int position, boolean won) {
	}

	/** The first value that says something; a blank one is treated as absent. */
	private static String firstOf(String preferred, String fallback) {
		return preferred != null && !preferred.isBlank() ? preferred
				: fallback != null && !fallback.isBlank() ? fallback : null;
	}

	/** A null status is a deal EvalOS created and GHL has not answered for yet: still open. */
	static boolean isOpen(Opportunity row) {
		return row.getStatus() == null || row.getStatus().isBlank() || "open".equalsIgnoreCase(row.getStatus());
	}

	private static BigDecimal sum(List<Opportunity> rows) {
		return rows.stream()
				.map(Opportunity::getAmount)
				.filter((amount) -> amount != null)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}
}
