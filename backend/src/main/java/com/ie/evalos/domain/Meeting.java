package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A meeting EvalOS booked into GHL, mirrored from GHL's own response.
 *
 * <p><strong>GHL owns the appointment; this is a copy.</strong> The row exists so a desk can see
 * what it booked without asking GHL on every screen, and so the Sales desk stops being write-only
 * — `SalesMeetingService` used to hand the booking response to the caller and keep nothing, which
 * is why no screen in EvalOS could show a meeting it had itself created. See `V47__meeting.sql`
 * for the three read routes that were compared and why a mirror won.
 *
 * <p><strong>{@code status} is GHL's word, stored verbatim.</strong> It is deliberately a
 * {@code String} and not an enum: a remote system's vocabulary can gain a value tomorrow, and an
 * enum would turn that into a deserialization failure on a screen that only wanted to print it.
 * EvalOS's own lifecycles get enums and a transition table; a mirrored one does not.
 *
 * <p><strong>Everything here is {@code updatable = false} except the fields a reschedule moves
 * and the sync stamp.</strong> A meeting's identity, its deal and its contact never change — a
 * meeting moved to a different deal is a different meeting — so making them immutable means a
 * mis-wired update fails loudly instead of quietly re-pointing a row.
 */
@Entity
@Table(name = "meeting")
public class Meeting extends ScopedEntity {

	@Column(name = "ghl_appointment_id", nullable = false, updatable = false)
	private String ghlAppointmentId;

	@Column(name = "ghl_opportunity_id", nullable = false, updatable = false)
	private String ghlOpportunityId;

	@Column(name = "ghl_contact_id", nullable = false, updatable = false)
	private String ghlContactId;

	@Column(name = "ghl_calendar_id", nullable = false, updatable = false)
	private String ghlCalendarId;

	/**
	 * The pipeline the deal sat on when this was booked — the access key.
	 *
	 * <p>{@code PipelineScope} scopes a desk by pipeline, and a meetings read has to apply the
	 * same predicate. Holding it here is what makes that one query instead of a round trip to GHL
	 * to ask which pipeline the opportunity is on.
	 *
	 * <p>It is a snapshot, and that is the honest trade: a deal moved to another pipeline in GHL
	 * leaves its old meetings scoped to the old desk until Unit 45's sweep reconciles. The
	 * alternative — resolving the pipeline on every read — is the second round trip this column
	 * exists to avoid.
	 */
	@Column(name = "ghl_pipeline_id", nullable = false, updatable = false)
	private String ghlPipelineId;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Column(name = "status")
	private String status;

	@Column(name = "booked_by", nullable = false, updatable = false)
	private UUID bookedBy;

	@Column(name = "synced_at", nullable = false)
	private Instant syncedAt;

	protected Meeting() {
		// for JPA
	}

	public Meeting(UUID brandId, String ghlAppointmentId, String ghlOpportunityId,
			String ghlContactId, String ghlCalendarId, String ghlPipelineId, String title,
			Instant startsAt, Instant endsAt, String status, UUID bookedBy) {
		super(brandId);
		this.ghlAppointmentId = ghlAppointmentId;
		this.ghlOpportunityId = ghlOpportunityId;
		this.ghlContactId = ghlContactId;
		this.ghlCalendarId = ghlCalendarId;
		this.ghlPipelineId = ghlPipelineId;
		this.title = title;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.status = status;
		this.bookedBy = bookedBy;
		this.syncedAt = Instant.now();
	}

	/**
	 * Applies a reschedule that GHL has already accepted.
	 *
	 * <p>Named for the event rather than as three setters, so the row cannot be moved a field at a
	 * time into a state GHL never confirmed. The caller passes GHL's response, not the request.
	 */
	/**
	 * GHL's version of this appointment — <strong>read-back, Unit 47b</strong>.
	 *
	 * <p>This row held what GHL confirmed at booking and nothing after, so an appointment cancelled
	 * or moved in GHL's own UI was invisible to the diary. `47` §4 named that as accepted
	 * divergence; it rides on the opportunity search, so it did not have to be.
	 *
	 * <p>{@code appointmentStatus} is GHL's own vocabulary (confirmed, cancelled, showed…) and is
	 * stored as given rather than mapped: EvalOS has no second status model for a meeting, and
	 * inventing one would be a translation that can itself be wrong.
	 */
	public void syncFromGhl(String title, Instant startsAt, Instant endsAt, String status) {
		if (title != null && !title.isBlank()) {
			this.title = title;
		}
		movedTo(startsAt == null ? this.startsAt : startsAt, endsAt == null ? this.endsAt : endsAt,
				status == null || status.isBlank() ? this.status : status);
	}

	public void movedTo(Instant startsAt, Instant endsAt, String status) {
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.status = status;
		this.syncedAt = Instant.now();
	}

	public String getGhlAppointmentId() {
		return ghlAppointmentId;
	}

	public String getGhlOpportunityId() {
		return ghlOpportunityId;
	}

	public String getGhlContactId() {
		return ghlContactId;
	}

	public String getGhlCalendarId() {
		return ghlCalendarId;
	}

	public String getGhlPipelineId() {
		return ghlPipelineId;
	}

	public String getTitle() {
		return title;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public Instant getEndsAt() {
		return endsAt;
	}

	public String getStatus() {
		return status;
	}

	public UUID getBookedBy() {
		return bookedBy;
	}

	public Instant getSyncedAt() {
		return syncedAt;
	}
}
