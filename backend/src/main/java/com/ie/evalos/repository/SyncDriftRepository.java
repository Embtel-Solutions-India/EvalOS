package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.SyncDrift;
import com.ie.evalos.domain.SyncEntity;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Divergences between EvalOS and GHL (Unit 45b). */
public interface SyncDriftRepository extends JpaRepository<SyncDrift, UUID>, JpaSpecificationExecutor<SyncDrift> {

	/**
	 * Brand only — a drift row has no team, no assignee and no pipeline.
	 *
	 * <p>It could have had one: an opportunity's drift belongs to whichever desk works that
	 * pipeline. It deliberately does not, because <strong>this screen is the GM's</strong>. A
	 * salesperson shown "GHL and EvalOS disagree about your deal's stage" has no action available
	 * and no way to judge whether it matters; the person who decides what to do about a broken sync
	 * is the one who can also look at both systems.
	 */
	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	/** What is wrong right now, newest sighting first. */
	List<SyncDrift> findByBrandIdAndResolvedAtIsNullOrderByLastSeenAtDesc(UUID brandId);

	/**
	 * The open row for one exact disagreement, so tonight's audit updates rather than duplicates.
	 *
	 * <p>Mirrors {@code uq_sync_drift_open}. The nullable halves are compared through the finder's
	 * own {@code Is Null} handling by the caller, which keeps the query derivable rather than
	 * hand-written.
	 */
	Optional<SyncDrift> findByBrandIdAndEntityTypeAndGhlIdAndFieldAndResolvedAtIsNull(UUID brandId,
			SyncEntity entityType, String ghlId, String field);

	Optional<SyncDrift> findByBrandIdAndEntityTypeAndGhlIdAndFieldIsNullAndResolvedAtIsNull(UUID brandId,
			SyncEntity entityType, String ghlId);

	/** Every open row for one entity type — the audit's "what did I say last night" read. */
	List<SyncDrift> findByBrandIdAndEntityTypeAndResolvedAtIsNull(UUID brandId, SyncEntity entityType);

	long countByBrandIdAndResolvedAtIsNull(UUID brandId);
}
