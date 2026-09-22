package com.ie.evalos.integration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * One contact, read from GHL.
 *
 * <p><strong>This exists because nothing fills {@code contact_snapshot} for a deal that started
 * in GHL.</strong> The three writers of that table are all EvalOS-side events — Handoff A's won
 * opportunity, a portal sign-up, and the {@code contact.created}/{@code contact.updated} webhook.
 * {@code OpportunityMirrorService} stores the contact <em>id</em> on the deal and nothing else, so
 * a salesperson opening a deal somebody entered in GHL saw "no contact on this deal yet — it
 * arrives with the next sync", which was not true: no sweep was ever going to bring it.
 *
 * <p><strong>Read only, and one contact at a time.</strong> There is no list here on purpose. The
 * caller is one opened deal, the rate limit is per location, and a bulk contact pull is a mirror
 * sweep's job rather than a screen's — see {@code ContactSnapshotService.findOrFetch}, which saves
 * what this returns so the second open costs no GHL call at all.
 *
 * <p><strong>{@code contacts.readonly}</strong>, the same grant
 * {@link GhlCalendarClient#CONTACT_READ_SCOPE} already uses for a contact's appointments — so this
 * needs no new permission on the token.
 */
@Component
public class GhlContactClient {

	/** Listed for the same reason every other client here lists one: so a 401 is diagnosable. */
	public static final String READ_SCOPE = "contacts.readonly";

	private final GhlHttp http;

	GhlContactClient(GhlHttp http) {
		this.http = http;
	}

	/**
	 * What a desk is shown about the person.
	 *
	 * <p>{@code name} is GHL's own display name and is preferred over stitching
	 * {@code firstName}+{@code lastName} together: GHL fills it for a contact that has only a
	 * company, and joining two nulls produces a space.
	 */
	public record Contact(String id, String name, String email, String phone, String company) {
	}

	/**
	 * The contact GHL holds under this id.
	 *
	 * @throws GhlUnavailableException if GHL is not configured here, refused the request, or
	 *                                 answered with no contact. The caller decides whether that is
	 *                                 fatal — for the deal screen it is not, because the mirror is
	 *                                 still the source and a blank card is better than a 502.
	 */
	public Contact byId(String contactId) {
		ContactEnvelope response;
		try {
			response = http.get(ContactEnvelope.class,
					(uri) -> uri.path("/contacts/{contactId}").build(contactId));
		}
		catch (GhlUnavailableException refused) {
			throw missingScopeHint(refused);
		}

		ContactRow row = response == null ? null : response.contact();
		if (row == null || row.id() == null) {
			throw new GhlUnavailableException("GHL returned no contact for " + contactId, null,
					GhlFailure.EMPTY_RESPONSE, null);
		}
		return new Contact(row.id(), displayName(row), row.email(), row.phone(), row.companyName());
	}

	/** GHL's own {@code name}, or the two halves joined when it did not send one. */
	private static String displayName(ContactRow row) {
		if (row.name() != null && !row.name().isBlank()) {
			return row.name();
		}
		String joined = (blankToEmpty(row.firstName()) + " " + blankToEmpty(row.lastName())).trim();
		return joined.isEmpty() ? null : joined;
	}

	private static String blankToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static GhlUnavailableException missingScopeHint(GhlUnavailableException refused) {
		if (refused.failure() != GhlFailure.UNAUTHORIZED) {
			return refused;
		}
		return new GhlUnavailableException(refused.getMessage() + " — reading a contact needs the "
				+ READ_SCOPE + " scope, the same one the calendar client uses for a contact's "
				+ "appointments. If those work and this does not, the grant is not the cause.",
				refused, GhlFailure.UNAUTHORIZED, refused.status());
	}

	/**
	 * One page of the location's contacts, newest change last.
	 *
	 * <p><strong>{@code POST /contacts/search}, not a GET list.</strong> GHL's contact listing is a
	 * search endpoint: the body carries {@code locationId}, {@code pageLimit} (100 is its maximum)
	 * and a {@code searchAfter} cursor, and there is no offset. That is a better fit than it looks
	 * — an offset walk over a table somebody is adding rows to skips and repeats, and a cursor
	 * cannot.
	 *
	 * <p><strong>Sorted by {@code dateUpdated} ascending, which is the load-bearing choice.</strong>
	 * The mirror pages from oldest change to newest, so a contact edited <em>while</em> the sweep is
	 * running moves ahead of the cursor and is picked up rather than skipped. Descending would
	 * order the pull the other way and lose exactly those rows.
	 *
	 * @param cursor the previous page's {@link Page#cursor}, or null for the first page
	 * @return the rows and the cursor to pass back, or {@link Page#cursor} null at the end
	 * @throws GhlUnavailableException if GHL is not configured here or refused the request
	 */
	public Page page(List<Object> cursor, int pageLimit) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("locationId", http.locationId());
		body.put("pageLimit", pageLimit);
		body.put("sort", List.of(Map.of("field", "dateUpdated", "direction", "asc")));
		if (cursor != null && !cursor.isEmpty()) {
			body.put("searchAfter", cursor);
		}

		SearchResponse response;
		try {
			// `search`, not `post`: this is a listing that GHL happens to expose as a POST, and
			// routing it through the write verb would put this class on the wrong side of the
			// audit guard for a call that changes nothing. See GhlHttp.search.
			response = http.search(SearchResponse.class, "/contacts/search", body);
		}
		catch (GhlUnavailableException refused) {
			throw missingScopeHint(refused);
		}

		List<ContactRow> rows = response == null || response.contacts() == null
				? List.of() : response.contacts();
		List<Contact> contacts = rows.stream()
				.filter((row) -> row.id() != null && !row.id().isBlank())
				.map((row) -> new Contact(row.id(), displayName(row), row.email(), row.phone(),
						row.companyName()))
				.toList();

		// **The cursor is the LAST ROW's, and GHL puts it on the row rather than on the envelope.**
		// A short page is not the end — GHL can return fewer than asked for and still have more —
		// so the end is "no rows came back", and that is the only thing that stops the walk.
		List<Object> next = rows.isEmpty() ? null : rows.get(rows.size() - 1).searchAfter();
		return new Page(contacts, next, response == null ? 0 : response.total());
	}

	/**
	 * A page of contacts and the cursor that follows it.
	 *
	 * @param cursor null when there is nothing after this page
	 * @param total  how many contacts the location holds in all. Reported by GHL on every page and
	 *               used only for the ledger line, so a sweep's progress is legible in the admin
	 *               panel rather than being a count with nothing to compare it to
	 */
	public record Page(List<Contact> contacts, List<Object> cursor, long total) {
	}

	// --- wire shapes -----------------------------------------------------------------

	record ContactEnvelope(ContactRow contact) {
	}

	record SearchResponse(List<ContactRow> contacts, long total) {
	}

	/**
	 * GHL's contact record, narrowed to what the deal screen shows.
	 *
	 * <p>The payload carries far more — tags, custom fields, DND flags, the location id. None of
	 * it is bound: a record component here is a field some screen may quietly start depending on,
	 * and the contact record is the single largest carrier of client PII in this API.
	 */
	record ContactRow(String id, String name, String firstName, String lastName, String email,
			String phone, String companyName, List<Object> searchAfter) {
	}
}
