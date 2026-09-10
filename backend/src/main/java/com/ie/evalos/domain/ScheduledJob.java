package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One run of one sweep — the ledger row, not a queued timer.
 *
 * <p><strong>This table records runs, not intentions.</strong> There is no row per future
 * reminder: a sweeper asks "which cases are overdue right now" and is correct on its first run
 * after any outage, where a queue of stale timers either fires a burst or drops them. And every
 * sweep's idempotency already lives in the data it reads — the {@code CHASED} audit rows, the
 * notification rows, the stored {@code sla_status} — so a job row claiming "the chase fired"
 * would be a second record of a fact the system already holds.
 *
 * <p>What this earns instead is observability: which sweep ran, how long it took, how many rows
 * it touched, and what failed.
 *
 * <p><strong>The row is written before the sweep runs, not after.</strong> A row stuck in
 * {@code RUNNING} is exactly how a JVM that died mid-sweep announces itself — worth seeing.
 */
@Entity
@Table(name = "scheduled_job")
public class ScheduledJob {

	public enum Status {
		RUNNING, OK, FAILED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "job_type", nullable = false, updatable = false)
	private String jobType;

	@Column(name = "started_at", nullable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "items_seen", nullable = false)
	private int itemsSeen;

	@Column(name = "items_acted", nullable = false)
	private int itemsActed;

	@Column(name = "error")
	private String error;

	protected ScheduledJob() {
		// for JPA
	}

	public ScheduledJob(String jobType, Instant startedAt) {
		this.jobType = jobType;
		this.startedAt = startedAt;
		this.status = Status.RUNNING.name();
	}

	/** Closes the row. Called from a finally, so it must not itself be able to throw. */
	public void finish(Status outcome, int seen, int acted, String error) {
		this.status = outcome.name();
		this.itemsSeen = seen;
		this.itemsActed = acted;
		// Truncated because a stack trace in a ledger column is a stack trace nobody reads and a
		// row nobody can scan past. The full one is in the log, where it belongs.
		this.error = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
		this.finishedAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public String getJobType() {
		return jobType;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public Status getStatus() {
		return Status.valueOf(status);
	}

	public int getItemsSeen() {
		return itemsSeen;
	}

	public int getItemsActed() {
		return itemsActed;
	}

	public String getError() {
		return error;
	}
}
