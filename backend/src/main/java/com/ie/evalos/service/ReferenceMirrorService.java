package com.ie.evalos.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.GhlReference;
import com.ie.evalos.integration.GhlCalendarClient;
import com.ie.evalos.integration.GhlCustomFieldClient;
import com.ie.evalos.integration.GhlTagClient;
import com.ie.evalos.integration.GhlUserClient;
import com.ie.evalos.repository.GhlCalendarRepository;
import com.ie.evalos.repository.GhlCustomFieldRepository;
import com.ie.evalos.repository.GhlTagRepository;
import com.ie.evalos.repository.GhlUserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The location's reference lists, kept as EvalOS rows — Unit 47.
 *
 * <p><strong>Three lists, one service, one sweep, because they change at the same speed.</strong>
 * Somebody adds a calendar or a custom field a few times a year. Three sweeps would mean three
 * intervals, three ledger rows an hour, and three chances for one of them to be the one that
 * quietly stopped; one sweep is one thing to watch.
 *
 * <p><strong>Upsert on GHL's id, never delete</strong> — the rule every mirror in this codebase
 * follows since 44a. A row GHL stops returning is stamped {@code missing_since} and kept, and a row
 * that comes back clears it. Deleting would mean a calendar hidden for an afternoon takes its
 * bookings' only label with it.
 *
 * <p><strong>The GHL reads happen outside the write transaction</strong>, as on every other mirror:
 * holding a pooled connection open across a network round trip is how one slow upstream becomes an
 * exhausted pool.
 *
 * <p><strong>Free slots are not here and must not be.</strong> See {@code 47} §3: availability is
 * GHL's to compute and a mirrored slot is wrong within a minute. This service mirrors structure.
 */
@Service
public class ReferenceMirrorService {

	/** The only GHL object whose fields anything reads today. The endpoint takes it as `?model=`. */
	public static final String OPPORTUNITY_MODEL = "opportunity";

	private static final Logger log = LoggerFactory.getLogger(ReferenceMirrorService.class);

	private final GhlCustomFieldClient customFieldClient;
	private final GhlCalendarClient calendarClient;
	private final GhlUserClient userClient;
	/** Unit 47b: the fourth list. Its own scope, so its own failure. */
	private final GhlTagClient tagClient;
	private final GhlCustomFieldRepository customFields;
	private final GhlCalendarRepository calendars;
	private final GhlUserRepository users;
	private final GhlTagRepository tags;
	private final UUID sellingBrandId;

	ReferenceMirrorService(GhlCustomFieldClient customFieldClient, GhlCalendarClient calendarClient,
			GhlUserClient userClient, GhlTagClient tagClient, GhlCustomFieldRepository customFields,
			GhlCalendarRepository calendars, GhlUserRepository users, GhlTagRepository tags,
			SellingBrand sellingBrand) {
		this.customFieldClient = customFieldClient;
		this.calendarClient = calendarClient;
		this.userClient = userClient;
		this.tagClient = tagClient;
		this.customFields = customFields;
		this.calendars = calendars;
		this.users = users;
		this.tags = tags;
		this.sellingBrandId = sellingBrand.id();
	}

	/** What the sweep did, so the ledger records something a GM can read. */
	public record RefreshResult(int fields, int calendars, int users, int tags) {

		public int total() {
			return fields + calendars + users + tags;
		}
	}

	/**
	 * Re-read all three lists from GHL and write them.
	 *
	 * <p><strong>Each list is independent and a failure in one does not lose the other two.</strong>
	 * The three come from three endpoints with three scopes; a location missing the users scope
	 * should still get its calendars, and taking the whole sweep down for it would make one missing
	 * grant look like a dead sweep.
	 */
	public RefreshResult refresh() {
		if (sellingBrandId == null) {
			log.warn("Reference mirror skipped: evalos.ghl.sales-brand is blank, so there is no "
					+ "brand to hold the location's lists.");
			return new RefreshResult(0, 0, 0, 0);
		}
		return new RefreshResult(refreshCustomFields(), refreshCalendars(), refreshUsers(),
				refreshTags());
	}

	private int refreshCustomFields() {
		return guarded("custom fields", () -> {
			List<GhlCustomFieldClient.CustomField> fromGhl =
					customFieldClient.forOpportunities();
			return absorbCustomFields(fromGhl);
		});
	}

	private int refreshCalendars() {
		return guarded("calendars", () -> absorbCalendars(calendarClient.calendars()));
	}

	private int refreshUsers() {
		return guarded("users", () -> absorbUsers(userClient.inLocation()));
	}

	private int refreshTags() {
		return guarded("tags", () -> absorbTags(tagClient.inLocation()));
	}

	@Transactional
	public int absorbTags(List<GhlTagClient.Tag> fromGhl) {
		Set<String> seen = new HashSet<>();
		for (GhlTagClient.Tag row : fromGhl) {
			if (blank(row.id())) {
				continue;
			}
			seen.add(row.id());
			GhlReference.Tag held = tags.findByBrandIdAndGhlId(sellingBrandId, row.id())
					.orElseGet(() -> new GhlReference.Tag(sellingBrandId, row.id(), nameOf(row.name(), row.id())));
			held.seenAs(nameOf(row.name(), row.id()));
			tags.save(held);
		}
		stampMissing(tags.findByBrandIdOrderByNameAsc(sellingBrandId), seen, tags::save);
		return seen.size();
	}

	/** The location's tag vocabulary. Live rows only, like every other list here. */
	public List<GhlReference.Tag> locationTags() {
		if (sellingBrandId == null) {
			return List.of();
		}
		refreshIfEmpty(() -> tags.countByBrandId(sellingBrandId), this::refreshTags);
		return tags.findByBrandIdOrderByNameAsc(sellingBrandId).stream()
				.filter(GhlReference::isLive)
				.toList();
	}

	@Transactional
	public int absorbCustomFields(List<GhlCustomFieldClient.CustomField> fromGhl) {
		Set<String> seen = new HashSet<>();
		for (GhlCustomFieldClient.CustomField row : fromGhl) {
			if (blank(row.id())) {
				continue;
			}
			seen.add(row.id());
			GhlReference.CustomField held = customFields
					.findByBrandIdAndGhlId(sellingBrandId, row.id())
					.orElseGet(() -> new GhlReference.CustomField(sellingBrandId, row.id(),
							OPPORTUNITY_MODEL, nameOf(row.name(), row.id())));
			held.seen(nameOf(row.name(), row.id()), row.fieldKey(), row.dataType(), row.picklistOptions());
			customFields.save(held);
		}
		stampMissing(customFields.findByBrandIdAndModelOrderByNameAsc(sellingBrandId, OPPORTUNITY_MODEL),
				seen, customFields::save);
		return seen.size();
	}

	@Transactional
	public int absorbCalendars(List<GhlCalendarClient.CalendarOption> fromGhl) {
		Set<String> seen = new HashSet<>();
		for (GhlCalendarClient.CalendarOption row : fromGhl) {
			if (blank(row.id())) {
				continue;
			}
			seen.add(row.id());
			GhlReference.Calendar held = calendars.findByBrandIdAndGhlId(sellingBrandId, row.id())
					.orElseGet(() -> new GhlReference.Calendar(sellingBrandId, row.id(), nameOf(row.name(), row.id())));
			held.seen(nameOf(row.name(), row.id()), row.active(), row.slotMinutes(), row.titleTemplate());
			calendars.save(held);
		}
		stampMissing(calendars.findByBrandIdOrderByNameAsc(sellingBrandId), seen, calendars::save);
		return seen.size();
	}

	@Transactional
	public int absorbUsers(List<GhlUserClient.User> fromGhl) {
		Set<String> seen = new HashSet<>();
		for (GhlUserClient.User row : fromGhl) {
			if (blank(row.id())) {
				continue;
			}
			seen.add(row.id());
			GhlReference.User held = users.findByBrandIdAndGhlId(sellingBrandId, row.id())
					.orElseGet(() -> new GhlReference.User(sellingBrandId, row.id(), nameOf(row.name(), row.id())));
			held.seen(nameOf(row.name(), row.id()), row.email());
			users.save(held);
		}
		stampMissing(users.findByBrandIdOrderByNameAsc(sellingBrandId), seen, users::save);
		return seen.size();
	}

	// --- reads -----------------------------------------------------------------

	/**
	 * The opportunity custom field definitions, from the mirror.
	 *
	 * <p>Live rows only: a field GHL has stopped returning is kept in the table — a create's stored
	 * ids point at it — but must not be drawn on a form as a question still worth asking.
	 *
	 * <p><strong>None of the three reads is {@code readOnly}, and that is not an oversight.</strong>
	 * {@link #refreshIfEmpty} can write on the very first call, and a write inside a read-only
	 * transaction fails at runtime — on the one request that needed it most, a fresh environment's
	 * first booking form.
	 */
	public List<GhlReference.CustomField> opportunityFields() {
		if (sellingBrandId == null) {
			return List.of();
		}
		refreshIfEmpty(() -> customFields.countByBrandIdAndModel(sellingBrandId, OPPORTUNITY_MODEL),
				this::refreshCustomFields);
		return customFields.findByBrandIdAndModelOrderByNameAsc(sellingBrandId, OPPORTUNITY_MODEL)
				.stream().filter(GhlReference::isLive).toList();
	}

	public List<GhlReference.Calendar> bookableCalendars() {
		if (sellingBrandId == null) {
			return List.of();
		}
		refreshIfEmpty(() -> calendars.countByBrandId(sellingBrandId), this::refreshCalendars);
		return calendars.findByBrandIdOrderByNameAsc(sellingBrandId).stream()
				.filter(GhlReference::isLive)
				.toList();
	}

	public List<GhlReference.User> locationUsers() {
		if (sellingBrandId == null) {
			return List.of();
		}
		refreshIfEmpty(() -> users.countByBrandId(sellingBrandId), this::refreshUsers);
		return users.findByBrandIdOrderByNameAsc(sellingBrandId).stream()
				.filter(GhlReference::isLive)
				.toList();
	}

	// --- plumbing --------------------------------------------------------------

	/**
	 * One live read when a list has never been populated, and never again.
	 *
	 * <p><strong>This is not the refill-on-read Unit 46 deleted.</strong> That one fired on every
	 * board render; this can only fire while a table has no rows at all — the window between a
	 * fresh deployment and its first sweep. Without it a new environment's booking form has no
	 * calendars for up to an hour, and the fix ("run the sweep") is not one an unfamiliar person
	 * would guess.
	 *
	 * <p>After the first successful pass it is dead code that never runs again, which is the right
	 * price for removing a first-run cliff.
	 */
	private void refreshIfEmpty(java.util.function.LongSupplier count, Runnable refresh) {
		if (count.getAsLong() == 0) {
			log.info("Reference list is empty — reading it from GHL once. "
					+ "The REFERENCE_MIRROR sweep keeps it current from here.");
			refresh.run();
		}
	}

	/**
	 * Rows GHL's answer did not contain, stamped rather than deleted.
	 *
	 * <p>A row already stamped stays at its original date: {@code missing_since} means "since
	 * when", and refreshing it every hour would turn it into "as of the last sweep", which answers
	 * a different and much less useful question.
	 */
	private <T extends GhlReference> void stampMissing(List<T> held, Set<String> seen,
			java.util.function.Consumer<T> save) {
		Instant now = Instant.now();
		for (T row : held) {
			if (row.isLive() && !seen.contains(row.getGhlId())) {
				row.markMissing(now);
				save.accept(row);
				log.info("GHL no longer returns {} {}; marked missing, not deleted",
						row.getClass().getSimpleName(), row.getGhlId());
			}
		}
	}

	/**
	 * One list's failure is not the sweep's failure.
	 *
	 * <p>The three come from three endpoints with three scopes. A location missing the users scope
	 * should still get its calendars — and taking the whole pass down for it would make one missing
	 * grant look like a dead sweep, which is the thing the admin panel's staleness warning exists
	 * to mean.
	 */
	private int guarded(String what, java.util.function.IntSupplier read) {
		try {
			return read.getAsInt();
		}
		catch (RuntimeException failed) {
			log.warn("Reference mirror could not refresh {}: {}", what, failed.getMessage());
			return 0;
		}
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	/**
	 * GHL's id standing in for a name GHL did not send — the same guard
	 * {@code PipelineMirrorService.nameOf} has had since 44a.
	 *
	 * <p><strong>One nameless row used to cost the whole list.</strong> {@code GhlReference.name}
	 * is {@code NOT NULL} (V60, V62), so a null name raised a
	 * {@code DataIntegrityViolationException} that {@link #guarded} caught, logged as a warning and
	 * reported as zero — so nothing from that endpoint mirrored at all, and
	 * {@link #refreshIfEmpty} then re-ran the failing GHL read on every booking-form request. An id
	 * is a poor label and a readable one; an empty calendar list is a broken screen.
	 */
	private static String nameOf(String name, String ghlId) {
		return blank(name) ? ghlId : name;
	}
}
