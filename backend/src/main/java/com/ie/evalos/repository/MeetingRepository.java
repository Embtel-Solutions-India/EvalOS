package com.ie.evalos.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Meeting;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Meetings EvalOS booked, scoped the way the desk that booked them is scoped.
 *
 * <p><strong>The scope axis is the pipeline, not the assignee.</strong> A meeting belongs to the
 * desk that owns the deal rather than to the person who happened to book it: cover during leave
 * is normal, and scoping to {@code booked_by} would hide a colleague's meeting on your own
 * pipeline. That is the same reasoning {@code OpportunityNoteRepository} applies to notes, and it
 * is why {@code booked_by} is recorded for the audit trail and is not an access key.
 *
 * <p>{@code Tier.PIPELINE} callers therefore see exactly their own pipeline's meetings, and every
 * other tier falls through to the brand — which is `ScopePredicate`'s existing behaviour and needs
 * nothing new here.
 */
public interface MeetingRepository
		extends JpaRepository<Meeting, UUID>, JpaSpecificationExecutor<Meeting> {

	/**
	 * The scope declaration {@code DomainInvariantsTest} checks against the entity's real fields.
	 *
	 * <p>No team and no assignee axis: a meeting has neither column, and declaring one that the
	 * schema does not have is the silent widening that test exists to catch.
	 */
	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandAndPipeline("brandId", "ghlPipelineId");

	/**
	 * One deal's meetings, newest first.
	 *
	 * <p><strong>Scoped by brand in the signature, not by a comment.</strong> The opportunity id
	 * arrives from a request, so a finder that took it alone would be one mistyped caller away
	 * from reading another brand's row — the trap five other finders in this package still carry
	 * as javadoc rather than as a parameter.
	 */
	List<Meeting> findByBrandIdAndGhlOpportunityIdOrderByStartsAtDesc(UUID brandId,
			String ghlOpportunityId);

	/**
	 * A desk's meetings inside a window, in the order they will happen.
	 *
	 * <p>Ascending because this is a diary: the next meeting is the one that matters, and a
	 * descending list would put next month above this afternoon.
	 */
	List<Meeting> findByBrandIdAndGhlPipelineIdAndStartsAtBetweenOrderByStartsAtAsc(UUID brandId,
			String ghlPipelineId, Instant from, Instant to);

	/**
	 * The mirrored row for a GHL appointment, so a reschedule updates rather than inserts.
	 *
	 * <p>Brand-scoped because {@code uq_meeting_per_brand_appointment} is: GHL ids are unique
	 * within a location, and two brands will hold two locations the day Unit 25 lands.
	 */
	Optional<Meeting> findByBrandIdAndGhlAppointmentId(UUID brandId, String ghlAppointmentId);
}
