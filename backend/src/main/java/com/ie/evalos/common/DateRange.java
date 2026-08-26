package com.ie.evalos.common;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The shell's period vocabulary, and <strong>nothing but the vocabulary</strong>.
 *
 * <p>Seven names: {@code today}, {@code week}, {@code month}, {@code year} — each meaning
 * <em>this</em> one, to date — plus {@code last-month}, {@code last-year} and {@code custom}. It is
 * an enum rather than a string because {@code code-standards.md} says to model a closed vocabulary
 * with a type, and {@link #parse} is the one place a request string becomes it.
 *
 * <p><strong>This used to carry an {@code int days} and do the arithmetic itself; it no longer
 * does any.</strong> "Month = 30 days" could not survive the filter gaining calendar periods:
 * <em>this</em> month is 1 day wide on the 1st and 31 on the 31st, and <em>last</em> month does not
 * end today. {@link DateWindow} resolves a name into the two dates it means, so there is still
 * exactly one home for the fact — it just moved, because the fact stopped being a number.
 *
 * <p><strong>Every window here looks backwards.</strong> Worth stating because the production
 * board's filter looks <em>forwards</em> to a deadline and once used these same words. It no
 * longer does: the board owns its own {@code DeadlineWindow}, because {@code last-month} cannot be
 * a "due before" cutoff and a shared type that is meaningless for half its callers is worse than
 * two types. {@code ui-context.md} recorded that collision for two units before it was resolved
 * this way.
 */
public enum DateRange {

	/** The calendar day, in the business's zone — not the last 24 hours. */
	TODAY,

	/** Monday of the current week through today. */
	WEEK,

	/** The 1st of the current month through today. */
	MONTH,

	/** 1 January of the current year through today. */
	YEAR,

	/** The whole of the previous calendar month. The one named range that does not end today. */
	LAST_MONTH,

	/** The whole of the previous calendar year. */
	LAST_YEAR,

	/**
	 * An explicit {@code from}/{@code to} pair, supplied by the caller.
	 *
	 * <p>The only constant that carries no window of its own — see
	 * {@link DateWindow#of(String, String, String, java.time.Clock)}, which requires both dates for
	 * this one and refuses them for every other.
	 */
	CUSTOM;

	/**
	 * @throws InvalidRequestException on anything else. Refused rather than defaulted: silently
	 *                                 answering for a month when the caller asked for a year is a
	 *                                 wrong number that looks right
	 */
	public static DateRange parse(String raw) {
		for (DateRange range : values()) {
			if (range.wireName().equalsIgnoreCase(raw == null ? null : raw.trim())) {
				return range;
			}
		}
		// Built from the enum rather than written out, so a new constant cannot be added without
		// appearing in the error the caller reads. The previous version hard-coded the list and
		// would have named four options while accepting seven.
		throw new InvalidRequestException("range must be one of "
				+ Stream.of(values()).map(DateRange::wireName).collect(Collectors.joining(", ")));
	}

	/**
	 * Lower-case and hyphenated, matching the wire vocabulary the frontend sends.
	 *
	 * <p>{@code LAST_MONTH} is {@code last-month} on the wire: an underscore in a query parameter
	 * value is legal but reads as an internal name leaking outward, and the frontend's own union
	 * type is hyphenated because that is what its labels look like.
	 */
	public String wireName() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}
}
