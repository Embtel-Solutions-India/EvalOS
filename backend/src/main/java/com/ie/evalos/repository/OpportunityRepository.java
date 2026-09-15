package com.ie.evalos.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** The mirrored GHL opportunities (Unit 44d). */
public interface OpportunityRepository
		extends JpaRepository<Opportunity, UUID>, JpaSpecificationExecutor<Opportunity> {

	/**
	 * Brand only — for now, and the "for now" is the point.
	 *
	 * <p>This row has a pipeline, so {@code brandAndPipeline} is the shape it will take. It cannot
	 * yet: {@code ScopePredicate}'s pipeline arm compares a <em>GHL</em> pipeline id from the
	 * caller's principal, and this entity holds EvalOS's {@code pipeline_id}. Slice <strong>44b</strong>
	 * is where that is resolved — {@code team_member_pipeline} replaces
	 * {@code team_member.ghl_pipeline_id} and {@code PipelineScope.mine()} becomes a set — and
	 * declaring an axis that does not line up would be the silent widening {@code DomainInvariantsTest}
	 * exists to catch.
	 *
	 * <p>Until then the desks reach opportunities through {@code PipelineScope}, which narrows by
	 * pipeline explicitly at every call site, so nothing reads wider than it did through the cache.
	 */
	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	List<Opportunity> findByPipelineIdIn(Collection<UUID> pipelineIds);

	Optional<Opportunity> findByBrandIdAndGhlId(UUID brandId, String ghlId);

	boolean existsByBrandIdAndGhlIdAndPipelineId(UUID brandId, String ghlId, UUID pipelineId);

	/**
	 * One contact's deals — the EvalOS half of the retry question (`00d` §6.1).
	 *
	 * <p>GHL's opportunity search offers <strong>no filter on a custom field</strong>, verified
	 * against the API, so "search the correlation field before creating" is not a query anyone can
	 * write. The implementable form is: ask GHL for this contact's opportunities and match the
	 * correlation value locally. This finder answers the same question on the EvalOS side.
	 */
	List<Opportunity> findByBrandIdAndGhlContactId(UUID brandId, String ghlContactId);

	/**
	 * When this pipeline's copy was last confirmed against GHL, or null if never.
	 *
	 * <p>The staleness bound the board reads, and the replacement for
	 * {@code CachedOpportunityRepository.lastFetchedFor}. <strong>Rows that have never reached GHL
	 * are excluded</strong>: a portal-born opportunity carries no {@code synced_at} from a read,
	 * and counting its absence would make a pipeline look freshly synced because somebody opened a
	 * request on it.
	 */
	@org.springframework.data.jpa.repository.Query("select max(o.syncedAt) from Opportunity o "
			+ "where o.pipelineId = :pipelineId and o.ghlId is not null")
	java.time.Instant lastSyncedFor(@org.springframework.data.repository.query.Param("pipelineId") UUID pipelineId);
}
