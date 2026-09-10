package com.ie.evalos.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.ie.evalos.domain.CachedOpportunity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and evictions for the opportunity cache.
 *
 * <p><strong>Every finder here takes the pipeline as a parameter, and that is the scoping
 * mechanism.</strong> The cache carries no {@code brand_id}, so {@code ScopePredicate} cannot be
 * applied to it — and rather than adding a column that would hold one value and only look like a
 * scope, the scope is made structural: the pipeline ids come from the caller's own principal, and
 * <strong>there is no finder that returns rows without them</strong>.
 *
 * <p>That is stronger than a predicate, not weaker. A predicate is something a query can forget;
 * a required parameter is not. {@code CachedOpportunityRepositoryScopeTest} fails the build if an
 * unscoped read is ever added here — including the {@code findAll} every {@code JpaRepository}
 * inherits, which is why callers must never reach for it.
 */
public interface CachedOpportunityRepository extends JpaRepository<CachedOpportunity, String> {

	/** One pipeline's board. The only read a screen makes. */
	List<CachedOpportunity> findByGhlPipelineId(String ghlPipelineId);

	/** The GM's union, over the pipelines of active sales and marketing members. */
	List<CachedOpportunity> findByGhlPipelineIdIn(Collection<String> ghlPipelineIds);

	/**
	 * Whether one opportunity is in one pipeline — the check in front of every write.
	 *
	 * <p>Both parameters are required, which is what keeps it a scope check rather than an
	 * existence oracle: it can only ever answer "is this one mine", never "does this exist".
	 */
	boolean existsByGhlOpportunityIdAndGhlPipelineId(String ghlOpportunityId, String ghlPipelineId);

	/**
	 * The freshest row in a pipeline, which is how the TTL is judged.
	 *
	 * <p>Freshest rather than oldest: a pipeline is refilled as a unit, so every row shares a
	 * {@code fetched_at}. Taking the maximum means a single straggler row written by a webhook
	 * refresh does not make the whole board look fresh — that row would be the maximum, but it is
	 * also genuinely the most recent read, which is the question being asked.
	 */
	@Query("select max(o.fetchedAt) from CachedOpportunity o where o.ghlPipelineId = :pipelineId")
	Instant lastFetchedFor(@Param("pipelineId") String pipelineId);

	/**
	 * Replaces a pipeline's contents. Called with the rows GHL just returned, never with a
	 * request body.
	 */
	@Modifying
	@Query("delete from CachedOpportunity o where o.ghlPipelineId = :pipelineId")
	void deleteByGhlPipelineId(@Param("pipelineId") String pipelineId);

	/**
	 * Evicts one opportunity, for the inbound-webhook path: GHL told us the row changed, so the
	 * copy is wrong and the next read refetches it.
	 */
	@Modifying
	@Query("delete from CachedOpportunity o where o.ghlOpportunityId = :opportunityId")
	void evict(@Param("opportunityId") String opportunityId);
}
