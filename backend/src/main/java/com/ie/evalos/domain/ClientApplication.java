package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A client's request for a service, from choosing one to Sales reading it (Unit 43).
 *
 * <p><strong>This is not a case and must never become one.</strong> A case is born only of a won
 * opportunity arriving on the webhook — invariant 8, Handoff A — and nothing in this unit creates
 * one. What this row does is carry the questionnaire between the client filling it in and a
 * salesperson pricing it, which is a stretch of the client's journey EvalOS previously had no
 * record of at all.
 *
 * <p><strong>{@code serviceName} is denormalised on purpose.</strong> It is the name as it stood
 * when the client chose it: the name the GHL opportunity carries and the one Sales reads weeks
 * later. Resolving it from the catalog on read would let an edit to the catalog silently change
 * what a client is recorded as having asked for.
 *
 * <p><strong>{@code answers} is the whole questionnaire as one JSON array of
 * {@code \{id, label, value\}}</strong> — the label included, because the question text lives in
 * the client portal's catalog and the staff app cannot import it. See
 * {@code V49__client_application.sql}.
 */
@Entity
@Table(name = "client_application")
public class ClientApplication extends ScopedEntity {

	/** Two values, one transition, and no transition table for it. */
	public enum Status {

		/** Being filled in. At most one per client — `client_application_one_draft_idx`. */
		DRAFT,

		/** Handed to Sales. The answers stop changing; the opportunity does not move. */
		SUBMITTED
	}

	@Column(name = "client_account_id", nullable = false, updatable = false)
	private UUID clientAccountId;

	@Column(name = "service_id", nullable = false, updatable = false)
	private String serviceId;

	@Column(name = "service_name", nullable = false, updatable = false)
	private String serviceName;

	@Column(name = "purpose")
	private String purpose;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private Status status = Status.DRAFT;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "answers", nullable = false)
	private String answers = "[]";

	/**
	 * Nullable for one window only: a GHL outage between this row being written and the
	 * opportunity being created. The client is not stranded mid-funnel for it; the next save
	 * tries again.
	 */
	@Column(name = "ghl_opportunity_id")
	private String ghlOpportunityId;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Column(name = "submitted_at")
	private Instant submittedAt;

	protected ClientApplication() {
		// for JPA
	}

	public ClientApplication(UUID brandId, UUID clientAccountId, String serviceId, String serviceName,
			String purpose) {
		super(brandId);
		this.clientAccountId = clientAccountId;
		this.serviceId = serviceId;
		this.serviceName = serviceName;
		this.purpose = purpose;
	}

	/** Records the answers so far. Refused once submitted — see {@link #submit}. */
	public void saveAnswers(String answersJson, String purpose) {
		this.answers = answersJson;
		if (purpose != null && !purpose.isBlank()) {
			this.purpose = purpose;
		}
		this.updatedAt = Instant.now();
	}

	/** Links the GHL opportunity this application opened. Never mints one — invariant 7. */
	public void linkOpportunity(String ghlOpportunityId) {
		this.ghlOpportunityId = ghlOpportunityId;
		this.updatedAt = Instant.now();
	}

	/**
	 * Hands it to Sales.
	 *
	 * <p><strong>Nothing about the opportunity changes here.</strong> It was created when the
	 * client picked a service, because starting a request is the qualification signal (43 §6a),
	 * and where it sits in the pipeline is GHL's automation's business rather than EvalOS's. What
	 * changes is {@code status}, which is what the staff screen sorts on.
	 */
	public void submit() {
		this.status = Status.SUBMITTED;
		this.submittedAt = Instant.now();
		this.updatedAt = this.submittedAt;
	}

	public boolean isDraft() {
		return status == Status.DRAFT;
	}

	public UUID getClientAccountId() {
		return clientAccountId;
	}

	public String getServiceId() {
		return serviceId;
	}

	public String getServiceName() {
		return serviceName;
	}

	public String getPurpose() {
		return purpose;
	}

	public Status getStatus() {
		return status;
	}

	public String getAnswers() {
		return answers;
	}

	public String getGhlOpportunityId() {
		return ghlOpportunityId;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getSubmittedAt() {
		return submittedAt;
	}
}
