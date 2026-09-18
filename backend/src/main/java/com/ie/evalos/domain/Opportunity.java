package com.ie.evalos.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One GHL opportunity, held as an EvalOS row — Unit 44, slice D.
 *
 * <p><strong>This replaces {@link CachedOpportunity}, and the difference is not the columns.</strong>
 * That class is refilled by delete-all-then-insert-all per pipeline, so every row's identity is
 * destroyed on every board refresh — {@code 00d} §6.5's fifth and deepest reason it cannot be
 * salvaged: nothing can hold a foreign key into it, and any row carrying local state dies. The
 * write model there is "truth lives elsewhere, replace everything"; here it is the opposite.
 *
 * <p><strong>Two names, and this is the table that earns them</strong> ({@code 00c} §2a). A
 * portal-born opportunity exists <em>before GHL has seen it</em>: the client picks a service, EvalOS
 * opens the row, and GHL is called. With GHL's id as the primary key that row cannot exist, and
 * when GHL later assigns one the row's identity would change, taking every foreign key with it. So
 * {@link #getId()} is stable from creation and {@link #getGhlId()} is null until GHL answers.
 *
 * <p><strong>{@code id} is also the correlation key</strong> ({@code 00d} §6.1). It is written into
 * a GHL custom field on create so a retry after a timeout can ask "did my create land?" rather than
 * guess. At-least-once delivery over a non-idempotent create is exactly how one opportunity becomes
 * two, and no amount of outbox discipline fixes it without a key GHL will hand back.
 *
 * <p><strong>Per-field ownership, not "EvalOS wins"</strong> ({@code 00d} §6.2). {@code assignedTo}
 * is GHL's — a round-robin automation reassigning a deal is not a conflict to be undone, and
 * reverting it would break the one thing {@code 00b} keeps GHL for. The shared fields are name,
 * amount, stage and status. Unit 45 is where that policy becomes code; this class is where the
 * columns it needs live.
 */
@Entity
@Table(name = "opportunity")
public class Opportunity extends ScopedEntity {

	/** GHL's id, or null while this row exists only here. */
	@Column(name = "ghl_id")
	private String ghlId;

	@Column(name = "ghl_contact_id")
	private String ghlContactId;

	@Column(name = "pipeline_id", nullable = false)
	private UUID pipelineId;

	/**
	 * GHL's stage id, resolved against {@link PipelineStage#getGhlId()} rather than by a foreign
	 * key — see the migration's note. The two mirrors run on two schedules, and a sync that failed
	 * because a different sweep was behind would turn one late job into two broken screens.
	 */
	@Column(name = "ghl_stage_id")
	private String ghlStageId;

	@Column(name = "name")
	private String name;

	@Column(name = "amount")
	private BigDecimal amount;

	/** GHL's own word — open / won / lost / abandoned. Never an EvalOS vocabulary. */
	@Column(name = "status")
	private String status;

	@Column(name = "source")
	private String source;

	@Column(name = "ghl_assigned_to")
	private String ghlAssignedTo;

	@Column(name = "ghl_created_at")
	private Instant ghlCreatedAt;

	/**
	 * GHL's last-modified.
	 *
	 * <p><strong>Null is a conflict, never "EvalOS is newer"</strong> ({@code 00d} §6.2). It is
	 * GHL-supplied and nullable, so a policy that treated absence as staleness would degrade
	 * silently into "EvalOS always wins" — which is the blanket rule that section exists to reject.
	 */
	@Column(name = "ghl_updated_at")
	private Instant ghlUpdatedAt;

	@Column(name = "last_status_change_at")
	private Instant lastStatusChangeAt;

	@Column(name = "last_stage_change_at")
	private Instant lastStageChangeAt;

	@Column(name = "local_updated_at")
	private Instant localUpdatedAt;

	/**
	 * Which of the four shared fields the local edit is about — V63.
	 *
	 * <p><strong>{@link #localUpdatedAt} alone was not enough to push safely.</strong> It says an
	 * edit is outstanding but not which field it touched, and the outbox stores an id rather than a
	 * payload — so the drain read the whole row and sent all four, making a rename re-send the
	 * mirror's stage. The mirror's stage is up to one {@code MIRROR_DELTA} behind GHL, so a
	 * workflow's stage move inside that window was dragged backwards and then read back as truth.
	 *
	 * <p>Comma-joined {@link FieldOwnership} property names, which is the vocabulary
	 * {@code sync_drift.field} already speaks. Null is the resting state: no edit outstanding.
	 */
	@Column(name = "locally_edited_fields")
	private String locallyEditedFields;

	@Column(name = "synced_at")
	private Instant syncedAt;

	@Column(name = "missing_since")
	private Instant missingSince;

	/**
	 * GHL's custom field <strong>values</strong> for this deal, keyed by GHL field id — Unit 47b.
	 *
	 * <p><strong>Keyed by id, never by name.</strong> A field renamed in GHL keeps its id, and
	 * {@code ghl_custom_field} (V60) is what turns an id into a label for a human. Keying by name
	 * would make a rename lose data silently.
	 *
	 * <p><strong>This is what unblocks D46.</strong> A desk create carries custom field values, and
	 * the outbox stores an id and never a payload — so a queued create had nowhere to put them and
	 * creates stayed synchronous. With the values on the row, the row <em>is</em> the payload.
	 */
	@org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
	@Column(name = "custom_fields", nullable = false)
	private java.util.Map<String, String> customFields = new java.util.LinkedHashMap<>();

	protected Opportunity() {
		// for JPA
	}

	/** A row GHL already has. */
	public Opportunity(UUID brandId, String ghlId, UUID pipelineId) {
		super(brandId);
		this.ghlId = ghlId;
		this.pipelineId = pipelineId;
	}

	/**
	 * Everything GHL says about this deal, in one call, <strong>under per-field ownership</strong>
	 * (Unit 45e, {@link FieldOwnership}).
	 *
	 * <p>Deliberately one method rather than a setter per column: a partial update from a sync is a
	 * row that half-agrees with GHL, and the whole value of a mirror is that a comparison means
	 * something.
	 *
	 * <p><strong>GHL-owned fields are taken every time.</strong> The assignee and the pipeline are
	 * GHL's, and overwriting them with a local value would revert the automations {@code 00b} kept
	 * GHL for — a round-robin reassigning a deal is not a conflict to undo.
	 *
	 * <p><strong>The four shared fields are kept when EvalOS holds an edit GHL has not confirmed.</strong>
	 * That is what {@link #localUpdatedAt} means, and it is cleared the moment GHL's answer
	 * supersedes it (below) or GHL acknowledges the create ({@link #linkGhl}) — so a row cannot sit
	 * frozen on a stale local value for ever. The disagreement is not silent: the next audit opens
	 * a {@code sync_drift} row for each kept field, which is the "and reporting" half of §3.2.
	 *
	 * @param ghlUpdatedAt GHL's last-modified. <strong>Null is a conflict, never "EvalOS is
	 *                     newer"</strong> — see the field's own note. It only decides anything when
	 *                     EvalOS actually holds an unconfirmed edit; a row with none follows GHL
	 *                     whatever this is, or a location that stopped sending the field would
	 *                     freeze the whole mirror.
	 */
	public void syncFromGhl(String ghlContactId, UUID pipelineId, String ghlStageId, String name,
			BigDecimal amount, String status, String source, String ghlAssignedTo, Instant ghlCreatedAt,
			Instant ghlUpdatedAt, Instant lastStatusChangeAt, Instant lastStageChangeAt) {
		boolean evalosWins = evalosWins(ghlUpdatedAt);

		// GHL's, always.
		this.ghlContactId = ghlContactId;
		this.pipelineId = pipelineId;
		this.source = source;
		this.ghlAssignedTo = ghlAssignedTo;
		this.ghlCreatedAt = ghlCreatedAt;
		this.ghlUpdatedAt = ghlUpdatedAt;
		this.lastStatusChangeAt = lastStatusChangeAt;
		this.lastStageChangeAt = lastStageChangeAt;

		// Shared: kept only while EvalOS holds an edit GHL has not confirmed.
		if (!evalosWins) {
			this.ghlStageId = ghlStageId;
			this.name = name;
			this.amount = amount;
			this.status = status;
			// GHL's answer has superseded whatever EvalOS held, so there is no unconfirmed edit
			// left to defend. Without this a row whose `ghl_updated_at` comes back null would
			// defend its local values for ever, which is "EvalOS always wins" arriving by the
			// back door.
			this.localUpdatedAt = null;
			this.locallyEditedFields = null;
		}

		this.syncedAt = Instant.now();
		this.missingSince = null;
	}

	/**
	 * Whether EvalOS holds an edit GHL has not confirmed, so the shared fields stay.
	 *
	 * <p>Both halves matter. <strong>No local edit means GHL wins</strong>, whatever the
	 * timestamps say — the mirror's default is to follow GHL. <strong>A null
	 * {@code ghlUpdatedAt} with a local edit is a conflict</strong> and EvalOS keeps its value
	 * ({@code 00d} §6.2): the field is GHL-supplied and nullable, so reading absence as "GHL is
	 * newer" would quietly discard the edit.
	 */
	private boolean evalosWins(Instant incomingGhlUpdatedAt) {
		if (this.localUpdatedAt == null) {
			return false;
		}
		return incomingGhlUpdatedAt == null || this.localUpdatedAt.isAfter(incomingGhlUpdatedAt);
	}

	/**
	 * GHL answered a create. The row keeps its id and gains GHL's.
	 *
	 * <p><strong>The local edit is confirmed by this and stops being defended</strong> (45e): the
	 * values EvalOS opened the row with are the values GHL was just handed, so there is nothing
	 * left for the ownership check to protect. A row that kept {@code localUpdatedAt} here would
	 * out-rank GHL on its four shared fields for the rest of its life.
	 */
	public void linkGhl(String ghlId) {
		if (this.ghlId == null) {
			this.ghlId = ghlId;
			this.syncedAt = Instant.now();
			this.localUpdatedAt = null;
			this.locallyEditedFields = null;
		}
	}

	public void markMissing(Instant at) {
		if (this.missingSince == null) {
			this.missingSince = at;
		}
	}

	/** Stamped whenever EvalOS changes the row itself, for Unit 45's comparison. */
	public void touchedLocally() {
		this.localUpdatedAt = Instant.now();
	}

	/**
	 * A desk changed this deal — Unit 46.
	 *
	 * <p><strong>Exactly the four fields 45e calls shared, and that is the point rather than a
	 * coincidence.</strong> What a desk may edit locally is what EvalOS is allowed to win a
	 * conflict over; anything wider would be the mirror arguing with GHL about a field GHL owns.
	 * The assignee is absent for that reason: it is GHL's, and a desk that could set it here would
	 * revert the round-robin the automations run.
	 *
	 * <p><strong>Null means "leave it alone", not "clear it".</strong> Every caller sends one or two
	 * of the four — a rename, a re-price, a stage move, a close — and a record-shaped update that
	 * blanked the rest would turn a rename into data loss.
	 *
	 * <p>The stamp is what makes the edit survive until GHL has it: a sync arriving before the push
	 * lands finds {@code localUpdatedAt} set and keeps these four (45e).
	 */
	public void editedLocally(String name, BigDecimal amount, String ghlStageId, String status) {
		java.util.Set<String> edited = new java.util.LinkedHashSet<>(locallyEdited());
		if (name != null && !name.isBlank()) {
			this.name = name;
			edited.add(FieldOwnership.NAME);
		}
		if (amount != null) {
			this.amount = amount;
			edited.add(FieldOwnership.AMOUNT);
		}
		if (ghlStageId != null && !ghlStageId.isBlank()) {
			this.ghlStageId = ghlStageId;
			edited.add(FieldOwnership.STAGE);
		}
		if (status != null && !status.isBlank()) {
			this.status = status;
			edited.add(FieldOwnership.STATUS);
		}
		// **Unioned, not replaced**, because the outbox collapses: a rename and then a re-price
		// before the drain runs are two edits that must both travel on the one queued push.
		this.locallyEditedFields = edited.isEmpty() ? null : String.join(",", edited);
		touchedLocally();
	}

	/**
	 * The shared fields a desk edited and GHL has not confirmed — what a push may send.
	 *
	 * <p>Empty means "send nothing of the four": either the row has no outstanding edit, or it is
	 * an older row queued before V63 and the drain says so rather than guessing.
	 */
	public java.util.Set<String> locallyEdited() {
		if (locallyEditedFields == null || locallyEditedFields.isBlank()) {
			return java.util.Set.of();
		}
		return java.util.Set.of(locallyEditedFields.split(","));
	}

	/*
	 * There is deliberately no `pushedToGhl()` here.
	 *
	 * Retiring a confirmed edit looks like an entity method and cannot be one: the drain reads the
	 * row, spends a GHL round trip, and by the time it comes back the row may hold a newer edit.
	 * Merging the entity it read would lose that edit outright. It is a conditional statement
	 * instead -- OpportunityRepository.confirmPushed, which clears the stamp only while it is still
	 * the same edit and reports zero when it is not.
	 */

	public String getGhlId() {
		return ghlId;
	}

	public String getGhlContactId() {
		return ghlContactId;
	}

	public UUID getPipelineId() {
		return pipelineId;
	}

	public String getGhlStageId() {
		return ghlStageId;
	}

	public String getName() {
		return name;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getStatus() {
		return status;
	}

	public String getSource() {
		return source;
	}

	public String getGhlAssignedTo() {
		return ghlAssignedTo;
	}

	public Instant getGhlCreatedAt() {
		return ghlCreatedAt;
	}

	public Instant getGhlUpdatedAt() {
		return ghlUpdatedAt;
	}

	public Instant getLastStatusChangeAt() {
		return lastStatusChangeAt;
	}

	public Instant getLastStageChangeAt() {
		return lastStageChangeAt;
	}

	public Instant getLocalUpdatedAt() {
		return localUpdatedAt;
	}

	public Instant getSyncedAt() {
		return syncedAt;
	}

	public Instant getMissingSince() {
		return missingSince;
	}

	/**
	 * GHL's values for this deal's custom fields.
	 *
	 * <p><strong>Replaced wholesale rather than merged</strong>, because a field cleared in GHL is
	 * absent from its answer, and merging would keep a value GHL no longer holds — the mirror would
	 * then disagree with GHL about something nobody edited.
	 */
	public void syncCustomFields(java.util.Map<String, String> values) {
		this.customFields = values == null ? new java.util.LinkedHashMap<>()
				: new java.util.LinkedHashMap<>(values);
	}

	public java.util.Map<String, String> getCustomFields() {
		return java.util.Collections.unmodifiableMap(customFields);
	}

	public boolean isLive() {
		return missingSince == null;
	}

	/** True while this row exists only in EvalOS — GHL has not acknowledged it yet. */
	public boolean isLocalOnly() {
		return ghlId == null;
	}
}
