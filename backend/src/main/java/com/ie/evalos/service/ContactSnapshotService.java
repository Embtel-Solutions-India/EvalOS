package com.ie.evalos.service;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientType;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.SourceChannel;
import com.ie.evalos.repository.ContactSnapshotRepository;

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

	private final ContactSnapshotRepository contacts;

	ContactSnapshotService(ContactSnapshotRepository contacts) {
		this.contacts = contacts;
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

	/** Both lookups, in order of authority — see the class note for why it is not one or the other. */
	private Optional<ContactSnapshot> existing(UUID brandId, Details details) {
		return byGhlContactId(brandId, details.ghlContactId())
				.or(() -> byEmail(brandId, details.email())
						.filter((match) -> !contradicts(match, details.ghlContactId())));
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

	private Optional<ContactSnapshot> byEmail(UUID brandId, String email) {
		return email == null || email.isBlank()
				? Optional.empty()
				: contacts.findByBrandIdAndEmailIgnoreCase(brandId, email);
	}

}
