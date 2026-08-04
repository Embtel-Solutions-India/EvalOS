package com.ie.evalos.domain;

/**
 * What kind of actor wrote an audit row — one value per authentication surface in EvalOS.
 *
 * <p>{@code audit_event.actor_id} answers "which staff member", and a null there used to have to
 * mean both "the system" and "somebody who is not staff". A client approving a draft (Unit 14)
 * is the first actor that is neither, and their approval is what commits a letter to an expert's
 * signature, so the trail states the kind as well as the id.
 *
 * <p><strong>The column is nullable and historical rows carry no value</strong>, because
 * {@code V10}'s trigger means no UPDATE can ever backfill them — see {@code V22}'s header. A
 * null means "written before this column existed": read {@code SYSTEM} for those when
 * {@code actor_id} is null and {@code STAFF} otherwise.
 */
public enum ActorType {

	/** A signed-in team member. {@code actor_id} names them. */
	STAFF,

	/** EvalOS itself: an inbound webhook, or a scheduled job later. {@code actor_id} is null. */
	SYSTEM,

	/** The client, through their own portal link (Unit 14). {@code actor_id} is null. */
	CLIENT,

	/** The expert, through their own portal link (Unit 15). {@code actor_id} is null. */
	EXPERT
}
