package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.CachedOpportunity;
import com.ie.evalos.domain.Role;
import com.ie.evalos.integration.GhlOpportunityClient;
import com.ie.evalos.integration.GhlOpportunityClient.BoardOpportunity;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The board's scoping, its cache behaviour, and open question P1's answer.
 *
 * <p>The assertion that matters most is {@link #anotherPipelineIsNeverRead}: GHL filters by
 * pipeline server-side, which makes it tempting to conclude EvalOS need not. That reasoning holds
 * for the live call and stops holding the moment a row is stored — which is what this unit does.
 */
class OpportunityBoardServiceTest {

	private static final UUID SELLING_BRAND = UUID.randomUUID();
	private static final UUID MEMBER = UUID.randomUUID();
	private static final String MINE = "pipe_mine";
	private static final String THEIRS = "pipe_theirs";
	private static final Duration TTL = Duration.ofMinutes(2);

	private final GhlOpportunityClient opportunities = mock(GhlOpportunityClient.class);
	private final GhlPipelineClient pipelines = mock(GhlPipelineClient.class);
	private final OpportunityCache cache = mock(OpportunityCache.class);
	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);

	private OpportunityBoardService service() {
		return new OpportunityBoardService(opportunities, pipelines, cache, teamMembers, TTL,
				SELLING_BRAND.toString());
	}

	private void authenticate(Role role, String pipelineId) {
		StaffPrincipal principal = new StaffPrincipal(MEMBER, role + "@ie.test", "Desk", role,
				role == Role.GM ? null : SELLING_BRAND, null, pipelineId, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	@BeforeEach
	void stubPipelineStages() {
		when(pipelines.pipelines()).thenReturn(List.of(
				new GhlPipelineClient.Pipeline(MINE, "My pipeline", List.of(
						new GhlPipelineClient.Pipeline.Stage("s1", "New", 0),
						new GhlPipelineClient.Pipeline.Stage("s2", "Warm", 1))),
				new GhlPipelineClient.Pipeline(THEIRS, "Their pipeline", List.of(
						new GhlPipelineClient.Pipeline.Stage("t1", "Theirs", 0)))));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private static CachedOpportunity cached(String id, String pipelineId, String stageId, String amount,
			Instant fetchedAt) {
		return new CachedOpportunity(id, pipelineId, "contact_" + id, stageId, "open", "Deal " + id,
				amount == null ? null : new BigDecimal(amount), null, fetchedAt);
	}

	/** A fresh cache is served without touching GHL. */
	private void givenFreshCache(List<CachedOpportunity> rows, String... pipelineIds) {
		for (String pipelineId : pipelineIds) {
			when(cache.isStale(eq(pipelineId), any())).thenReturn(false);
		}
		when(cache.forPipelines(any())).thenReturn(rows);
	}

	@Test
	void aSalesCallerSeesTheirOwnPipelineGroupedByStage() {
		authenticate(Role.SALES, MINE);
		givenFreshCache(List.of(
				cached("a", MINE, "s1", "100", Instant.now()),
				cached("b", MINE, "s2", "250", Instant.now())), MINE);

		OpportunityBoardService.Board board = service().forCaller();

		assertThat(board.columns()).extracting(OpportunityBoardService.BoardColumn::stageName)
				.containsExactly("New", "Warm");
		assertThat(board.totalDeals()).isEqualTo(2);
		assertThat(board.totalValue()).isEqualByComparingTo("350");
	}

	@Test
	void marketingIsScopedTheSameWay() {
		authenticate(Role.MARKETING, MINE);
		givenFreshCache(List.of(cached("a", MINE, "s1", "10", Instant.now())), MINE);

		assertThat(service().forCaller().totalDeals()).isEqualTo(1);
	}

	/**
	 * <strong>The scope is the query, not a predicate somebody remembers to add.</strong>
	 *
	 * <p>The cache has no {@code brand_id} and so cannot go through {@code ScopePredicate}. What
	 * replaces it is that every finder requires the pipeline ids, and they come from the
	 * principal — so this asserts the value actually handed to the repository, which is the only
	 * place the scope could go wrong.
	 */
	@Test
	void anotherPipelineIsNeverRead() {
		authenticate(Role.SALES, MINE);
		givenFreshCache(List.of(), MINE);

		service().forCaller();

		verify(cache).forPipelines(List.of(MINE));
	}

	/** Fail closed, exactly as {@code ScopePredicate}'s PIPELINE arm does. */
	@Test
	void aCallerWithNoPipelineSeesAnEmptyBoardAndNoGhlCall() {
		authenticate(Role.SALES, null);

		OpportunityBoardService.Board board = service().forCaller();

		assertThat(board.columns()).isEmpty();
		assertThat(board.totalDeals()).isZero();
		verify(cache, never()).forPipelines(any());
		verify(opportunities, never()).inPipeline(anyString());
	}

	/**
	 * <strong>P1's answer, asserted rather than left to the comment.</strong> The GM's union is
	 * the configured selling brand's pipelines — a query over the roster, not a predicate.
	 */
	@Test
	void theGmSeesTheUnionOfTheSellingBrandsPipelines() {
		authenticate(Role.GM, null);
		when(teamMembers.findPipelinesOfActiveMembers(SELLING_BRAND)).thenReturn(List.of(MINE, THEIRS));
		givenFreshCache(List.of(
				cached("a", MINE, "s1", "100", Instant.now()),
				cached("b", THEIRS, "t1", "5", Instant.now())), MINE, THEIRS);

		OpportunityBoardService.Board board = service().forCaller();

		verify(teamMembers).findPipelinesOfActiveMembers(SELLING_BRAND);
		assertThat(board.totalDeals()).isEqualTo(2);
	}

	/** No configured selling brand means the GM's union is empty, not everything. */
	@Test
	void theGmSeesNothingWhenNoSellingBrandIsConfigured() {
		authenticate(Role.GM, null);
		OpportunityBoardService bare = new OpportunityBoardService(opportunities, pipelines, cache,
				teamMembers, TTL, "");

		assertThat(bare.forCaller().totalDeals()).isZero();
		verify(cache, never()).forPipelines(any());
	}

	/**
	 * <strong>Named for what it actually proves.</strong> A fresh cache means no *opportunity*
	 * read — it does not mean no GHL call at all: {@code draw} resolves stage names through
	 * {@code GhlPipelineClient.pipelines()} on every request, uncached.
	 *
	 * <pre>
	 * ponytail: one uncached pipeline-metadata request per board load. Cheap (one request, not a
	 * cursor loop) and it keeps a renamed stage showing renamed immediately. If board loads ever
	 * dominate the 100-per-10s budget, cache the stage list — not the opportunities again.
	 * </pre>
	 */
	/**
	 * A typo in `evalos.ghl.sales-brand` fails the boot, not the first GM board load.
	 *
	 * <p>Blank and malformed are different faults: blank is "no brand sells yet", a legitimate
	 * state that yields an empty union. A malformed UUID is a deployment mistake, and finding it
	 * as a 500 the first time a GM opens a board is finding it in the worst possible place.
	 */
	@Test
	void aMalformedSellingBrandFailsAtConstruction() {
		assertThatThrownBy(() -> new OpportunityBoardService(opportunities, pipelines, cache,
				teamMembers, TTL, "not-a-uuid"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("evalos.ghl.sales-brand");
	}

	@Test
	void aFreshCacheIsServedWithoutRefetchingOpportunities() {
		authenticate(Role.SALES, MINE);
		givenFreshCache(List.of(cached("a", MINE, "s1", "1", Instant.now())), MINE);

		service().forCaller();

		verify(opportunities, never()).inPipeline(anyString());
	}

	@Test
	void aStaleCacheIsRefilledFromGhl() {
		authenticate(Role.SALES, MINE);
		when(cache.isStale(eq(MINE), any())).thenReturn(true);
		when(opportunities.inPipeline(MINE)).thenReturn(List.of(
				new BoardOpportunity("a", "Acme", "contact_a", MINE, "s1", "open",
						new BigDecimal("100"), null)));
		when(cache.forPipelines(any()))
				.thenReturn(List.of(cached("a", MINE, "s1", "100", Instant.now())));

		service().forCaller();

		verify(opportunities).inPipeline(MINE);
		// Replaced wholesale: an opportunity that has left the pipeline has no fresh row to
		// update, so an upsert would strand it on the board forever. `OpportunityCache.replace`
		// is the only write path and it deletes first — asserted in its own test.
		verify(cache).replace(eq(MINE), any());
	}

	/** An empty cache is stale by definition — there is nothing to have been read recently. */
	@Test
	void anEmptyCacheIsFilledFromGhl() {
		authenticate(Role.SALES, MINE);
		when(cache.isStale(eq(MINE), any())).thenReturn(true);
		when(opportunities.inPipeline(MINE)).thenReturn(List.of());
		when(cache.forPipelines(any())).thenReturn(List.of());

		service().forCaller();

		verify(opportunities).inPipeline(MINE);
	}

	/**
	 * What reaches the cache is what GHL returned, never anything the caller supplied.
	 *
	 * <p>Asserted on the argument handed to {@link OpportunityCache#replace}; the mapping into
	 * rows is that class's own, and {@code OpportunityCacheTest} pins it against a real schema.
	 */
	@Test
	void theCacheIsWrittenFromGhlsAnswer() {
		authenticate(Role.SALES, MINE);
		when(cache.isStale(eq(MINE), any())).thenReturn(true);
		BoardOpportunity fromGhl = new BoardOpportunity("a", "Acme", "contact_a", MINE, "s1", "won",
				new BigDecimal("42"), null);
		when(opportunities.inPipeline(MINE)).thenReturn(List.of(fromGhl));
		when(cache.forPipelines(any())).thenReturn(List.of());

		service().forCaller();

		verify(cache).replace(MINE, List.of(fromGhl));
	}

	/**
	 * A stage GHL no longer lists still holds its cards, under the stage id.
	 *
	 * <p>Dropping the column would drop the deals in it, and a card that vanishes is a card
	 * somebody goes looking for. Sorted last, because it has no position to sort by.
	 */
	@Test
	void anUnknownStageKeepsItsDealsRatherThanLosingThem() {
		authenticate(Role.SALES, MINE);
		givenFreshCache(List.of(cached("a", MINE, "retired-stage", "7", Instant.now())), MINE);

		OpportunityBoardService.Board board = service().forCaller();

		assertThat(board.totalDeals()).isEqualTo(1);
		assertThat(board.columns()).extracting(OpportunityBoardService.BoardColumn::stageId)
				.contains("retired-stage");
		assertThat(board.columns().get(board.columns().size() - 1).stageId()).isEqualTo("retired-stage");
	}

	/** Empty stages are drawn, so a board reads like the pipeline rather than like its busy half. */
	@Test
	void emptyStagesStillAppearAsColumns() {
		authenticate(Role.SALES, MINE);
		givenFreshCache(List.of(cached("a", MINE, "s1", "1", Instant.now())), MINE);

		assertThat(service().forCaller().columns()).hasSize(2);
	}

	/** A null amount is not zero-cost to get wrong: it must not break the total. */
	@Test
	void aDealWithNoAmountDoesNotBreakTheTotal() {
		authenticate(Role.SALES, MINE);
		givenFreshCache(List.of(
				cached("a", MINE, "s1", null, Instant.now()),
				cached("b", MINE, "s1", "10", Instant.now())), MINE);

		assertThat(service().forCaller().totalValue()).isEqualByComparingTo("10");
	}

	@Test
	void theBoardCarriesItsOwnAgeSoTheReaderNeedNotTrustItBlindly() {
		authenticate(Role.SALES, MINE);
		Instant readAt = Instant.now().minusSeconds(30);
		givenFreshCache(List.of(cached("a", MINE, "s1", "1", readAt)), MINE);

		OpportunityBoardService.Board board = service().forCaller();

		assertThat(board.readAt()).isEqualTo(readAt);
		assertThat(board.stale()).isFalse();
	}

	@Test
	void theStageOrderFollowsGhlsOwnPositions() {
		authenticate(Role.SALES, MINE);
		givenFreshCache(List.of(
				cached("b", MINE, "s2", "1", Instant.now()),
				cached("a", MINE, "s1", "1", Instant.now())), MINE);

		assertThat(service().forCaller().columns())
				.extracting(OpportunityBoardService.BoardColumn::stageId)
				.containsExactly("s1", "s2");
	}

	/** Only the caller's own pipeline is refilled, even when GHL knows about others. */
	@Test
	void onlyTheCallersPipelineIsFetched() {
		authenticate(Role.SALES, MINE);
		when(cache.isStale(eq(MINE), any())).thenReturn(true);
		when(opportunities.inPipeline(MINE)).thenReturn(List.of());
		when(cache.forPipelines(any())).thenReturn(List.of());

		service().forCaller();

		verify(opportunities).inPipeline(MINE);
		verify(opportunities, never()).inPipeline(eq(THEIRS));
	}
}
