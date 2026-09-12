package com.ie.evalos.job;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The sweep schedule, read as data rather than only as {@code @Scheduled} placeholders.
 *
 * <p><strong>This exists so the panel's "this sweep has stopped" warning uses the same numbers
 * the scheduler does.</strong> The alternative is a second list of intervals for the check to
 * compare against, and when the two drift the panel does not fail — it quietly stops warning,
 * which is precisely the failure the panel was built to catch.
 *
 * @param enabled            whether the clocks run. Off in the integration suite; see
 *                           {@link JobSchedule}
 * @param intervals          one entry per {@code JOB_TYPE}, and the same keys the
 *                           {@code @Scheduled} placeholders name
 * @param staleAfterIntervals how many missed intervals before a sweep is called stopped
 */
@ConfigurationProperties("evalos.jobs")
public record JobProperties(boolean enabled, Map<String, Duration> intervals, int staleAfterIntervals) {

	/**
	 * How long a sweep may go unheard from before the panel says it has stopped.
	 *
	 * @return the window, or null if this job type has no configured interval — which
	 *         {@code SweepRegistrationTest} makes impossible for a real sweep
	 */
	public Duration silenceBudgetFor(String jobType) {
		Duration interval = intervals == null ? null : intervals.get(jobType);
		return interval == null ? null : interval.multipliedBy(staleAfterIntervals);
	}
}
