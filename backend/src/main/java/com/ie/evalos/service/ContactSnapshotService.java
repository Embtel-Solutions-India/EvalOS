package com.ie.evalos.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientType;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.SourceChannel;
import com.ie.evalos.integration.GhlContactClient;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.repository.ContactSnapshotRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds or updates the CRM row for one person — Unit 44, slice C.
 *
 * <p><strong>Extracted from {@code CaseIntakeService}, and the extraction is the point rather than
 * tidiness.</strong> {@code 00d} §5.4: the {@code contact.created} / {@code contact.updated}
 * webhook handlers "must not be written through {@code CaseIntakeService}" — {@code 05b} rules that
 * {@code contact.created} <em>"must not route to intake"</em> and
 * {@code DomainInvariantsTest} <strong>enforces it structurally</strong> by permitting exactly
 * <em>one</em> injector of {@code CaseIntakeService} on the whole classpath. A second handler
 * reaching for the matching logic would therefore fail the build, correctly — that is invariant 8
 * working. So the logic moves here first, and both callers use it.
 *
 * <p><strong>The matching rules came with it unchanged, and they are load-bearing.</strong> Both
 * lookups run, in order of authority, and <em>not</em> one or the other:
 *
 * <ul>
 * <li><strong>GHL id first.</strong> It is the identity GHL owns (invariant 7).</li>
 * <li><strong>Then email, but only when it does not contradict.</strong> Two distinct GHL contacts
 * can share an inbox — a firm's office address is the obvious case — and without
 * {@link #contradicts} the second one's delivery matched the first one's row, could not backfill
 * its own id over the id already there, and quietly attached a paid case to <em>the wrong
 * client</em>. A wrong merge is worse than a duplicate: the duplicate is visible and fixable, the
 * merge looks like a normal case.</li>
 * <li><strong>Backfill the id onto whatever was found</strong>, or every later delivery re-matches
 * by email — which works until the email changes, and then it is a second contact again.</li>
 * </ul>
 *
 * <p><strong>This is still {@code contact_snapshot}, and the name is the only thing wrong with
 * it.</strong> {@code 00c} §2 calls the mirror's table {@code contact}; renaming is blocked because
 * two seeds write this table and run after every migration — see {@code V55}'s comment. It is the
 * mirror's contact table in every way that matters.
 */
@Service
public class ContactSnapshotService {

	/**
	 * Everything EvalOS is told about a person, from whichever side told it.
	 *
	 * <p>Deliberately not {@code CaseIntakeService.ContactDetails}: that record is a webhook
	 * payload's shape and belongs to the handoff that receives it. This one is the service's own
	 * vocabulary, so a second caller — the portal sign-up, and Unit 45's contact webhook — is not
	 * made to speak Handoff A's language to say who somebody is.
	 */
	public record Details(String ghlContactId, String fullName, String email, String phone,
			String company, ClientType clientType, SourceChannel sourceChannel, String utmSource,
			String utmMedium, String utmCampaign) {

		/** What the portal knows at sign-up: a name, an address, a phone, and GHL's id. */
		public static Details fromSignUp(String ghlContactId, String fullName, String email, String phone) {
			return new Details(ghlContactId, fullName, email, phone, null, null, null, null, null, null);
		}
	}

	private static final Logger log = LoggerFactory.getLogger(ContactSnapshotService.class);

	private final ContactSnapshotRepository contacts;
	private final GhlContactClient ghlContacts;

	ContactSnapshotService(ContactSnapshotRepository contacts, GhlContactClient ghlContacts) {
		this.contacts = contacts;
		this.ghlContacts = ghlContacts;
	}

	/**
	 * The row for this person, created if EvalOS has never seen them.
	 *
	 * <p><strong>This is what closes the prospect gap</strong> ({@code 00d} §5.4): before Unit 44c
	 * the only writer was Handoff A, so {@code contact_snapshot} held <em>only contacts that won an
	 * opportunity</em> — every prospect, and every lead Marketing opened this month, was unknown to
	 * the portal. A client signing up now has a row from the first moment.
	 */
	@Transactional
	public ContactSnapshot findOrCreate(UUID brandId, Details details) {
		ContactSnapshot contact = existing(brandId, details)
				.orElseGet(() -> new ContactSnapshot(brandId, details.ghlContactId()));
		contact.linkGhlContact(details.ghlContactId());
		contact.syncFromGhl(details.fullName(), details.email(), details.phone(), details.company(),
				details.clientType(), details.sourceChannel(), details.utmSource(), details.utmMedium(),
				details.utmCampaign());
		return contacts.save(contact);
	}

	/**
	 * The mirror's row for this GHL contact, <strong>fetched from GHL and kept if the mirror has
	 * never seen them</strong>.
	 *
	 * <p><strong>Why a fallback exists at all.</strong> Every writer above is an EvalOS-side
	 * event — a won opportunity, a portal sign-up, a contact webhook. A deal a salesperson typed
	 * straight into GHL fires none of them: {@code OpportunityMirrorService} stores the contact
	 * <em>id</em> on the deal and stops, so the deal screen showed "no contact on this deal yet —
	 * it arrives with the next sync" forever. No sweep was coming. This is the read that closes
	 * that, and it writes what it finds, so the gap closes for good rather than once per page view.
	 *
	 * <p><strong>The mirror is still the source, and that ordering is the whole design.</strong>
	 * A row already held is returned without touching GHL — which keeps the deal screen working
	 * with the sync off, the property Unit 48 has to be able to claim, and keeps a busy pipeline
	 * from spending its rate limit re-reading contacts it already has.
	 *
	 * <p><strong>A GHL failure is not this caller's problem.</strong> It returns empty and logs,
	 * rather than throwing: the screen's honest state for "we do not hold this person" already
	 * exists and already renders, and turning an upstream blip into a 502 would take the notes,
	 * the questionnaire and the actions down with the contact card. The one thing it must not do
	 * is report success with nothing, which is why the caller gets an {@code Optional} and not a
	 * half-filled row.
	 */
	@Transactional
	public Optional<ContactSnapshot> findOrFetch(UUID brandId, String ghlContactId) {
		if (ghlContactId == null || ghlContactId.isBlank()) {
			return Optional.empty();
		}
		return byGhlContactId(brandId, ghlContactId)
				.or(() -> fetchFromGhl(brandId, ghlContactId));
	}

	private Optional<ContactSnapshot> fetchFromGhl(UUID brandId, String ghlContactId) {
		try {
			GhlContactClient.Contact fromGhl = ghlContacts.byId(ghlContactId);
			// Through findOrCreate rather than a save here, so the email-match and
			// contradiction rules above still apply: a contact the mirror holds under a
			// different id must not become a second row just because this path found it first.
			return Optional.of(findOrCreate(brandId, new Details(fromGhl.id(), fromGhl.name(),
					fromGhl.email(), fromGhl.phone(), fromGhl.company(), null, null, null, null, null)));
		}
		catch (GhlUnavailableException unavailable) {
			// Logged at warn with the id, because a contact that never resolves is a screen a
			// salesperson reports as broken and this is the line that explains it.
			log.warn("Could not read GHL contact {} for brand {}; the deal screen shows no contact",
					ghlContactId, brandId, unavailable);
			return Optional.empty();
		}
	}

	/** Both lookups, in order of authority — see the class note for why it is not one or the other. */
	private Optional<ContactSnapshot> existing(UUID brandId, Details details) {
		return byGhlContactId(brandId, details.ghlContactId())
				.or(() -> byEmail(brandId, details.email(), details.ghlContactId()));
	}

	/**
	 * An email match that names a different client.
	 *
	 * <p>Only a genuine conflict counts — <strong>both</strong> ids present and different — so the
	 * two cases the fall-through exists for still match: a row with no id yet (it gets backfilled),
	 * and a delivery with no id to assert. Rejecting sends the caller down the create path, which
	 * {@code V27} is what lets land: the email uniqueness index applies only to rows without a GHL
	 * id, because a row that has one does not need email to tell it apart.
	 */
	private static boolean contradicts(ContactSnapshot match, String incomingGhlContactId) {
		String held = match.getGhlContactId();
		return held != null && !held.isBlank()
				&& incomingGhlContactId != null && !incomingGhlContactId.isBlank()
				&& !held.equals(incomingGhlContactId);
	}

	private Optional<ContactSnapshot> byGhlContactId(UUID brandId, String ghlContactId) {
		return ghlContactId == null || ghlContactId.isBlank()
				? Optional.empty()
				: contacts.findByBrandIdAndGhlContactId(brandId, ghlContactId);
	}

	/**
	 * The one contact at this address that this delivery could be, or nothing.
	 *
	 * <p><strong>An address can name several contacts, and that is the whole difficulty.</strong>
	 * {@code uq_contact_per_brand_email} does not cover rows carrying a GHL id, so a firm's office
	 * inbox legitimately appears on two mirrored people. This used to take an {@code Optional} from
	 * the repository and threw the first time a real mirror pass met one.
	 *
	 * <p><strong>Ambiguity resolves to "create", never to "pick one".</strong> Contradicting
	 * matches are dropped first — that is the existing rule, unchanged. If <em>exactly one</em>
	 * candidate survives, it is the match. If several do, this returns empty and the caller creates
	 * a row, because the class's founding trade applies exactly here: a wrong merge attaches a paid
	 * case to the wrong client and looks like a normal case, while a duplicate is visible and
	 * fixable. Choosing the first by id would be picking one, and the id is arbitrary.
	 */
	private Optional<ContactSnapshot> byEmail(UUID brandId, String email, String incomingGhlContactId) {
		if (email == null || email.isBlank()) {
			return Optional.empty();
		}
		List<ContactSnapshot> candidates = contacts.findByBrandIdAndEmailIgnoreCase(brandId, email)
				.stream()
				.filter((match) -> !contradicts(match, incomingGhlContactId))
				.toList();

		if (candidates.size() > 1) {
			log.warn("{} contacts in brand {} share an address and none carries the incoming GHL "
					+ "id; creating rather than guessing which one this is", candidates.size(), brandId);
			return Optional.empty();
		}
		return candidates.stream().findFirst();
	}

}
