package com.ie.evalos.domain;

/**
 * How close a case is to missing the date it was <em>promised</em> to the client.
 *
 * <p><strong>Not {@link SlaStatus}, and the difference is the whole reason this exists.</strong>
 * {@code SlaStatus} compares {@code stage_entered_at} against a per-stage budget and answers "is
 * this stage taking too long". This compares {@code deadline} against now and answers "will we
 * miss the promised date". The two routinely disagree — a case can sit comfortably inside a
 * twelve-hour PM-review budget with its deadline nine hours away — so they are separate types
 * and are labelled separately on screen ("Stage SLA" and "Deadline").
 *
 * <p>The bands are {@code ui-context.md}'s, not new ones. Note that {@link #OVERDUE} is the
 * <em>red band</em> rather than literally "past the date": that file puts "overdue" and
 * "at-risk deadline &lt;24h" in one colour, and a case four business hours from a deadline it
 * cannot meet is not usefully distinguished from one that has just missed it. A view that needs
 * genuinely-past-due reads the {@code deadline} column directly.
 */
public enum DeadlineRisk {

	/** Red: the deadline has passed, or fewer than 24 business hours remain. */
	OVERDUE,

	/** Amber: fewer than 48 business hours remain. */
	AT_RISK,

	/** Green: more than 48 business hours remain. */
	ON_TRACK
}
