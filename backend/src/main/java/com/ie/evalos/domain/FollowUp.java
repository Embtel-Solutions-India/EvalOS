package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A follow-up EvalOS set, mirrored from GHL's own response.
 *
 * <p><strong>A follow-up is a GHL task on the deal's contact.</strong> GHL owns it; this is a
 * copy, held so the sales desk can see the reminders it sets. See `V48__follow_up.sql` for the
 * three read routes that were compared and why a mirror won — it is the same argument
 * {@link Meeting} makes, from the same evidence.
 *
 * <p><strong>`completed` is a convenience, not an authority.</strong> The desk's own "mark done"
 * writes to GHL first and here second, so the common case is right immediately. A task completed
 * in GHL itself is not reflected until the sync reconciles, which {@code syncedAt} bounds.
 */
@Entity
@Table(name = "follow_up")
public class FollowUp extends ScopedEntity {

	@Column(name = "ghl_task_id", nullable = false, updatable = false)
	private String ghlTaskId;

	@Column(name = "ghl_opportunity_id", nullable = false, updatable = false)
	private String ghlOpportunityId;

	@Column(name = "ghl_contact_id", nullable = false, updatable = false)
	private String ghlContactId;

	@Column(name = "ghl_pipeline_id", nullable = false, updatable = false)
	private String ghlPipelineId;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "body")
	private String body;

	@Column(name = "due_at", nullable = false)
	private Instant dueAt;

	@Column(name = "completed", nullable = false)
	private boolean completed;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "set_by", nullable = false, updatable = false)
	private UUID setBy;

	@Column(name = "synced_at", nullable = false)
	private Instant syncedAt;

	protected FollowUp() {
		// for JPA
	}

	public FollowUp(UUID brandId, String ghlTaskId, String ghlOpportunityId, String ghlContactId,
			String ghlPipelineId, String title, String body, Instant dueAt, UUID setBy) {
		super(brandId);
		this.ghlTaskId = ghlTaskId;
		this.ghlOpportunityId = ghlOpportunityId;
		this.ghlContactId = ghlContactId;
		this.ghlPipelineId = ghlPipelineId;
		this.title = title;
		this.body = body;
		this.dueAt = dueAt;
		this.completed = false;
		this.syncedAt = Instant.now();
		this.setBy = setBy;
	}

	/**
	 * Marks it done, after GHL has accepted.
	 *
	 * <p>Named for the event rather than exposed as two setters, so {@code completed} and
	 * {@code completed_at} cannot drift apart — the database CHECK refuses that pair anyway, and
	 * this is what stops a caller discovering it at the insert.
	 *
	 * <p>Idempotent: completing an already-completed follow-up keeps the original time. The second
	 * caller is a double-click, and moving the timestamp would rewrite when the work was actually
	 * done.
	 */
	/**
	 * GHL's version of this task — <strong>read-back, Unit 47b</strong>.
	 *
	 * <p>Until now this row was written when EvalOS created the task and never again, so a task
	 * completed <em>in GHL</em> still read as open on the desk. `47` §4 accepted that divergence on
	 * the grounds that tasks could only be listed per contact; they ride on the opportunity search
	 * EvalOS already makes, so the divergence was never necessary.
	 *
	 * <p><strong>GHL wins on every field here.</strong> A task is GHL's object — EvalOS creates one
	 * and never edits it — so there is no conflict to resolve and no 45e question to ask.
	 */
	public void syncFromGhl(String title, String body, Instant dueAt, boolean completed) {
		this.title = title == null || title.isBlank() ? this.title : title;
		this.body = body;
		this.dueAt = dueAt == null ? this.dueAt : dueAt;
		if (completed && !this.completed) {
			complete();
		}
		this.syncedAt = Instant.now();
	}

	public void complete() {
		if (!completed) {
			this.completed = true;
			this.completedAt = Instant.now();
		}
		this.syncedAt = Instant.now();
	}

	public String getGhlTaskId() {
		return ghlTaskId;
	}

	public String getGhlOpportunityId() {
		return ghlOpportunityId;
	}

	public String getGhlContactId() {
		return ghlContactId;
	}

	public String getGhlPipelineId() {
		return ghlPipelineId;
	}

	public String getTitle() {
		return title;
	}

	public String getBody() {
		return body;
	}

	public Instant getDueAt() {
		return dueAt;
	}

	public boolean isCompleted() {
		return completed;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public UUID getSetBy() {
		return setBy;
	}

	public Instant getSyncedAt() {
		return syncedAt;
	}
}
