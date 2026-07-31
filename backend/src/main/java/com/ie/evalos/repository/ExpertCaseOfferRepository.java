package com.ie.evalos.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Offers are brand-wide like the roster they are about: an expert is not shared across brands,
 * so neither is their record.
 *
 * <p>Brand-only, with no assignee axis. The expert named here is not a staff assignee — they
 * are the subject of the row, not a principal who can read it — and the case's own assignee
 * scoping is applied by {@code CaseRepository} on the read that found the case in the first
 * place.
 */
public interface ExpertCaseOfferRepository extends ScopedRepository<ExpertCaseOffer> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/**
	 * The still-open offer on a case, which the three resolving transitions stamp.
	 *
	 * <p>A list rather than an {@code Optional} even though there is at most one: a derived
	 * {@code Optional} finder throws on a second row, and turning "somehow two open offers"
	 * into a 500 on a legitimate decline is worse than stamping both. Backed by V19's partial
	 * index on {@code (case_id) WHERE outcome = 'OFFERED'}.
	 *
	 * <p>No brand predicate, by the same convention as the other batched finders: the case id
	 * passed in has already come out of a scoped read, and a row can only exist for a case that
	 * exists. Do not call it with an id that arrived from a client.
	 */
	List<ExpertCaseOffer> findByCaseIdAndOutcome(UUID caseId, OfferOutcome outcome);

	/**
	 * How many offers each of these experts resolved which way — the aggregate the acceptance
	 * factor is built on, one query per shortlist rather than one per expert.
	 *
	 * <p>Brand is a parameter and not a scope predicate for the reason
	 * {@code ExpertRepository.findByBrandIdAndEmailIgnoreCase} gives: it is resolved once, from
	 * the case being staffed, and re-deriving it per expert would answer the same thing fifty
	 * times. It is still a real predicate, which is what makes this read brand-isolated rather
	 * than isolated by convention — and it is what makes V19's
	 * {@code (brand_id, expert_id, outcome)} index apply.
	 *
	 * @return rows of {@code [expertId, outcome, count]}
	 */
	@Query("""
			select o.expertId, o.outcome, count(o)
			from ExpertCaseOffer o
			where o.brandId = :brandId and o.expertId in :expertIds
			group by o.expertId, o.outcome
			""")
	List<Object[]> countOutcomesPerExpert(@Param("brandId") UUID brandId,
			@Param("expertIds") Collection<UUID> expertIds);
}
