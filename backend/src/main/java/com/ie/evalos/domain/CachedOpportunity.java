package com.ie.evalos.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A copy of one GHL opportunity, held only so a board paints inside the browser's timeout.
 *
 * <p><strong>Not a domain object. Not a source of truth. Droppable.</strong> Every field here is
 * one GHL owns; truncating this table costs a refill and nothing else. No write path reads it to
 * decide anything, and no screen shows a value that exists only here. If a field ever appears
 * that GHL has no equivalent for, the truth model in
 * {@code context/specs/00b-ghl-operational-programme.md} §1.3 is void and gets re-argued before
 * the column lands — because at that point EvalOS is a second CRM and two systems own one record.
 *
 * <p><strong>Why it exists</strong>, given that {@code architecture.md} invariant 2 warned against
 * exactly this: GHL allows 100 requests per 10 seconds per location, and reading a pipeline is a
 * cursor loop that cannot be parallelised. A pass-through board is not slow — it does not load.
 *
 * <p><strong>It is replaced from GHL's response, never from a request body.</strong> That is the
 * rule that keeps a cache from becoming a second source of truth: optimistic local state
 * diverges, and nothing here is authoritative enough to win the argument when it does.
 *
 * <p>There is deliberately no {@code brand_id} — see {@code V40}'s comment. The scope is the
 * pipeline id, taken from the caller's own principal.
 */
@Entity
@Table(name = "ghl_opportunity_cache")
public class CachedOpportunity {

	/** GHL's id. EvalOS mints nothing here (invariant 7). */
	@Id
	@Column(name = "ghl_opportunity_id", nullable = false, updatable = false)
	private String ghlOpportunityId;

	/** The access key: every read of this table is keyed on it. */
	@Column(name = "ghl_pipeline_id", nullable = false)
	private String ghlPipelineId;

	/** The client, and the canonical external identity everywhere (invariant 7). */
	@Column(name = "ghl_contact_id", nullable = false)
	private String ghlContactId;

	@Column(name = "stage_id", nullable = false)
	private String stageId;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "name")
	private String name;

	@Column(name = "amount")
	private BigDecimal amount;

	/** GHL's own last-modified, kept so a future delta poll has something to poll on. */
	@Column(name = "updated_in_ghl_at")
	private Instant updatedInGhlAt;

	/** When EvalOS last read this row from GHL. The TTL is measured from here. */
	@Column(name = "fetched_at", nullable = false)
	private Instant fetchedAt;

	protected CachedOpportunity() {
		// for JPA
	}

	public CachedOpportunity(String ghlOpportunityId, String ghlPipelineId, String ghlContactId, String stageId,
			String status, String name, BigDecimal amount, Instant updatedInGhlAt, Instant fetchedAt) {
		this.ghlOpportunityId = ghlOpportunityId;
		this.ghlPipelineId = ghlPipelineId;
		this.ghlContactId = ghlContactId;
		this.stageId = stageId;
		this.status = status;
		this.name = name;
		this.amount = amount;
		this.updatedInGhlAt = updatedInGhlAt;
		this.fetchedAt = fetchedAt;
	}

	public String getGhlOpportunityId() {
		return ghlOpportunityId;
	}

	public String getGhlPipelineId() {
		return ghlPipelineId;
	}

	public String getGhlContactId() {
		return ghlContactId;
	}

	public String getStageId() {
		return stageId;
	}

	public String getStatus() {
		return status;
	}

	public String getName() {
		return name;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public Instant getUpdatedInGhlAt() {
		return updatedInGhlAt;
	}

	public Instant getFetchedAt() {
		return fetchedAt;
	}
}
