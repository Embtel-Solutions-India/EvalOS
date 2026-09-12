package com.ie.evalos.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.ScheduledJob;
import com.ie.evalos.repository.ScheduledJobRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The registry, which is the only logic here — everything else is a read.
 *
 * <p>The point of building it from {@code List<Sweep>} is that a fifth sweep registers itself by
 * existing. {@link #aSweepIsAddressableByItsOwnJobType} is what pins that; the rest of the class
 * makes sure a typo in a URL says something useful rather than 500ing on a null.
 */
class JobAdminServiceTest {

	private static final JobProperties SCHEDULE =
			new JobProperties(true, Map.of("DOC_CHASE", Duration.ofMinutes(30)), 4);

	private final ScheduledJobRepository runs = mock(ScheduledJobRepository.class);
	private final SweepRunner runner = mock(SweepRunner.class);

	private static Sweep sweepNamed(String jobType, boolean result) {
		Sweep sweep = mock(Sweep.class);
		when(sweep.jobType()).thenReturn(jobType);
		when(sweep.run()).thenReturn(result);
		return sweep;
	}

	@Test
	void aSweepIsAddressableByItsOwnJobType() {
		Sweep chase = sweepNamed("DOC_CHASE", true);
		JobAdminService jobs = new JobAdminService(List.of(chase, sweepNamed("STAGE_SLA", true)), runs, runner, SCHEDULE);

		assertThat(jobs.runNow("DOC_CHASE")).isTrue();
	}

	@Test
	void aHeldLockIsReportedRatherThanThrown() {
		JobAdminService jobs = new JobAdminService(List.of(sweepNamed("DOC_CHASE", false)), runs, runner, SCHEDULE);

		// Nothing went wrong: the sweep is already doing the thing that was asked for.
		assertThat(jobs.runNow("DOC_CHASE")).isFalse();
	}

	@Test
	void anUnknownSweepNamesTheOnesThatExist() {
		JobAdminService jobs = new JobAdminService(
				List.of(sweepNamed("DOC_CHASE", true), sweepNamed("STAGE_SLA", true)), runs, runner, SCHEDULE);

		assertThatThrownBy(() -> jobs.runNow("DOC_CHASe"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("DOC_CHASE")
				.hasMessageContaining("STAGE_SLA");
	}

	@Test
	void aSweepThatHasNeverRunStillGetsALine() {
		when(runs.findTop1ByJobTypeOrderByStartedAtDesc("DOC_CHASE")).thenReturn(List.of());
		when(runner.isFree("DOC_CHASE")).thenReturn(true);
		JobAdminService jobs = new JobAdminService(List.of(sweepNamed("DOC_CHASE", true)), runs, runner, SCHEDULE);

		// "Never run" is the alarming state and it must be shown, not filtered out for having
		// no row: it means the schedule is not turning at all.
		assertThat(jobs.statuses()).singleElement()
				.satisfies((status) -> {
					assertThat(status.jobType()).isEqualTo("DOC_CHASE");
					assertThat(status.lastStartedAt()).isNull();
					assertThat(status.idle()).isTrue();
					assertThat(status.stale()).isEqualTo("Has never run.");
				});
	}

	@Test
	void aSweepThatHasNotRunInFourIntervalsIsCalledStopped() {
		givenLastRun(Instant.now().minus(Duration.ofHours(3)));
		JobAdminService jobs = new JobAdminService(List.of(sweepNamed("DOC_CHASE", true)), runs, runner,
				SCHEDULE);

		assertThat(jobs.statuses()).singleElement()
				.satisfies((status) -> assertThat(status.stale()).contains("may have stopped"));
	}

	@Test
	void aSweepThatRanRecentlyIsNotFlagged() {
		givenLastRun(Instant.now().minus(Duration.ofMinutes(20)));
		JobAdminService jobs = new JobAdminService(List.of(sweepNamed("DOC_CHASE", true)), runs, runner,
				SCHEDULE);

		assertThat(jobs.statuses()).singleElement()
				.satisfies((status) -> assertThat(status.stale()).isNull());
	}

	/**
	 * With the clocks deliberately off, "stale" is the correct state for every sweep — and an
	 * alarm that is right every time it fires in a configuration somebody chose is an alarm
	 * people learn to close.
	 */
	@Test
	void nothingIsStaleWhenTheScheduleIsSwitchedOff() {
		givenLastRun(Instant.now().minus(Duration.ofDays(9)));
		JobAdminService jobs = new JobAdminService(List.of(sweepNamed("DOC_CHASE", true)), runs, runner,
				new JobProperties(false, SCHEDULE.intervals(), 4));

		assertThat(jobs.statuses()).singleElement()
				.satisfies((status) -> assertThat(status.stale()).isNull());
	}

	private void givenLastRun(Instant startedAt) {
		ScheduledJob run = new ScheduledJob("DOC_CHASE", startedAt);
		when(runs.findTop1ByJobTypeOrderByStartedAtDesc("DOC_CHASE")).thenReturn(List.of(run));
	}
}
