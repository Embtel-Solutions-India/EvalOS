package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.OpportunityNote;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * The note stream, scoped the way every EvalOS row is.
 *
 * <p><strong>Unlike {@code CachedOpportunityRepository}, this one CAN go through
 * {@code ScopePredicate}</strong> — a note carries a {@code brand_id}, because it is an EvalOS
 * row whatever the opportunity it describes is. So reads compose the usual scope Specification
 * with {@code Fields.brandAndPipeline}, and the {@code Tier.PIPELINE} axis Unit 36 shipped
 * finally has a production entity behind it.
 *
 * <p>The one derived finder below still takes the opportunity id, because the stream is always
 * read for a single deal. It is <strong>not</strong> a scope on its own — the caller composes it
 * with the Specification, and {@code OpportunityNoteService} is the only place that happens.
 */
public interface OpportunityNoteRepository
		extends JpaRepository<OpportunityNote, UUID>, JpaSpecificationExecutor<OpportunityNote> {

	/**
	 * One deal's stream, newest first.
	 *
	 * <p>Deliberately <em>not</em> the scoped read on its own: an opportunity id is not a scope,
	 * because it is a value the caller supplies. {@code OpportunityNoteService} checks the
	 * opportunity is in the caller's pipeline first, and this only orders what that allowed.
	 *
	 * <p><strong>Brand-scoped as well</strong> (2026-09-24): the brand is the deal's, which the
	 * service has just authorised, so a GHL id colliding across brands cannot mix two streams.
	 */
	List<OpportunityNote> findByBrandIdAndGhlOpportunityIdOrderByCreatedAtDesc(UUID brandId,
			String ghlOpportunityId);
}
