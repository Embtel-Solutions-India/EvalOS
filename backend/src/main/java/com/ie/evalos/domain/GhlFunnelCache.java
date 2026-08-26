package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One marketing window's cached figures — the funnel payload the GM's screen displays, keyed by
 * which funnel and which period.
 *
 * <p><strong>A cache of a read, not a copy of GHL's pipeline.</strong> Individual opportunities
 * are still never stored — there is no {@code ghl_opportunity} table and there must not be one,
 * because a stage somebody dragged five seconds ago is already wrong in it. What is stored is the
 * aggregate the screen shows, plus the timestamp it was computed at so it can never be read as
 * live.
 *
 * <p><strong>Not a {@link ScopedEntity}, and that is deliberate rather than forgotten.</strong>
 * Every scoped entity carries {@code brand_id} because it belongs to a brand. These figures do
 * not: they come from the single GHL sub-account named by {@code evalos.ghl.location-id}, a global
 * setting with no link to any brand, so there is no value a {@code brand_id} here could correctly
 * hold. That is the same reason the endpoint is GM-only. Unit 25 moves the location onto
 * {@code brand}, and the column arrives as part of that change — not before it.
 *
 * <p><strong>Mutable, and not append-only.</strong> The append-only rule protects audit and
 * assignment history, which record what happened. This records only the most recent answer to a
 * question that will be asked again: every field except the window key is overwritten on each
 * refresh. Losing the whole table costs one slow page load — which is what V26 relies on when it
 * empties the table rather than translating the old range-name keys into window keys it cannot
 * reconstruct.
 *
 * <p>{@link #version} is why the writes are safe. Two callers can race past a stale row, and the
 * loser must not clobber the winner — see {@code MarketingPipelineService}, where a slow inline
 * read overwriting a completed background total was a real defect.
 */
@Entity
@Table(name = "ghl_funnel_cache")
public class GhlFunnelCache {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", updatable = false)
	private UUID id;

	/** {@code MarketingPipelineService.Funnel}, as its name. */
	@Column(name = "funnel", nullable = false, updatable = false)
	private String funnel;

	/**
	 * The <strong>resolved window</strong> — {@code DateWindow.key()}, e.g.
	 * {@code 2026-08-01..2026-08-26}. Not the range's name, and not called {@code range} either:
	 * that reads as the SQL type name and shadows it in hand-written queries.
	 *
	 * <p><strong>It held the range name until V26, and that became wrong the moment the filter
	 * gained {@code custom}.</strong> Every custom window is named {@code custom}, so a
	 * name-keyed row would be shared by two different date ranges and serve one's figures for
	 * the other — undetectable on screen, because the payloads are identical in shape. Keying on
	 * the days makes it impossible instead of merely unlikely.
	 */
	@Column(name = "window_key", nullable = false, updatable = false)
	private String windowKey;

	/** The whole {@code MarketingPipeline} record as JSON — read back whole, never queried into. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false)
	private String payload;

	/** {@code READY | TOTALLING | UNAVAILABLE}, out of the payload so a claim needs no JSON parse. */
	@Column(name = "detail", nullable = false)
	private String detail;

	/** When GHL was asked. The TTL is measured from here, not from when the row was written. */
	@Column(name = "read_at", nullable = false)
	private Instant readAt;

	/**
	 * Set while a background total is running, null otherwise.
	 *
	 * <p>A timestamp rather than a flag so the claim can expire: the process holding an in-heap
	 * claim died with it, but a row outlives the instance that wrote it, so a totaller killed
	 * mid-read would otherwise wedge this window at {@code TOTALLING} forever.
	 */
	@Column(name = "totalling_since")
	private Instant totallingSince;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected GhlFunnelCache() {
		// JPA.
	}

	public GhlFunnelCache(String funnel, String windowKey, String payload, String detail, Instant readAt,
			Instant totallingSince) {
		this.funnel = funnel;
		this.windowKey = windowKey;
		this.payload = payload;
		this.detail = detail;
		this.readAt = readAt;
		this.totallingSince = totallingSince;
	}

	public UUID getId() {
		return this.id;
	}

	public String getFunnel() {
		return this.funnel;
	}

	public String getWindowKey() {
		return this.windowKey;
	}

	public String getPayload() {
		return this.payload;
	}

	public String getDetail() {
		return this.detail;
	}

	public Instant getReadAt() {
		return this.readAt;
	}

	public Instant getTotallingSince() {
		return this.totallingSince;
	}

	public long getVersion() {
		return this.version;
	}

	/** Replace the figures for this window. The window key itself is never reassigned. */
	public void refresh(String payload, String detail, Instant readAt, Instant totallingSince) {
		this.payload = payload;
		this.detail = detail;
		this.readAt = readAt;
		this.totallingSince = totallingSince;
	}
}
