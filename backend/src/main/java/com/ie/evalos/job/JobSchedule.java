package com.ie.evalos.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns the sweeps' clocks on — and the one switch that turns them off.
 *
 * <p><strong>{@code @EnableScheduling} lives here rather than on the application class</strong>
 * so it can carry a condition. Without the condition every test that starts a context also
 * starts four sweeps against the test database, and a sweep that fires mid-assertion is the
 * kind of flake nobody reproduces. {@code evalos.jobs.enabled=false} in the integration suite
 * is what makes those tests deterministic.
 *
 * <p><strong>The flag defaults to on.</strong> A deployment that forgot to set it should run
 * its jobs — the failure mode of the opposite default is a production instance that quietly
 * chases nobody and escalates nothing, and nothing about it looks broken.
 *
 * <p>Note what the flag does <em>not</em> reach: {@code run()} on each sweep is a plain method,
 * so the GM's "Run now" works whether or not the schedule is enabled. That is deliberate — the
 * switch stops the clock, not the job.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "evalos.jobs.enabled", havingValue = "true", matchIfMissing = true)
class JobSchedule {
}
