package com.ie.evalos.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.ScheduledJob;
import com.ie.evalos.repository.ScheduledJobRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a GM can see and do about the sweeps.
 *
 * <p><strong>The reason this screen exists at all:</strong> a sweep that stops has no symptom.
 * Nobody is chased, nothing escalates, no error appears — the system simply goes quiet, and the
 * first sign is a client asking why nobody followed up. The ledger turns that silence into a
 * row, and this service turns the rows into "when did each sweep last work".
 *
 * <p><strong>"Run now" goes through the lock, not around it.</strong> It calls the same
 * {@code run()} the schedule calls, which takes the same advisory lock — so a GM pressing the
 * button while the sweep is mid-run gets "already running" rather than a second concurrent
 * pass. Any faster path would be a path with different concurrency behaviour than the one that
 * runs 48 times a day, which is the version nobody tests.
 *
 * <p>Runs synchronously, inside the request. The sweeps are minutes apart and touch tens of
 * rows, so the bound is real (invariant 6); the day one is not, it needs a queue and not a
 * thread.
 */
@Service
public class JobAdminService {

	/**
	 * One row of the panel: a sweep and how its last run went.
	 *
	 * @param stale the reason this sweep looks stopped, or null if it looks healthy. A string
	 *              rather than a boolean because "never run" and "last ran nine hours ago" are
	 *              different problems and the panel should say which
	 */
	public record SweepStatus(String jobType, boolean idle, Instant lastStartedAt,
			ScheduledJob.Status lastStatus, Integer lastItemsSeen, Integer lastItemsActed,
			String lastError, Long lastDurationSeconds, String stale) {
	}

	private final Map<String, Sweep> sweeps;
	private final ScheduledJobRepository runs;
	private final SweepRunner runner;
	private final JobProperties schedule;

	JobAdminService(List<Sweep> sweeps, ScheduledJobRepository runs, SweepRunner runner,
			JobProperties schedule) {
		// Keyed once at startup. A duplicate job type would silently overwrite here, so it
		// throws instead: two sweeps sharing a lock key would take turns skipping each other.
		this.sweeps = sweeps.stream().collect(Collectors.toUnmodifiableMap(Sweep::jobType, Function.identity()));
		this.runs = runs;
		this.runner = runner;
		this.schedule = schedule;
	}

	/** The most recent runs across every sweep, newest first. */
	@Transactional(readOnly = true)
	public List<ScheduledJob> recentRuns() {
		return runs.findTop50ByOrderByStartedAtDesc();
	}

	/** One line per sweep, whether or not it has ever run. */
	@Transactional(readOnly = true)
	public List<SweepStatus> statuses() {
		return sweeps.keySet().stream().sorted()
				.map(this::statusOf)
				.toList();
	}

	private SweepStatus statusOf(String jobType) {
		ScheduledJob last = runs.findTop1ByJobTypeOrderByStartedAtDesc(jobType).stream()
				.findFirst().orElse(null);
		boolean idle = runner.isFree(jobType);
		if (last == null) {
			// Never run. Distinct from "ran and did nothing", and the difference matters: the
			// first means the schedule is not turning, the second means there was no work.
			return new SweepStatus(jobType, idle, null, null, null, null, null, null,
					schedule.enabled() ? "Has never run." : null);
		}
		Long seconds = last.getFinishedAt() == null ? null
				: Duration.between(last.getStartedAt(), last.getFinishedAt()).toSeconds();
		return new SweepStatus(jobType, idle, last.getStartedAt(), last.getStatus(), last.getItemsSeen(),
				last.getItemsActed(), last.getError(), seconds, staleness(jobType, last.getStartedAt()));
	}

	/**
	 * Whether this sweep has gone quiet, said in words.
	 *
	 * <p><strong>The one thing this whole screen is for.</strong> A scheduler that has stopped
	 * and a scheduler with nothing to do produce identical ledgers — no rows, no errors — so
	 * without this the panel would look calm during the exact outage it exists to surface.
	 *
	 * <p>Silent when the schedule is switched off: "stale" would then be the correct and
	 * expected state, and an alarm that is right every time it fires in a configuration
	 * somebody chose is an alarm people learn to close.
	 */
	private String staleness(String jobType, Instant lastStartedAt) {
		if (!schedule.enabled()) {
			return null;
		}
		Duration budget = schedule.silenceBudgetFor(jobType);
		if (budget == null || Duration.between(lastStartedAt, Instant.now()).compareTo(budget) < 0) {
			return null;
		}
		return "Last ran more than " + budget.toMinutes() + " minutes ago — the schedule may have stopped.";
	}

	/**
	 * Runs one sweep now.
	 *
	 * <p>Not {@code @Transactional}: the sweep opens its own transaction per item, and wrapping
	 * it in the request's would defeat that and hold one connection for the whole pass.
	 *
	 * @return false if another instance — or another tab — already holds the lock
	 */
	public boolean runNow(String jobType) {
		Sweep sweep = sweeps.get(jobType);
		if (sweep == null) {
			// Names the valid set: the caller is a GM pressing a button, and a bare 400 on a
			// typo'd path segment tells them nothing about what they could have pressed.
			throw new InvalidRequestException(
					"No such sweep: " + jobType + ". Known sweeps: " + sweeps.keySet().stream().sorted().toList());
		}
		return sweep.run();
	}
}
