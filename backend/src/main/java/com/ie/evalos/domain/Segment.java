package com.ie.evalos.domain;

/**
 * Which kind of client a {@link Role#SALES} or {@link Role#MARKETING} employee handles.
 *
 * <p><strong>This is not an access key, and the whole design depends on it never becoming one.</strong>
 * The three kinds carry <em>identical</em> permissions — each sees the one pipeline they own and
 * does the same things with it — so they are a column rather than six more roles. Six enum values
 * would have grown every {@code switch (role)}, {@code team_member_role_valid}, the nav tests and
 * the permission matrix to express a distinction that changes no permission: an org chart encoded
 * in a security enum.
 *
 * <p>What differs between them is which clients they work, and <strong>GHL's workflows do that
 * routing</strong> — a qualified lead is promoted into the right person's pipeline by automation
 * the business already owns, not by anything here.
 *
 * <p><strong>So nothing in production code may branch on this value</strong>, and
 * {@code SegmentIsNotAnAccessKeyTest} fails the build if anything does. The day a segment needs a
 * different permission, that is a new {@link Role} argued in
 * {@code context/specs/00b-ghl-operational-programme.md} first — not an {@code if} here.
 */
public enum Segment {

	/** Immigration attorneys and law firms instructing on behalf of a client. */
	ATTORNEY,

	/** Employers and staffing firms evaluating candidates. */
	EMPLOYER_FIRM,

	/** A person buying their own evaluation. */
	INDIVIDUAL
}
