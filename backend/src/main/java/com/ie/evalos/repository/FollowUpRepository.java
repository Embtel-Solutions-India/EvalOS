package com.ie.evalos.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.FollowUp;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Follow-ups EvalOS set, scoped the way the desk that set them is scoped.
 *
 * <p><strong>The scope axis is the pipeline, not the person who set it.</strong> A follow-up
 * belongs to the desk that owns the deal: cover during leave is normal, and scoping to
 * {@code set_by} would hide a colleague's reminder on your own pipeline the week it matters most.
 * Same reasoning as {@code MeetingRepository} and {@code OpportunityNoteRepository}; {@code set_by}
 * is for the trail, not for access.
 */
public interface FollowUpRepository
		extends JpaRepository<FollowUp, UUID>, JpaSpecificationExecutor<FollowUp> {

	/** The scope declaration {@code DomainInvariantsTest} checks against the entity's fields. */
	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandAndPipeline("brandId", "ghlPipelineId");

	/**
	 * A desk's open follow-ups, soonest first.
	 *
	 * <p>Ascending and open-only because this is a work queue: the overdue one is the point, and a
	 * list led by completed items buries it.
	 */
	List<FollowUp> findByBrandIdAndGhlPipelineIdAndCompletedFalseAndDueAtBeforeOrderByDueAtAsc(
			UUID brandId, String ghlPipelineId, Instant before);

	/** One deal's follow-ups, newest first — for the drawer on a deal card. */
	List<FollowUp> findByBrandIdAndGhlOpportunityIdOrderByDueAtDesc(UUID brandId,
			String ghlOpportunityId);

	/**
	 * The mirrored row for a GHL task, so completing updates rather than inserts.
	 *
	 * <p>Brand-scoped because {@code uq_follow_up_per_brand_task} is, and because the task id
	 * arrives from a request — a finder taking it alone would be one mistyped caller away from
	 * another brand's row.
	 */
	Optional<FollowUp> findByBrandIdAndGhlTaskId(UUID brandId, String ghlTaskId);
}
