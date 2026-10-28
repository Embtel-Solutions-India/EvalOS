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

	/** One day's runs in the viewer's zone: {@code day} is ISO {@code yyyy-MM-dd}. */
	interface DayCount {
		String getDay();

		long getOk();

		long getFailed();
	}

	/**
	 * Runs per local day since {@code since}, in {@code zone} (2026-10-28). Counted here rather than drawn
	 * from {@link #findTop50ByOrderByStartedAtDesc}: the sweeps run every minute or two, so fifty runs are
	 * the last half hour and a week's chart showed only today. A run still RUNNING counts as not failed.
	 */
	@org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
			SELECT to_char(started_at AT TIME ZONE :zone, 'YYYY-MM-DD') AS day,
			       count(*) FILTER (WHERE status <> 'FAILED') AS ok,
			       count(*) FILTER (WHERE status = 'FAILED') AS failed
			  FROM scheduled_job
			 WHERE started_at >= :since
			 GROUP BY 1
			 ORDER BY 1
			""")
	List<DayCount> countByDaySince(@org.springframework.data.repository.query.Param("since") java.time.Instant since,
			@org.springframework.data.repository.query.Param("zone") String zone);

	/** The latest run of one sweep, for "when did this last work". */
	List<ScheduledJob> findTop1ByJobTypeOrderByStartedAtDesc(String jobType);
}
