package com.ie.evalos.service;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.integration.GhlContactClient;
import com.ie.evalos.integration.GhlUnavailableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pulls the location's contacts into {@code contact_snapshot}, so every screen reads EvalOS's own
 * table rather than GHL.
 *
 * <p><strong>This closes the last hole in the contact mirror.</strong> Before it, every writer of
 * that table was an EvalOS-side event — Handoff A's won opportunity, a portal sign-up, 45d's
 * {@code contact.*} webhook, and the deal screen's own fallback read. Not one of them fires for a
 * contact that already existed in GHL, so the contacts list showed the few dozen people EvalOS had
 * happened to meet out of the <strong>1,407</strong> the location actually holds. The list was not
 * wrong about what it had; it was wrong about what there was.
 *
 * <p><strong>A full pass every time, not an incremental one.</strong> 1,407 contacts is fifteen
 * pages at GHL's maximum of 100, which is about 1.7 seconds of the location's shared budget —
 * cheaper than the nightly sync audit by an order of magnitude. A high-water mark on
 * {@code dateUpdated} would save most of that and cost a cursor to store, a clock to trust and a
 * first-run backfill to special-case, and it would still have to do a full pass periodically to
 * catch anything edited behind the mark.
 *
 * <p>ponytail: full pass, no high-water mark. Switch to {@code dateUpdated >= lastRun} with a
 * stored cursor when this location passes roughly 20,000 contacts — the client already sorts
 * ascending by {@code dateUpdated} precisely so that filter can be added without changing the
 * paging.
 *
 * <p><strong>The walk is bounded even so.</strong> {@link #MAX_PAGES} stops a runaway — a cursor
 * GHL keeps re-serving, a location that grew a hundredfold — from spending the entire rate budget
 * and starving every desk behind it. Hitting the ceiling is logged at warn and leaves the mirror
 * partial, which the next pass corrects; it does not fail the sweep, because a partial mirror is
 * better than none and the ledger records what was seen.
 *
 * <p><strong>Every contact lands under the selling brand.</strong> The configured GHL location
 * belongs to exactly one brand ({@code evalos.ghl.sales-brand}, Unit 36 §4a) — that is the same
 * mapping {@code OpportunityMirrorService} and {@code ReferenceMirrorService} use, and it is what
 * makes a location-wide read attributable at all. With no selling brand configured this does
 * nothing rather than guessing one.
 */
@Service
public class ContactMirrorService {

	private static final Logger log = LoggerFactory.getLogger(ContactMirrorService.class);

	/** GHL's own maximum for {@code pageLimit}; asking for more is refused, not clamped. */
	private static final int PAGE_SIZE = 100;

	/**
	 * The ceiling on one pass — 50,000 contacts at the page size above.
	 *
	 * <p>Not a tuning knob. It is the point past which "the walk is not terminating" is a better
	 * explanation than "this location is large", and continuing past it would spend the whole
	 * location's budget proving otherwise.
	 */
	private static final int MAX_PAGES = 500;

	private final GhlContactClient ghl;
	private final ContactSnapshotService contacts;
	private final UUID sellingBrandId;

	ContactMirrorService(GhlContactClient ghl, ContactSnapshotService contacts,
			SellingBrand sellingBrand) {
		this.ghl = ghl;
		this.contacts = contacts;
		this.sellingBrandId = sellingBrand.id();
	}

	/** What one pass did, for the ledger and for the log. */
	public record Result(int seen, int pages, long locationTotal, boolean complete) {
	}

	/**
	 * Reads every contact in the location and writes each one into {@code contact_snapshot}.
	 *
	 * <p><strong>Each contact is written through {@link ContactSnapshotService#findOrCreate}</strong>
	 * rather than saved directly, so the match rules apply to a mirrored row exactly as they do to
	 * a won opportunity's: GHL id first, then email but only where it does not contradict. Writing
	 * straight to the table would let this pass create a second row for somebody the portal already
	 * knew under the same address, which is the wrong-merge-versus-duplicate problem that service
	 * exists to get right.
	 *
	 * <p><strong>A failed page ends the pass rather than failing it.</strong> What has been written
	 * is already written and is correct; the rest arrives on the next run. Throwing here would roll
	 * nothing back — each contact is its own transaction — and would turn a partial success into a
	 * FAILED ledger row that reads as though nothing happened.
	 */
	public Result refresh() {
		if (sellingBrandId == null) {
			log.debug("No selling brand configured; the contact mirror has nothing to attribute to");
			return new Result(0, 0, 0, true);
		}

		List<Object> cursor = null;
		int seen = 0;
		int pages = 0;
		long locationTotal = 0;

		while (pages < MAX_PAGES) {
			GhlContactClient.Page page;
			try {
				page = ghl.page(cursor, PAGE_SIZE);
			}
			catch (GhlUnavailableException unavailable) {
				log.warn("Contact mirror stopped after {} contacts: GHL refused page {}", seen,
						pages + 1, unavailable);
				return new Result(seen, pages, locationTotal, false);
			}

			pages++;
			locationTotal = page.total();
			if (page.contacts().isEmpty()) {
				// The only end condition. A short page is NOT the end: GHL may return fewer rows
				// than asked for and still have more behind the cursor.
				return new Result(seen, pages, locationTotal, true);
			}

			for (GhlContactClient.Contact contact : page.contacts()) {
				contacts.findOrCreate(sellingBrandId, new ContactSnapshotService.Details(
						contact.id(), contact.name(), contact.email(), contact.phone(),
						contact.company(), null, null, null, null, null));
				seen++;
			}

			cursor = page.cursor();
			if (cursor == null) {
				return new Result(seen, pages, locationTotal, true);
			}
		}

		log.warn("Contact mirror hit its {}-page ceiling after {} contacts; the rest arrives next "
				+ "pass. If this repeats, the cursor is not advancing.", MAX_PAGES, seen);
		return new Result(seen, pages, locationTotal, false);
	}
}
