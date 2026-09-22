package com.ie.evalos.web;

import java.time.Instant;
import java.util.Optional;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.GhlReference;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.repository.GhlUserRepository;
import com.ie.evalos.service.ContactSnapshotService;
import com.ie.evalos.service.OpportunityMirrorService;
import com.ie.evalos.service.PipelineScope;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the deal is with — the contact half of the opportunity screen.
 *
 * <p><strong>Why this is its own read rather than fields on the board.</strong> The board draws
 * hundreds of cards; a card carries the contact <em>id</em> and nothing else, deliberately, because
 * putting names, emails and phone numbers on every card would ship a page of client PII to draw a
 * column of headings. One deal opened is one contact read.
 *
 * <p><strong>The mirror first, GHL only when the mirror has never seen them.</strong>
 * {@code contact_snapshot} is the mirror's contact table (Unit 44c) and is still the source: a row
 * already held is returned without touching GHL, so this screen keeps working when the sync is off
 * — the property Unit 48 has to be able to claim.
 *
 * <p><strong>This paragraph used to read "from the mirror, never from GHL", and that was a
 * promise the data could not keep.</strong> Every writer of {@code contact_snapshot} is an
 * EvalOS-side event — Handoff A, a portal sign-up, a contact webhook — while
 * {@code OpportunityMirrorService} stores the contact <em>id</em> on the deal and nothing more. A
 * deal a salesperson typed into GHL therefore had a contact id and no contact row, and the screen
 * said "it arrives with the next sync" indefinitely, because no sweep pulls contacts.
 * {@code ContactSnapshotService.findOrFetch} closes that and keeps what it reads, so the second
 * open is a mirror read again.
 *
 * <p>A contact GHL cannot be asked about — an outage, a missing grant, an id GHL no longer holds —
 * still answers 200 with nothing, for the same reason the application read does: a deal with no
 * contact is an ordinary state, and a 404 would teach the screen to read failure as normal.
 *
 * <p><strong>Pipeline-scoped, like every other write and read on a deal.</strong>
 * {@code requireMine} is what stops a salesperson reading the contact on another desk's deal by
 * pasting its id — the same gate {@code SalesDeskService} applies before it edits one.
 */
@RestController
@RequestMapping("/api/opportunities/{opportunityId}/contact")
public class OpportunityContactController {

	/**
	 * Everything the deal screen's details panel shows.
	 *
	 * <p><strong>Four of these are the contact's and three are the deal's, and that mix is the
	 * panel rather than a leak.</strong> The screen draws one card headed "Contact details" and it
	 * holds who the person is <em>and</em> where the deal came from, who is on it and when it
	 * opened. Splitting it across two routes would be two round trips to fill one card, and putting
	 * the deal's three on the board payload would carry them on all 1,500 cards to serve the one
	 * that is open.
	 *
	 * <p>No GHL id of any kind: the screen has no use for one, and {@code assignedTo} is resolved
	 * to a <em>name</em> here precisely so the raw user id never reaches the browser.
	 *
	 * @param source     where GHL says the person came from, or null. The contact's channel rather
	 *                   than {@code opportunity.source}: the card is about the person, and a repeat
	 *                   client's second deal does not change how they first arrived
	 * @param assignedTo the GHL user's display name, or null when the deal is unassigned or the
	 *                   {@code ghl_user} mirror has not seen that id. Both render the same and
	 *                   should: "nobody is on this" and "we cannot name who is" are equally
	 *                   actionable to a salesperson looking at their own board
	 * @param createdAt  when GHL opened the deal, not when EvalOS mirrored it
	 */
	public record ContactView(String name, String email, String phone, String company,
			String source, String assignedTo, Instant createdAt) {
	}

	private final PipelineScope scope;
	private final OpportunityMirrorService deals;
	private final ContactSnapshotService contacts;
	private final GhlUserRepository ghlUsers;

	OpportunityContactController(PipelineScope scope, OpportunityMirrorService deals,
			ContactSnapshotService contacts, GhlUserRepository ghlUsers) {
		this.scope = scope;
		this.deals = deals;
		this.contacts = contacts;
		this.ghlUsers = ghlUsers;
	}

	@GetMapping
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING', 'GM')")
	public ApiResponse<ContactView> read(@PathVariable String opportunityId) {
		// `requireVisible` returns the deal it authorised, so this is one lookup rather than two —
		// and it is the read-side check, which a GM and a Brand Manager pass. `requireMine` refused
		// both here: a GM holds no pipelines by design, so the contact card on a deal their own
		// board had listed answered "not permitted for this role, brand, or assignment".
		Optional<ContactView> found = Optional.of(scope.requireVisible(opportunityId)).map((deal) -> {
			// The contact may be absent — a deal GHL holds no contact against — while the deal's
			// own three facts are always there. So the panel is built from the deal and the
			// contact fills what it can, rather than the whole card vanishing with the person.
			ContactSnapshot contact = contactFor(deal).orElse(null);
			return new ContactView(
					contact == null ? null : contact.getFullName(),
					contact == null ? null : contact.getEmail(),
					contact == null ? null : contact.getPhone(),
					contact == null ? null : contact.getCompany(),
					contact == null || contact.getSourceChannel() == null
							? null : contact.getSourceChannel().name(),
					assigneeName(deal),
					deal.getGhlCreatedAt());
		});
		return ApiResponse.ok(found.orElse(null));
	}

	/**
	 * The assignee's name from the Unit 47 mirror, or null.
	 *
	 * <p>Brand-scoped from the deal, like every other lookup here. A miss is null rather than the
	 * raw id: an id is not a name, and showing one teaches the reader to ignore the field.
	 */
	private String assigneeName(Opportunity deal) {
		String assignedTo = deal.getGhlAssignedTo();
		if (assignedTo == null || assignedTo.isBlank()) {
			return null;
		}
		return ghlUsers.findByBrandIdAndGhlId(deal.getBrandId(), assignedTo)
				.map(GhlReference.User::getName)
				.orElse(null);
	}

	/**
	 * Brand-scoped, from the deal's own brand rather than from anything the caller sent. A lookup
	 * taking a GHL id alone is one mistyped caller away from another brand's row — the trap five
	 * finders in that package carry a comment about — and it is also what the contact this may
	 * fetch gets written under.
	 */
	private Optional<ContactSnapshot> contactFor(Opportunity deal) {
		return contacts.findOrFetch(deal.getBrandId(), deal.getGhlContactId());
	}
}
