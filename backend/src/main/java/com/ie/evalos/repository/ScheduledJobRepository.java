package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.ScheduledJob;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The sweep run ledger.
 *
 * <p><strong>Not a {@code ScopedRepository}, and it is the one place that is right.</strong> A
 * sweep has no authenticated caller to scope against — the same situation the inbound gateway is
 * in — and a run spans brands by nature, so there is no {@code brand_id} on the table to filter
 * by. The reads here are GM-only at the route, which is where cross-brand infrastructure is
 * gated everywhere else in this codebase.
 */
public interface ScheduledJobRepository extends JpaRepository<ScheduledJob, UUID> {

	/** The most recent runs, newest first — what the admin panel draws. */
	List<ScheduledJob> findTop50ByOrderByStartedAtDesc();

	/** The latest run of one sweep, for "when did this last work". */
	List<ScheduledJob> findTop1ByJobTypeOrderByStartedAtDesc(String jobType);
}
