package com.ie.evalos.domain;

/**
 * Which mirrored record a {@link SyncDrift} row is about.
 *
 * <p><strong>Only the ones an audit can honestly compare.</strong> {@code PIPELINE} and
 * {@code PIPELINE_STAGE} are deliberately absent: the 44a sweep overwrites them hourly through the
 * same code path an audit would compare against, so auditing them would report zero by construction
 * — a screen that proves nothing. They join the day something else starts writing them.
 *
 * <p>{@code CONTACT} is absent for a different reason and will arrive: EvalOS has no GHL contact
 * <em>read</em> client, only the upsert, so there is nothing to compare against yet.
 */
public enum SyncEntity {

	OPPORTUNITY,

	CONTACT
}
