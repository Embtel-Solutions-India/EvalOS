package com.ie.evalos.web;

import java.util.List;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.ScheduledJob;
import com.ie.evalos.job.JobAdminService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sweep panel: what ran, when, and a button to run it again.
 *
 * <p><strong>GM-only, and for a different reason than the GHL screens.</strong> Those are gated
 * because the location cannot be attributed to a brand. This one is gated because the ledger is
 * cross-brand by nature — a sweep runs over every brand's cases at once — so there is no scoped
 * view of it to give a Brand Manager, and a partial one would be a lie about what ran.
 *
 * <p>Thin (invariant 12): every decision, including which sweeps exist, is
 * {@link JobAdminService}'s.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobAdminController {

	/** Whether the run actually started, which is not the same as whether the request worked. */
	public record RunResult(String jobType, boolean started, String message) {
	}

	private final JobAdminService jobs;

	JobAdminController(JobAdminService jobs) {
		this.jobs = jobs;
	}

	@GetMapping("/runs")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<List<ScheduledJob>> runs() {
		return ApiResponse.ok(jobs.recentRuns());
	}

	@GetMapping("/sweeps")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<List<JobAdminService.SweepStatus>> sweeps() {
		return ApiResponse.ok(jobs.statuses());
	}

	/**
	 * Runs one sweep now, synchronously.
	 *
	 * <p>200 with {@code started: false} rather than 409 when the lock is held: nothing went
	 * wrong, the sweep is simply already doing the thing that was asked for, and a GM should
	 * read that as reassurance rather than as an error to retry.
	 */
	@PostMapping("/{jobType}/run")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<RunResult> run(@PathVariable String jobType) {
		boolean started = jobs.runNow(jobType);
		return ApiResponse.ok(new RunResult(jobType, started,
				started ? "Sweep finished — see the run ledger." : "Already running; nothing was started."));
	}
}
