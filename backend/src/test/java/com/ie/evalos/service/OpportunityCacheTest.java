package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.ie.evalos.domain.CachedOpportunity;
import com.ie.evalos.integration.GhlOpportunityClient.BoardOpportunity;
import com.ie.evalos.repository.CachedOpportunityRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cache's two load-bearing properties: it maps GHL's answer faithfully, and it <em>replaces</em>
 * a pipeline rather than merging into it.
 *
 * <p>Both are the kind of thing that looks fine in review and is wrong in production — a mapping
 * that drops a field shows an empty card, and an upsert instead of a replace strands a closed deal
 * on somebody's board indefinitely.
 */
class OpportunityCacheTest {

	private static final String PIPELINE = "pipe_mine";

	private final CachedOpportunityRepository rows = mock(CachedOpportunityRepository.class);
	private final OpportunityCache cache = new OpportunityCache(rows);

	@Test
	void everyFieldGhlSendsReachesTheRow() {
		Instant updated = Instant.parse("2026-06-01T10:00:00Z");
		cache.replace(PIPELINE, List.of(new BoardOpportunity("o1", "Acme Corp", "contact_1", PIPELINE,
				"s1", "open", new BigDecimal("1200"), updated)));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<CachedOpportunity>> saved = ArgumentCaptor.forClass(List.class);
		verify(rows).saveAll(saved.capture());

		assertThat(saved.getValue()).singleElement().satisfies((row) -> {
			assertThat(row.getGhlOpportunityId()).isEqualTo("o1");
			// From the request, not the payload: it is the scope that was asked for.
			assertThat(row.getGhlPipelineId()).isEqualTo(PIPELINE);
			assertThat(row.getGhlContactId()).isEqualTo("contact_1");
			assertThat(row.getStageId()).isEqualTo("s1");
			assertThat(row.getStatus()).isEqualTo("open");
			assertThat(row.getName()).isEqualTo("Acme Corp");
			assertThat(row.getAmount()).isEqualByComparingTo("1200");
			assertThat(row.getUpdatedInGhlAt()).isEqualTo(updated);
			assertThat(row.getFetchedAt()).isNotNull();
		});
	}

	/**
	 * <strong>Delete before write, in that order.</strong>
	 *
	 * <p>An opportunity that has left the pipeline has no row in the fresh read to update, so an
	 * upsert would leave it on the board forever. The order is asserted rather than just the pair:
	 * saving first and deleting after would wipe exactly what was written.
	 */
	@Test
	void aPipelineIsReplacedNotMergedInto() {
		cache.replace(PIPELINE, List.of(new BoardOpportunity("o1", "A", "c1", PIPELINE, "s1", "open",
				BigDecimal.ONE, null)));

		InOrder order = inOrder(rows);
		order.verify(rows).deleteByGhlPipelineId(PIPELINE);
		order.verify(rows).flush();
		order.verify(rows).saveAll(anyList());
	}

	/** An emptied pipeline clears its rows rather than leaving the last read standing. */
	@Test
	void anEmptyReadStillClearsThePipeline() {
		cache.replace(PIPELINE, List.of());

		verify(rows).deleteByGhlPipelineId(PIPELINE);
		verify(rows).saveAll(List.of());
	}

	/** A null amount and a null timestamp are carried through as null, not coerced. */
	@Test
	void nullsAreCarriedRatherThanInvented() {
		cache.replace(PIPELINE, List.of(new BoardOpportunity("o1", null, "c1", PIPELINE, "s1", "open",
				null, null)));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<CachedOpportunity>> saved = ArgumentCaptor.forClass(List.class);
		verify(rows).saveAll(saved.capture());

		assertThat(saved.getValue()).singleElement().satisfies((row) -> {
			assertThat(row.getAmount()).isNull();
			assertThat(row.getUpdatedInGhlAt()).isNull();
			assertThat(row.getName()).isNull();
			// fetchedAt is ours, not GHL's, so it is always set.
			assertThat(row.getFetchedAt()).isNotNull();
		});
	}

	/** An absent pipeline is stale: there is nothing to have been read recently. */
	@Test
	void anEmptyPipelineIsStale() {
		when(rows.lastFetchedFor(PIPELINE)).thenReturn(null);

		assertThat(cache.isStale(PIPELINE, Duration.ofMinutes(2))).isTrue();
	}

	@Test
	void aRecentReadIsFresh() {
		when(rows.lastFetchedFor(PIPELINE)).thenReturn(Instant.now().minusSeconds(5));

		assertThat(cache.isStale(PIPELINE, Duration.ofMinutes(2))).isFalse();
	}

	@Test
	void aReadOlderThanTheTtlIsStale() {
		when(rows.lastFetchedFor(PIPELINE)).thenReturn(Instant.now().minus(Duration.ofMinutes(10)));

		assertThat(cache.isStale(PIPELINE, Duration.ofMinutes(2))).isTrue();
	}

	/** Exactly at the TTL counts as stale — the boundary goes the safe way, towards refetching. */
	@Test
	void theTtlBoundaryRefetches() {
		Duration ttl = Duration.ofMinutes(2);
		when(rows.lastFetchedFor(PIPELINE)).thenReturn(Instant.now().minus(ttl));

		assertThat(cache.isStale(PIPELINE, ttl)).isTrue();
	}
}
