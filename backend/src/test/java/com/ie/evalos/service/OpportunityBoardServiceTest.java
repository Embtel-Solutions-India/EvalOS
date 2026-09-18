package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.PipelineStage;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
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
	/** How old the mirror may be before the board says so. Unit 46: a label, never a refresh. */
	private static final Duration STALE_AFTER = Duration.ofMinutes(30);

	private final OpportunityMirrorService deals = mock(OpportunityMirrorService.class);
	private final PipelineMirrorService pipelines = mock(PipelineMirrorService.class);
	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
	private final com.ie.evalos.repository.TeamMemberPipelineRepository assignments =
			mock(com.ie.evalos.repository.TeamMemberPipelineRepository.class);

	private OpportunityBoardService service() {
		return new OpportunityBoardService(deals, pipelines, teamMembers, assignments, STALE_AFTER,
				new SellingBrand(SELLING_BRAND));
	}

	private void authenticate(Role role, String pipelineId) {
		StaffPrincipal principal = new StaffPrincipal(MEMBER, role + "@ie.test", "Desk", role,
				role == Role.GM ? null : SELLING_BRAND, null, pipelineId, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	/**
	 * The stage names come from the MIRROR as of Unit 44a, not from a live GHL read.
	 *
	 * <p>The fixture shape changed with it and the assertions did not, which is the point: the
	 * board draws the same columns from local rows that it used to draw from a network call on
	 * every render.
	 */
	@BeforeEach
	void stubPipelineStages() {
		Pipeline mine = mirrored(MINE, "My pipeline", 0);
		Pipeline theirs = mirrored(THEIRS, "Their pipeline", 1);
		when(pipelines.all()).thenReturn(List.of(mine, theirs));
		when(pipelines.stagesOf(mine.getId())).thenReturn(List.of(
				stage(mine, "s1", "New", 0), stage(mine, "s2", "Warm", 1)));
		when(pipelines.stagesOf(theirs.getId())).thenReturn(List.of(
				stage(theirs, "t1", "Theirs", 0)));
	}

	private static Pipeline mirrored(String ghlId, String name, int position) {
		Pipeline pipeline = new Pipeline(SELLING_BRAND, ghlId, name, position);
		ReflectionTestUtils.setField(pipeline, "id", UUID.randomUUID());
		return pipeline;
	}

	private static PipelineStage stage(Pipeline pipeline, String ghlId, String name, int position) {
		PipelineStage row = new PipelineStage(SELLING_BRAND, pipeline.getId(), ghlId, name, position);
		ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
		return row;
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	/**
	 * One mirrored row, as {@code OpportunityMirrorService.onPipelines} would hand it back.
	 *
	 * <p>The pipeline is passed as GHL's id and is not stored on the row — the entity holds
	 * EvalOS's {@code pipeline_id} — because these tests are about what the board DRAWS, and the
	 * board asks the mirror for "the rows on these GHL pipelines" rather than filtering them
	 * itself.
	 */
	private static Opportunity mirrored(String id, String pipelineId, String stageId, String amount,
			Instant syncedAt) {
		Opportunity row = new Opportunity(SELLING_BRAND, id, UUID.randomUUID());
		row.syncFromGhl("contact_" + id, row.getPipelineId(), stageId, "Deal " + id,
				amount == null ? null : new BigDecimal(amount), "open", null, null, null, syncedAt,
				null, null);
		return row;
	}

	/** The mirror already holds these rows, so nothing is read from GHL. */
	private void givenMirrored(List<Opportunity> rows, String... pipelineIds) {
		when(deals.onPipelines(any())).thenReturn(rows);
		when(deals.lastSynced(any())).thenReturn(Instant.now());
	}

	@Test
	void aSalesCallerSeesTheirOwnPipelineGroupedByStage() {
		authenticate(Role.SALES, MINE);
		givenMirrored(List.of(
				mirrored("a", MINE, "s1", "100", Instant.now()),
				mirrored("b", MINE, "s2", "250", Instant.now())), MINE);

		OpportunityBoardService.Board board = service().forCaller();

		assertThat(board.columns()).extracting(OpportunityBoardService.BoardColumn::stageName)
				.containsExactly("New", "Warm");
		assertThat(board.totalDeals()).isEqualTo(2);
		assertThat(board.totalValue()).isEqualByComparingTo("350");
	}

	@Test
	void marketingIsScopedTheSameWay() {
		authenticate(Role.MARKETING, MINE);
		givenMirrored(List.of(mirrored("a", MINE, "s1", "10", Instant.now())), MINE);

		assertThat(service().forCaller().totalDeals()).isEqualTo(1);
	}

	/**
	 * <strong>The scope is the query, not a predicate somebody remembers to add.</strong>
	 *
	 * <p>{@code OpportunityRepository.SCOPE} is {@code brandOnly} until slice 44b lines the
	 * pipeline axis up with {@code ScopePredicate}, so until then the scope is that every read
	 * requires the pipeline ids and they come from the principal. This asserts the value actually
	 * handed to the mirror, which is the only place it could go wrong.
	 */
	@Test
	void anotherPipelineIsNeverRead() {
		authenticate(Role.SALES, MINE);
		givenMirrored(List.of(), MINE);

		service().forCaller();

		verify(deals).onPipelines(List.of(MINE));
	}

	/** Fail closed, exactly as {@code ScopePredicate}'s PIPELINE arm does. */
	@Test
	void aCallerWithNoPipelineSeesAnEmptyBoardAndNoGhlCall() {
		authenticate(Role.SALES, null);

		OpportunityBoardService.Board board = service().forCaller();

		assertThat(board.columns()).isEmpty();
		assertThat(board.totalDeals()).isZero();
		verify(deals, never()).onPipelines(any());
		verify(deals, never()).refreshIfStale(anyString(), any());
	}

	/**
	 * <strong>P1's answer, corrected 2026-09-17: the GM sees every MIRRORED pipeline.</strong>
	 *
	 * <p>It used to be the union of what active members are assigned to, which hid exactly the
	 * pipelines that belong to the business rather than to a person — {@code 00d} §6.7's Case
	 * Delivery, "a pipeline no single person owns", and the location's Master Pipeline. It also
	 * read {@code team_member.ghl_pipeline_id}, the column Unit 44b replaced with
	 * {@code team_member_pipeline}, so it answered empty once assignment moved.
	 */
	@Test
	void theGmSeesEveryMirroredPipelineIncludingOnesNobodyIsAssignedTo() {
		authenticate(Role.GM, null);
		givenMirrored(List.of(
				mirrored("a", MINE, "s1", "100", Instant.now()),
				mirrored("b", THEIRS, "t1", "5", Instant.now())), MINE, THEIRS);

		OpportunityBoardService.Board board = service().forCaller();

		assertThat(board.totalDeals()).isEqualTo(2);
		// The roster is not consulted at all: an unassigned pipeline is still the GM's to see.
		verify(teamMembers, never()).findPipelinesOfActiveMembers(any());
	}

	/** A pipeline GHL stopped returning is not offered, even to the GM. */
	@Test
	void theGmDoesNotSeeAPipelineThatHasLeftGhl() {
		authenticate(Role.GM, null);
		com.ie.evalos.domain.Pipeline gone =
				new com.ie.evalos.domain.Pipeline(SELLING_BRAND, THEIRS, "Retired", 1);
		gone.markMissing(Instant.now());
		com.ie.evalos.domain.Pipeline live =
				new com.ie.evalos.domain.Pipeline(SELLING_BRAND, MINE, "Sales", 0);
		when(pipelines.all()).thenReturn(List.of(live, gone));
		when(deals.onPipelines(any())).thenReturn(List.of());
		when(deals.lastSynced(any())).thenReturn(Instant.now());

		service().forCaller();

		verify(deals).onPipelines(List.of(MINE));
	}

	/**
	 * <strong>A fresh cache now means no GHL call at all.</strong>
	 *
	 * <p>It used to mean no *opportunity* read only: {@code draw} resolved stage names through
	 * {@code GhlPipelineClient.pipelines()} on every request, uncached, and the old note here said
	 * to cache the stage list if board loads ever dominated the budget. Unit 44a did better than
	 * cache it — the stages are mirrored rows now, so the lookup left the network entirely.
	 */
	/**
	 * <strong>The board draws mirror rows and makes no GHL request at all</strong> — Unit 46, and
	 * the headline `00c` §3 gives that unit.
	 *
	 * <p>It used to call {@code refreshIfStale} per pipeline on every load. That was right while
	 * nothing else kept the mirror current; 45d's webhooks and {@code MIRROR_DELTA} changed the
	 * premise, so the refill went. This is the assertion that fails if somebody puts a read back
	 * on the request path — which would be easy to do and invisible until the rate budget ran out.
	 */
	@Test
	void theBoardDrawsMirrorRowsAndRefreshesNothing() {
		authenticate(Role.SALES, MINE);
		givenMirrored(List.of(mirrored("a", MINE, "s1", "1", Instant.now())), MINE);

		assertThat(service().forCaller().totalDeals()).isEqualTo(1);

		verify(deals).onPipelines(List.of(MINE));
		verify(deals, never()).refreshIfStale(any(), any());
	}

	/**
	 * A mirror nobody has refreshed in a while draws anyway, and says so.
	 *
	 * <p>{@code stale} changed meaning at Unit 46: it was "this render did not refill", a fact
	 * about one request, and it is now "the mirror has not been confirmed against GHL lately", a
	 * fact about the sync. It is the only staleness signal a reader has left, so it is worth a test.
	 */
	@Test
	void anUnconfirmedMirrorStillDrawsAndIsFlaggedStale() {
		authenticate(Role.SALES, MINE);
		givenMirrored(List.of(mirrored("a", MINE, "s1", "100", Instant.now())), MINE);
		// The age that decides is the mirror's last confirmation against GHL, not the row's own
		// stamp: an empty pipeline has no row to carry one.
		when(deals.lastSynced(any())).thenReturn(Instant.now().minus(java.time.Duration.ofHours(2)));

		OpportunityBoardService.Board board = service().forCaller();

		assertThat(board.totalDeals()).isEqualTo(1);
		assertThat(board.stale()).isTrue();
		verify(deals, never()).refreshIfStale(any(), any());
	}

	/**
	 * <strong>The write path is an upsert now, and that is the whole point of slice 44d.</strong>
	 *
	 * <p>The three tests this replaced asserted {@code OpportunityCache.replace} — delete-all then
	 * insert-all per pipeline, which destroys every row's identity on every board refresh.
	 * {@code 00d} §6.5 calls that the deepest of five reasons the cache could not be salvaged:
	 * nothing can hold a foreign key into it, and a portal-born row with no {@code ghl_id} dies on
	 * the next refresh. What the board guarantees is now narrower and truer — it asks for a refresh
	 * and draws what it is given.
	 */
	@Test
	void theBoardNeverWritesToTheMirrorItself() {
		authenticate(Role.SALES, MINE);
		when(deals.onPipelines(any())).thenReturn(List.of());

		service().forCaller();

		verify(deals, never()).absorb(any(), any());
		verify(deals, never()).openLocally(any(), any(), any());
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
		givenMirrored(List.of(mirrored("a", MINE, "retired-stage", "7", Instant.now())), MINE);

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
		givenMirrored(List.of(mirrored("a", MINE, "s1", "1", Instant.now())), MINE);

		assertThat(service().forCaller().columns()).hasSize(2);
	}

	/** A null amount is not zero-cost to get wrong: it must not break the total. */
	@Test
	void aDealWithNoAmountDoesNotBreakTheTotal() {
		authenticate(Role.SALES, MINE);
		givenMirrored(List.of(
				mirrored("a", MINE, "s1", null, Instant.now()),
				mirrored("b", MINE, "s1", "10", Instant.now())), MINE);

		assertThat(service().forCaller().totalValue()).isEqualByComparingTo("10");
	}

	/**
	 * <strong>The age is asked of the PIPELINES, not of the rows.</strong>
	 *
	 * <p>It used to be {@code max(fetched_at)} across the cached rows, which quietly reported "now"
	 * for an empty pipeline — a board with no deals looked freshly read when nothing had been read
	 * at all. The mirror answers the question properly, because {@code synced_at} belongs to the
	 * pipeline's last confirmation rather than to whichever rows happen to exist.
	 */
	@Test
	void theBoardCarriesItsOwnAgeSoTheReaderNeedNotTrustItBlindly() {
		authenticate(Role.SALES, MINE);
		Instant readAt = Instant.now().minusSeconds(30);
		when(deals.onPipelines(any())).thenReturn(List.of(mirrored("a", MINE, "s1", "1", readAt)));
		when(deals.lastSynced(any())).thenReturn(readAt);

		OpportunityBoardService.Board board = service().forCaller();

		assertThat(board.lastSyncedAt()).isEqualTo(readAt);
		assertThat(board.stale()).isFalse();
	}

	/**
	 * A pipeline nothing has ever synced still answers — and <strong>says it has never synced</strong>.
	 *
	 * <p>The age used to fall back to {@code Instant.now()} here, which made the payload well-formed
	 * by telling the reader the board was confirmed at the one moment nothing had ever been read.
	 * Unit 46 makes that indicator load-bearing — the board has no other freshness signal — so null
	 * travels as null and the row counts as stale. Not knowing is not the same as being fresh.
	 */
	@Test
	void anUnsyncedPipelineSaysSoRatherThanClaimingItIsCurrent() {
		authenticate(Role.SALES, MINE);
		when(deals.onPipelines(any())).thenReturn(List.of());
		when(deals.lastSynced(any())).thenReturn(null);

		OpportunityBoardService.Board board = service().forCaller();

		assertThat(board.lastSyncedAt()).isNull();
		assertThat(board.stale()).isTrue();
		assertThat(board.totalDeals()).isZero();
	}

	/**
	 * The Refresh button reconciles the caller's own pipelines and nothing else.
	 *
	 * <p>A salesperson's refresh must not spend GHL's shared budget on pipelines they cannot see;
	 * the background sweep is what covers those.
	 */
	@Test
	void aManualSyncRefreshesOnlyTheCallersOwnPipelines() {
		authenticate(Role.SALES, MINE);
		givenMirrored(List.of(), MINE);

		service().syncNow();

		verify(deals).refreshIfStale(eq(MINE), any());
		verify(deals, never()).refreshIfStale(eq(THEIRS), any());
	}

	@Test
	void theStageOrderFollowsGhlsOwnPositions() {
		authenticate(Role.SALES, MINE);
		givenMirrored(List.of(
				mirrored("b", MINE, "s2", "1", Instant.now()),
				mirrored("a", MINE, "s1", "1", Instant.now())), MINE);

		assertThat(service().forCaller().columns())
				.extracting(OpportunityBoardService.BoardColumn::stageId)
				.containsExactly("s1", "s2");
	}

	/** Only the caller's own pipeline is read, even when the mirror holds others. */
	@Test
	void onlyTheCallersPipelineIsRead() {
		authenticate(Role.SALES, MINE);
		when(deals.onPipelines(any())).thenReturn(List.of());

		service().forCaller();

		verify(deals).onPipelines(List.of(MINE));
		verify(deals, never()).onPipelines(List.of(THEIRS));
	}

	/**
	 * <strong>The trap this closes.</strong> A desk signs in before the mirror has run, is granted
	 * its pipeline minutes later by the backfill, and would otherwise see an empty board for the
	 * rest of the session with "ask a GM" as the only advice. D19b's token claim is a staleness
	 * bound for a REASSIGNMENT; it must not be one for a first assignment.
	 */
	@Test
	void aDeskWhoseTokenPredatesItsAssignmentStillSeesItsPipeline() {
		authenticate(Role.SALES, null);
		when(assignments.ghlIdsFor(MEMBER)).thenReturn(List.of(MINE));
		givenMirrored(List.of(mirrored("a", MINE, "s1", "100", Instant.now())), MINE);

		assertThat(service().forCaller().totalDeals()).isEqualTo(1);
	}

	/** A member with no assignment anywhere still sees nothing — the fallback widens no access. */
	@Test
	void aDeskWithNoAssignmentAnywhereStillSeesAnEmptyBoard() {
		authenticate(Role.SALES, null);
		when(assignments.ghlIdsFor(MEMBER)).thenReturn(List.of());

		assertThat(service().forCaller().totalDeals()).isZero();
		verify(deals, never()).onPipelines(any());
	}

	/**
	 * <strong>Refresh syncs pipelines before deals — in the state it was added for.</strong> An
	 * empty mirror has no pipelines to refresh deals <em>for</em>, so an opportunities-only refresh
	 * could not fix the thing the button is offered for.
	 */
	@Test
	void theRefreshButtonSyncsPipelineStructureWhenTheMirrorHasNone() {
		authenticate(Role.SALES, MINE);
		when(pipelines.all()).thenReturn(List.of());
		givenMirrored(List.of(), MINE);

		service().syncNow();

		verify(pipelines).sync();
	}

	/**
	 * <strong>And only in that state.</strong> This test is the other half, and it is the one the
	 * cost lives in: the structure read is a paged GHL call on a location whose pipelines change a
	 * few times a year, MANUAL_SYNC_FLOOR guards the deal reads and not this one, and a GM's board
	 * is every live pipeline — so an ungated press spent a structure read plus a fan-out every
	 * time anybody pressed it. The hourly PIPELINE_MIRROR sweep is what keeps structure current.
	 */
	@Test
	void theRefreshButtonLeavesPipelineStructureAloneWhenTheMirrorAlreadyHasIt() {
		authenticate(Role.SALES, MINE);
		givenMirrored(List.of(), MINE);

		service().syncNow();

		// The @BeforeEach mirror holds two live pipelines, which is every state but the first run.
		verify(pipelines, never()).sync();
		verify(deals).refreshIfStale(eq(MINE), any());
	}
}
