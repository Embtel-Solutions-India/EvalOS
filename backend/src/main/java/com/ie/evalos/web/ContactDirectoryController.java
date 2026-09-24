package com.ie.evalos.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.repository.ContactDirectoryRepository;
import com.ie.evalos.service.ContactDirectoryService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The contacts list — everyone the CRM holds, at the width the caller reads.
 *
 * <p><strong>Gated twice, and the two gates answer different questions.</strong> The
 * {@code @PreAuthorize} here answers "may this role open the screen at all"; the service answers
 * "and how much of it", from {@code Role.tier()}. Neither is sufficient alone — a role list here
 * with no tier check would show a desk the whole business, and a tier check with no role list
 * would let a Case Manager reach a CRM screen that is none of their work.
 *
 * <p><strong>No id of GHL's in the payload.</strong> Rows carry EvalOS's own
 * {@code contact_snapshot.id}, which is what a future contact detail screen would be keyed on.
 * The GHL contact id is the join key and stays server-side.
 *
 * <p><strong>The brand travels on every row, and is only useful to one reader.</strong> A GM's
 * list spans brands and a row that does not say which is a row they cannot act on; every other
 * caller sees one brand and can ignore it. Sending it always is cheaper than a second shape.
 */
@RestController
@RequestMapping("/api/contacts")
public class ContactDirectoryController {

	/**
	 * One row of the list.
	 *
	 * <p>{@code dealCount} is scoped like the row itself — see
	 * {@code ContactDirectoryRepository.Row}.
	 */
	public record ContactRow(UUID id, UUID brandId, String brandName, String name, String email,
			String phone, String company, String source, long dealCount, Instant lastActivityAt) {
	}

	/**
	 * A page of the directory.
	 *
	 * @param total how many contacts are in the caller's scope after the search, not on this page.
	 *              The client needs it to draw a last page and to say how many there are; without
	 *              it a pager can only ever offer "next", which makes a roster feel bottomless
	 */
	public record ContactPage(List<ContactRow> contacts, long total, int page, int size) {
	}

	private final ContactDirectoryService directory;

	ContactDirectoryController(ContactDirectoryService directory) {
		this.directory = directory;
	}

	/**
	 * @param search optional. Matches name, email or company, case-insensitively — never the
	 *               phone, which is stored in whatever shape GHL received it
	 * @param page   zero-based. Out of range is an empty page rather than a refusal: asking for
	 *               page 40 of a 3-page list is a stale bookmark, not an error worth a 400
	 * @param size   rows per page, clamped to 100 by the repository. 15 by default, matching what
	 *               the screen draws
	 */
	@GetMapping
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'SALES', 'MARKETING')")
	public ApiResponse<ContactPage> list(
			@RequestParam(required = false) String search,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "15") int size) {

		ContactDirectoryRepository.Page found = directory.forCaller(search, page, size);
		List<ContactRow> rows = found.rows().stream()
				.map((row) -> new ContactRow(row.id(), row.brandId(), row.brandName(),
						row.fullName(), row.email(), row.phone(), row.company(),
						row.sourceChannel(), row.dealCount(), row.lastActivityAt()))
				.toList();
		// The size echoed back is the one the repository APPLIED after its own clamp — never the
		// one asked for and never the row count. A client that requested 5,000 and got 100 must be
		// told 100, and a last page of 7 out of 15 is still a page size of 15; either mistake makes
		// the client compute a page count the server does not agree with.
		return ApiResponse.ok(new ContactPage(rows, found.total(), Math.max(page, 0),
				found.appliedSize()));
	}
}
