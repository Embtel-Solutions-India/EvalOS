package com.ie.evalos.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

	/**
	 * Retires a local edit that GHL has now confirmed — <strong>only if it is still the same
	 * edit</strong> (Unit 46, corrected).
	 *
	 * <p><strong>A statement rather than {@code save(row)}, and that is the whole point.</strong>
	 * The drain reads the row, spends a GHL round trip, then comes back to clear the stamp. Merging
	 * the entity it read is a lost update: a re-price that landed while the push was in flight is
	 * overwritten by the values from before it, has {@code local_updated_at} cleared so 45e stops
	 * defending it, and — because the outbox collapses onto a pending row that is about to be
	 * marked sent — never gets pushed either. The edit disappears from both systems.
	 *
	 * <p>Guarding on {@code local_updated_at} is what makes it safe. Zero rows means the row moved
	 * under the push, so nothing is cleared and the drain re-queues instead; the stamp and the
	 * field list go together because they describe one fact.
	 *
	 * <p>{@code @Transactional} here, not only on the caller: a bulk update throws
	 * {@code TransactionRequiredException} without one, and the drain deliberately sends each row
	 * outside a transaction.
	 *
	 * <p>The parameter is {@code opportunityId} rather than {@code id} because
	 * {@code OpportunityRepositoryScopeTest} reads these signatures for the pipeline axis that
	 * {@code SCOPE} cannot carry until 44b. A primary key is the narrowest scope there is, and the
	 * name is what makes that visible to the guard as well as to a reader.
	 *
	 * @return 1 when the edit was retired, 0 when a newer edit has replaced it
	 */
	@Modifying(clearAutomatically = true)
	@Transactional
	@Query("update Opportunity o set o.localUpdatedAt = null, o.locallyEditedFields = null "
			+ "where o.id = :opportunityId and o.localUpdatedAt = :seen")
	int confirmPushed(@Param("opportunityId") UUID opportunityId,
			@Param("seen") java.time.Instant seen);

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

}
