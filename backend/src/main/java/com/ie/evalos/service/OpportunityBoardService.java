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
	public record Board(List<BoardColumn> columns, int totalDeals, BigDecimal totalValue,
			Instant readAt, boolean stale) {
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

	OpportunityBoardService(OpportunityMirrorService deals, PipelineMirrorService mirroredPipelines,
			TeamMemberRepository teamMembers,
			@Value("${evalos.ghl.board-cache-ttl}") Duration ttl,
			@Value("${evalos.ghl.sales-brand:}") String salesBrandId) {
		this.deals = deals;
		this.mirroredPipelines = mirroredPipelines;
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
		return draw(mine, deals.onPipelines(mine));
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
		return caller.ghlPipelineIds();
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
	 * pages, the upgrade is an off-thread refill (MarketingPipelineService implemented one until the
	 * funnel screens were removed on 2026-09-16; `git show` it rather than redesigning it) —
	 * not a bigger cache, and not a delta sync before there is a complaint to justify it.
	 * </pre>
	 */
	private void refillIfStale(String pipelineId) {
		// The GHL read still happens outside any transaction, and the write is still a separate
		// transactional call — holding a pooled connection open across a network round trip is how
		// one slow upstream becomes an exhausted pool. What changed at Unit 44d is that the write
		// is an UPSERT into `opportunity` rather than a delete-all-then-insert-all into a cache.
		deals.refreshIfStale(pipelineId, ttl);
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
		// empty pipeline has no row to carry a timestamp, and reporting "now" for it would tell a
		// reader the board is live when nothing has been read.
		Instant lastSynced = deals.lastSynced(pipelineIds);
		Instant readAt = lastSynced == null ? Instant.now() : lastSynced;

		return new Board(columns, rows.size(), sum(rows), readAt,
				Duration.between(readAt, Instant.now()).compareTo(ttl) >= 0);
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
