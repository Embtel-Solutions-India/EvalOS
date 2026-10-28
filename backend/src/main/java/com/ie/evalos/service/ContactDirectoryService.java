package com.ie.evalos.service;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.ContactDirectoryRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who a caller may see in the contacts list.
 *
 * <p><strong>The three widths are the role tiers, not a new rule.</strong> {@code GM(Tier.ALL)}
 * reads every brand, {@code BRAND_MANAGER(Tier.BRAND)} reads its own, and
 * {@code SALES}/{@code MARKETING(Tier.PIPELINE)} read the contacts on the deals they work. That is
 * the same ladder {@code ScopePredicate} applies to every other scoped read in the system — this
 * class picks the arm, and {@code Role.tier()} decides which, so a role added to a tier reaches
 * the right width without anyone editing this file.
 *
 * <p><strong>It switches on the tier and not on the role</strong>, and the {@code default} throws.
 * A role that reaches this route without a tier this screen has an answer for must be refused
 * rather than silently handled: the safe direction here is a support call, not a list somebody
 * should not have seen. {@code ContactDirectoryController}'s {@code @PreAuthorize} is the first
 * gate; this is the one that decides how much.
 *
 * <p><strong>A brand-locked caller with no brand, and a desk with no pipeline, are both
 * refusals.</strong> Same fail-closed rule, same reason: an empty screen is a support call and
 * somebody else's contacts is a breach.
 */
@Service
public class ContactDirectoryService {

	private final ContactDirectoryRepository contacts;

	ContactDirectoryService(ContactDirectoryRepository contacts) {
		this.contacts = contacts;
	}

	@Transactional(readOnly = true)
	public ContactDirectoryRepository.Page forCaller(String search, int page, int size) {
		TenantContext caller = TenantContext.current();

		return switch (caller.role().tier()) {
			case ALL -> contacts.all(search, page, size);
			case BRAND, PIPELINE -> scopedToBrand(caller, search, page, size);
			default -> throw new ForbiddenException(
					"The contacts list is for the GM, a Brand Manager, and the sales and marketing "
							+ "desks. Your role reads cases, not the CRM.");
		};
	}

	/** One contact's page. {@code cases} is null for a desk: Sales and Marketing read no case (D19c). */
	public record Detail(ContactDirectoryRepository.Row contact, List<ContactDirectoryRepository.Deal> deals,
			List<ContactDirectoryRepository.CaseRow> cases) {
	}

	/**
	 * The contact page, at the same width as the list: a GM any brand, a Brand Manager their own, a
	 * desk only a contact with a deal on its pipelines (and only those deals). Anything outside that is
	 * one 403 whether or not the id exists, so the answer leaks nothing.
	 */
	@Transactional(readOnly = true)
	public Detail detail(UUID contactId) {
		TenantContext caller = TenantContext.current();
		Role.Tier tier = caller.role().tier();
		// Scoped in the query, like the list: a brand-locked caller with no brand, or a tier this page has no
		// answer for, reads nothing — the same 403 as an id outside their brand.
		java.util.Optional<ContactDirectoryRepository.Row> found = switch (tier) {
			case ALL -> contacts.contact(contactId);
			case BRAND, PIPELINE -> caller.brandId() == null ? java.util.Optional.empty()
					: contacts.contactInBrand(contactId, caller.brandId());
			default -> java.util.Optional.empty();
		};
		ContactDirectoryRepository.Row contact = found
				.orElseThrow(() -> new ForbiddenException("That contact is not one you can see."));
		if (tier == Role.Tier.PIPELINE) {
			if (caller.ghlPipelineIds().isEmpty()) {
				throw new ForbiddenException("You have no GHL pipeline assigned. A GM assigns one.");
			}
			List<ContactDirectoryRepository.Deal> mine = contacts.deals(contactId, contact.brandId(), caller.ghlPipelineIds());
			if (mine.isEmpty()) {
				throw new ForbiddenException("That contact is not one you can see.");
			}
			return new Detail(contact, mine, null);
		}
		return new Detail(contact, contacts.deals(contactId, contact.brandId(), null),
				contacts.cases(contactId, contact.brandId()));
	}

	private ContactDirectoryRepository.Page scopedToBrand(TenantContext caller, String search,
			int page, int size) {
		if (caller.brandId() == null) {
			throw new ForbiddenException("Your account has no brand. A GM sets one.");
		}
		if (caller.role().tier() == com.ie.evalos.domain.Role.Tier.BRAND) {
			return contacts.inBrand(caller.brandId(), search, page, size);
		}
		// PIPELINE. Refused rather than answered empty, matching `PipelineScope.mine()` word for
		// word: a desk with no pipeline cannot do anything here, and saying so is more use than a
		// screen that silently shows nothing.
		if (caller.ghlPipelineIds().isEmpty()) {
			throw new ForbiddenException("You have no GHL pipeline assigned. A GM assigns one.");
		}
		return contacts.onPipelines(caller.brandId(), caller.ghlPipelineIds(), search, page, size);
	}
}
