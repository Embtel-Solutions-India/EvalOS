package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.ie.evalos.domain.CachedOpportunity;
import com.ie.evalos.integration.GhlOpportunityClient.BoardOpportunity;
import com.ie.evalos.repository.CachedOpportunityRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of the opportunity cache, kept apart from the board that reads it.
 *
 * <p><strong>This exists so a GHL request is never made inside a database transaction.</strong>
 * The board's flow is: check the age, fetch from GHL if stale, write what came back. Wrapping all
 * three in one {@code @Transactional} method — which is what the first version of
 * {@link OpportunityBoardService} did — holds a connection from the pool open across an HTTP call
 * that can take seconds. A handful of people opening boards at once would then be a handful of
 * connections doing nothing but waiting on the network, and the symptom would be pool exhaustion
 * somewhere else entirely.
 *
 * <p>So the transaction is drawn tightly around the write, and it has to be a separate bean:
 * Spring's {@code @Transactional} is proxy-based, so a service calling its own annotated method
 * gets no transaction at all — the annotation would be there, look right, and do nothing.
 *
 * <p><strong>The read side is deliberately not transactional.</strong> A single select needs no
 * transaction, and one here would only widen what is held.
 */
@Component
public class OpportunityCache {

	private final CachedOpportunityRepository rows;

	OpportunityCache(CachedOpportunityRepository rows) {
		this.rows = rows;
	}

	/** Whether this pipeline's copy is older than the TTL, or absent entirely. */
	public boolean isStale(String pipelineId, Duration ttl) {
		Instant lastFetched = rows.lastFetchedFor(pipelineId);
		// Absent reads as stale: there is nothing to have been read recently.
		return lastFetched == null || Duration.between(lastFetched, Instant.now()).compareTo(ttl) >= 0;
	}

	public List<CachedOpportunity> forPipelines(Collection<String> pipelineIds) {
		return rows.findByGhlPipelineIdIn(pipelineIds);
	}

	/**
	 * Replaces one pipeline's contents with what GHL just returned.
	 *
	 * <p><strong>Wholesale, never an upsert.</strong> An opportunity that has left the pipeline
	 * has no row in {@code fresh} to update, so an upsert would leave it on the board forever —
	 * the failure mode of every incremental cache that never deletes.
	 *
	 * <p><strong>And only ever from GHL's response.</strong> The parameter type says so: these
	 * are rows that came back over the wire, not anything a caller composed. Writing a cache
	 * from a request body is what turns it into a second source of truth.
	 */
	@Transactional
	public void replace(String pipelineId, List<BoardOpportunity> fresh) {
		Instant now = Instant.now();
		rows.deleteByGhlPipelineId(pipelineId);
		rows.flush();
		rows.saveAll(fresh.stream()
				.map((row) -> new CachedOpportunity(row.id(), pipelineId, row.contactId(),
						row.pipelineStageId(), row.status(), row.name(), row.monetaryValue(),
						row.updatedAt(), now))
				.toList());
	}

	/**
	 * Drops one opportunity's copy.
	 *
	 * <p>Nothing calls this yet. Units 39 and 40 write to GHL, and a write whose response cannot
	 * be trusted to be complete is a row that should be refetched rather than patched.
	 */
	@Transactional
	public void evict(String opportunityId) {
		rows.evict(opportunityId);
	}
}
