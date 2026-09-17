package com.ie.evalos.web;

import java.util.Optional;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.repository.ContactSnapshotRepository;
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
 * <p><strong>From the mirror, never from GHL.</strong> {@code contact_snapshot} is the mirror's
 * contact table (Unit 44c), so this screen keeps working when the sync is off — the property Unit
 * 48 has to be able to claim. A contact the mirror has not absorbed yet answers 200 with nothing,
 * for the same reason the application read does: a deal whose contact has not synced is an ordinary
 * state, and a 404 would teach the screen to read failure as normal.
 *
 * <p><strong>Pipeline-scoped, like every other write and read on a deal.</strong>
 * {@code requireMine} is what stops a salesperson reading the contact on another desk's deal by
 * pasting its id — the same gate {@code SalesDeskService} applies before it edits one.
 */
@RestController
@RequestMapping("/api/opportunities/{opportunityId}/contact")
public class OpportunityContactController {

	/** What a desk is shown about the person. No GHL id: the screen has no use for one. */
	public record ContactView(String name, String email, String phone, String company) {
	}

	private final PipelineScope scope;
	private final OpportunityMirrorService deals;
	private final ContactSnapshotRepository contacts;

	OpportunityContactController(PipelineScope scope, OpportunityMirrorService deals,
			ContactSnapshotRepository contacts) {
		this.scope = scope;
		this.deals = deals;
		this.contacts = contacts;
	}

	@GetMapping
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING', 'GM')")
	public ApiResponse<ContactView> read(@PathVariable String opportunityId) {
		scope.requireMine(opportunityId);

		Optional<ContactView> found = deals.byGhlId(opportunityId)
				.flatMap(this::contactFor)
				.map((row) -> new ContactView(row.getFullName(), row.getEmail(), row.getPhone(),
						row.getCompany()));
		return ApiResponse.ok(found.orElse(null));
	}

	/**
	 * Brand-scoped, from the deal's own brand rather than from anything the caller sent. A finder
	 * taking a GHL id alone is one mistyped caller away from another brand's row — the trap five
	 * finders in that package carry a comment about.
	 */
	private Optional<ContactSnapshot> contactFor(Opportunity deal) {
		String ghlContactId = deal.getGhlContactId();
		return ghlContactId == null || ghlContactId.isBlank() ? Optional.empty()
				: contacts.findByBrandIdAndGhlContactId(deal.getBrandId(), ghlContactId);
	}
}
