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
import java.util.stream.Stream;

import com.ie.evalos.domain.CachedOpportunity;
import com.ie.evalos.domain.Role;
import com.ie.evalos.integration.GhlOpportunityClient;
import com.ie.evalos.integration.GhlPipelineClient;
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
	public record Deal(String opportunityId, String name, String contactId, String status,
			BigDecimal amount) {
	}

	/**
	 * The whole board.
	 *
	 * <p>{@code readAt} is on the payload because a cache the reader cannot date is a cache the
	 * reader has to trust blindly — the same reasoning the funnel screens' own age stamp carries.
	 */
	public record Board(List<BoardColumn> columns, int totalDeals, BigDecimal totalValue,
			Instant readAt, boolean stale) {
	}

	private final GhlOpportunityClient opportunities;
	private final GhlPipelineClient pipelines;
	private final OpportunityCache cache;
	private final TeamMemberRepository teamMembers;
	private final Duration ttl;

	/**
	 * The brand that owns the configured GHL location, or null when none is configured.
	 *
	 * <p><strong>Parsed at construction, so a typo fails the boot rather than the first board
	 * load.</strong> Blank and malformed are different faults and deserve different treatment:
	 * blank means "no brand sells yet", which is a legitimate state that yields an empty union;
	 * a malformed UUID is a deployment mistake, and discovering it as a 500 the first time a GM
	 * opens a board is discovering it in the worst place. {@code GhlHttp} answers 502 rather than
	 * failing to boot for a *missing* token, which is the same distinction from the other side.
	 */
	private final UUID sellingBrandId;

	OpportunityBoardService(GhlOpportunityClient opportunities, GhlPipelineClient pipelines,
			OpportunityCache cache, TeamMemberRepository teamMembers,
			@Value("${evalos.ghl.board-cache-ttl}") Duration ttl,
			@Value("${evalos.ghl.sales-brand:}") String salesBrandId) {
		this.opportunities = opportunities;
		this.pipelines = pipelines;
		this.cache = cache;
		this.teamMembers = teamMembers;
		this.ttl = ttl;
		try {
			this.sellingBrandId = salesBrandId == null || salesBrandId.isBlank() ? null
					: UUID.fromString(salesBrandId);
		}
		catch (IllegalArgumentException malformed) {
			throw new IllegalStateException(
					"evalos.ghl.sales-brand is not a UUID: \"" + salesBrandId + "\". It names the brand "
							+ "that owns evalos.ghl.location-id; leave it blank if no brand sells yet.",
					malformed);
		}
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
			return new Board(List.of(), 0, BigDecimal.ZERO, Instant.now(), false);
		}

		mine.forEach(this::refillIfStale);
		return draw(mine, cache.forPipelines(mine));
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
	 */
	private List<String> pipelinesFor(TenantContext caller) {
		if (caller.role() == Role.GM) {
			return sellingBrandId == null ? List.of()
					: teamMembers.findPipelinesOfActiveMembers(sellingBrandId);
		}
		return caller.ghlPipelineId() == null ? List.of() : List.of(caller.ghlPipelineId());
	}

	/**
	 * Refetches a pipeline when its copy has aged past the TTL.
	 *
	 * <p><strong>Inline, not on a background thread, and that departs from the spec.</strong>
	 * §4 justifies the cache with the funnel's arithmetic — ~11.4k opportunities over ~115
	 * un-parallelisable pages, a ~13s floor. That figure is a <em>year of one marketing
	 * funnel</em>. One person's live pipeline is one or two pages, so an inline refill costs a
	 * few hundred milliseconds and the background machinery would be complexity bought for a
	 * problem this screen does not have.
	 *
	 * <p>What the cache is still earning is the shared pacer: GHL's 100-per-10-seconds is per
	 * location, so several people loading boards at once serialise behind one limiter. Absorbing
	 * repeat loads is the point, not absorbing a single slow one.
	 *
	 * <pre>
	 * ponytail: inline refill, whole-pipeline replace. If one pipeline ever grows past a few
	 * pages, the upgrade is the off-thread refill MarketingPipelineService already implements —
	 * not a bigger cache, and not a delta sync before there is a complaint to justify it.
	 * </pre>
	 */
	private void refillIfStale(String pipelineId) {
		if (!cache.isStale(pipelineId, ttl)) {
			return;
		}
		// The GHL read happens HERE, outside any transaction, and the write is a separate
		// transactional call on OpportunityCache. Doing both inside one @Transactional method
		// would hold a pooled connection open across a network round trip — see that class.
		cache.replace(pipelineId, opportunities.inPipeline(pipelineId));
	}

	/** Groups the rows into GHL's own stage order, so the board reads like the pipeline does. */
	private Board draw(List<String> pipelineIds, List<CachedOpportunity> rows) {
		Map<String, GhlPipelineClient.Pipeline.Stage> stages = new LinkedHashMap<>();
		pipelines.pipelines().stream()
				.filter((pipeline) -> pipelineIds.contains(pipeline.id()))
				.flatMap((pipeline) -> pipeline.stages() == null
						? Stream.<GhlPipelineClient.Pipeline.Stage>empty()
						: pipeline.stages().stream())
				.sorted(Comparator.comparingInt(GhlPipelineClient.Pipeline.Stage::position))
				.forEach((stage) -> stages.putIfAbsent(stage.id(), stage));

		Map<String, List<CachedOpportunity>> byStage = new LinkedHashMap<>();
		stages.keySet().forEach((stageId) -> byStage.put(stageId, new ArrayList<>()));
		rows.forEach((row) -> byStage.computeIfAbsent(row.getStageId(), (key) -> new ArrayList<>())
				.add(row));

		List<BoardColumn> columns = byStage.entrySet().stream()
				.map((entry) -> {
					GhlPipelineClient.Pipeline.Stage stage = stages.get(entry.getKey());
					List<Deal> deals = entry.getValue().stream()
							.map((row) -> new Deal(row.getGhlOpportunityId(), row.getName(),
									row.getGhlContactId(), row.getStatus(), row.getAmount()))
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

		Instant readAt = rows.stream().map(CachedOpportunity::getFetchedAt)
				.max(Comparator.naturalOrder()).orElse(Instant.now());

		return new Board(columns, rows.size(), sum(rows), readAt,
				Duration.between(readAt, Instant.now()).compareTo(ttl) >= 0);
	}

	private static BigDecimal sum(List<CachedOpportunity> rows) {
		return rows.stream()
				.map(CachedOpportunity::getAmount)
				.filter((amount) -> amount != null)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}
}
