package com.ie.evalos.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The location's reference lists, mirrored — Unit 47.
 *
 * <p><strong>Three tables that are the same table three times</strong>, which is why they share a
 * superclass rather than repeating the mirror's bookkeeping. Each is a small, slow-changing,
 * location-scoped list a form reads on every render — the shape 44a already solved for
 * {@code pipeline} — so each is upserted on GHL's own id, stamped {@code synced_at}, and
 * <strong>never deleted</strong>: a row GHL stops returning is stamped {@link #missingSince} and
 * kept, because a calendar archived for an afternoon must not take a booking option away for good.
 *
 * <p><strong>Why a superclass and not one table with a {@code kind} column.</strong> The three have
 * genuinely different columns — a custom field has a data type and a picklist, a calendar has a
 * slot length, a user has an email — and a single table would carry every column nullable and let
 * any of them be set on any kind. The shared part is exactly the mirror's bookkeeping, and that is
 * what is shared.
 *
 * <p><strong>GHL owns every column on all three.</strong> Nothing here is ever pushed, so like
 * {@code pipeline} these can only be behind, never in conflict — no {@code FieldOwnership} question
 * arises and 45e does not apply.
 */
@MappedSuperclass
public abstract class GhlReference extends ScopedEntity {

	/** GHL's id, verbatim — the upsert key, and what a form posts back. */
	@Column(name = "ghl_id", nullable = false, updatable = false)
	private String ghlId;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "synced_at", nullable = false)
	private Instant syncedAt;

	/** Non-null once GHL stopped returning this row. A soft delete, never a hard one. */
	@Column(name = "missing_since")
	private Instant missingSince;

	protected GhlReference() {
		// for JPA
	}

	protected GhlReference(UUID brandId, String ghlId, String name) {
		super(brandId);
		this.ghlId = ghlId;
		this.name = name;
		this.syncedAt = Instant.now();
	}

	/**
	 * GHL returned this row, so it is present and current.
	 *
	 * <p>Clearing {@code missingSince} is the half that is easy to forget: a calendar switched off
	 * and back on has to come back, or the first outage permanently shrinks the form.
	 */
	protected void seen(String name) {
		this.name = name;
		this.syncedAt = Instant.now();
		this.missingSince = null;
	}

	/** GHL's answer no longer contains this row. Stamped once, so the date means "since when". */
	public void markMissing(Instant at) {
		if (this.missingSince == null) {
			this.missingSince = at;
		}
	}

	public String getGhlId() {
		return ghlId;
	}

	public String getName() {
		return name;
	}

	public Instant getSyncedAt() {
		return syncedAt;
	}

	public Instant getMissingSince() {
		return missingSince;
	}

	/** Whether GHL still returns it. Only live rows are offered to a form. */
	public boolean isLive() {
		return missingSince == null;
	}

	/**
	 * A GHL custom field <strong>definition</strong>.
	 *
	 * <p><strong>The definition; the values live on {@code opportunity.custom_fields}</strong>
	 * (Unit 47b). This table is what turns a GHL field id into a label a human can read, which is
	 * why the values are keyed by id rather than by name — a rename in GHL changes this row and
	 * leaves every stored value intact.
	 */
	@Entity
	@Table(name = "ghl_custom_field")
	public static class CustomField extends GhlReference {

		@Column(name = "model", nullable = false, updatable = false)
		private String model;

		@Column(name = "field_key")
		private String fieldKey;

		/**
		 * GHL's own type string, not an enum.
		 *
		 * <p>A type EvalOS has never seen must reach the form as something it can render
		 * generically rather than fail deserialization — the ruling {@code GhlCustomFieldClient}
		 * already carries, kept here so the mirror cannot be stricter than the client that fills it.
		 */
		@Column(name = "data_type")
		private String dataType;

		@JdbcTypeCode(SqlTypes.JSON)
		@Column(name = "picklist_options", nullable = false)
		private List<String> picklistOptions = List.of();

		protected CustomField() {
		}

		public CustomField(UUID brandId, String ghlId, String model, String name) {
			super(brandId, ghlId, name);
			this.model = model;
		}

		public void seen(String name, String fieldKey, String dataType, List<String> picklistOptions) {
			seen(name);
			this.fieldKey = fieldKey;
			this.dataType = dataType;
			this.picklistOptions = picklistOptions == null ? List.of() : List.copyOf(picklistOptions);
		}

		public String getModel() {
			return model;
		}

		public String getFieldKey() {
			return fieldKey;
		}

		public String getDataType() {
			return dataType;
		}

		public List<String> getPicklistOptions() {
			return picklistOptions;
		}
	}

	/**
	 * A GHL calendar.
	 *
	 * <p><strong>The structure, never the availability.</strong> Free slots are not mirrored and
	 * must not be: GHL computes them from open hours, buffers, caps and the assignee's other
	 * appointments, and a mirrored slot is wrong within a minute of being written. A calendar is a
	 * fact about the location; a free slot is a fact about right now.
	 */
	@Entity
	@Table(name = "ghl_calendar")
	public static class Calendar extends GhlReference {

		@Column(name = "active", nullable = false)
		private boolean active = true;

		@Column(name = "slot_minutes")
		private Integer slotMinutes;

		@Column(name = "title_template")
		private String titleTemplate;

		protected Calendar() {
		}

		public Calendar(UUID brandId, String ghlId, String name) {
			super(brandId, ghlId, name);
		}

		public void seen(String name, boolean active, Integer slotMinutes, String titleTemplate) {
			seen(name);
			this.active = active;
			this.slotMinutes = slotMinutes;
			this.titleTemplate = titleTemplate;
		}

		public boolean isActive() {
			return active;
		}

		public Integer getSlotMinutes() {
			return slotMinutes;
		}

		public String getTitleTemplate() {
			return titleTemplate;
		}
	}

	/**
	 * A tag the location defines — Unit 47b.
	 *
	 * <p><strong>The vocabulary only.</strong> Which contact carries which tag arrives on the
	 * contact record; this is the list of tags that exist, which is what a filter or a picker needs
	 * and what {@code 47} §4 cut before the business asked for it back.
	 *
	 * <p>Nothing keys behaviour off a tag name, and nothing should start to without a decision:
	 * GHL workflows key off tags, so a tag EvalOS acted on would be EvalOS racing an automation.
	 */
	@Entity
	@Table(name = "ghl_tag")
	public static class Tag extends GhlReference {

		protected Tag() {
		}

		public Tag(UUID brandId, String ghlId, String name) {
			super(brandId, ghlId, name);
		}

		public void seenAs(String name) {
			seen(name);
		}
	}

	/**
	 * A user of the GHL location, for the booking form's team-member picker.
	 *
	 * <p><strong>Not joined to {@code team_member}.</strong> That column is `open-decisions.md` Q9,
	 * and inventing the join here would answer a question about appointment ownership that this
	 * unit has no business answering.
	 */
	@Entity
	@Table(name = "ghl_user")
	public static class User extends GhlReference {

		/** Nullable: GHL's list has returned rows without one, and a picker can show a name. */
		@Column(name = "email")
		private String email;

		protected User() {
		}

		public User(UUID brandId, String ghlId, String name) {
			super(brandId, ghlId, name);
		}

		public void seen(String name, String email) {
			seen(name);
			this.email = email;
		}

		public String getEmail() {
			return email;
		}
	}
}
