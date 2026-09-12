package com.ie.evalos.service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.AmbiguousCaseException;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.CaseDocument;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.DocumentKind;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.PayoutPayment;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.VisaCategory;
import com.ie.evalos.integration.DocumentStore;
import com.ie.evalos.repository.CaseDocumentRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.PayoutPaymentRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.PortalPrincipal;

import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an expert may see of the case they were sent, the three answers they can give, and the
 * signature itself (Unit 15).
 *
 * <p><strong>The expert's own whitelist, beside the client's, never merged with it.</strong>
 * {@link PortalCaseService} projects what a client may see; this projects what an expert may see,
 * and the two lists differ in both directions — the expert sees the goal and the evidence supplied,
 * the client does not; the client sees the approval state, the expert does not. One record serving
 * both audiences would be one record somebody widens for one of them.
 *
 * <p><strong>The token is the scope.</strong> No method here takes a case id: the case comes off
 * {@link PortalPrincipal}, which came off the token's own row. One token names one case, so an
 * expert holding three cases holds three links, each revocable on its own, and there is no expert
 * account, password or session to secure.
 *
 * <p><strong>There is no signature provider and there is not going to be one here.</strong> The
 * expert downloads the letter, signs it however they already sign things, and uploads it back.
 * What replaces a tamper-evident certificate is stated plainly in the spec and implemented in
 * {@link #uploadSignedLetter}: the hash of what came back, a contemporaneous attestation naming a
 * person, and an audit row whose actor is the <strong>expert</strong> rather than a staff member
 * recording a claim about one.
 */
@Service
public class ExpertPortalService {

	/**
	 * The whole expert-facing view of a case.
	 *
	 * <p>Deliberately absent, each because it is somebody else's: {@code deal_value},
	 * {@code invoice_ref}, {@code campaign_attribution}, {@code pm_strategy_notes} (the PM's
	 * framing is instructions to the Case Manager, not to the expert), the expert selection
	 * rationale, every assignment field, the audit timeline, the client's own uploaded documents,
	 * and every other case.
	 *
	 * @param applicantName  who the letter is about. Cannot be withheld — the expert is putting
	 *                       their name to a document about a named person
	 * @param serviceType    with {@code visaCategory}, the stated goal: what this letter has to
	 *                       achieve
	 * @param draftLink      where the letter is. Null until a draft exists, which the portal reads
	 *                       as "not ready" rather than substituting something else
	 * @param evidence       the labels of the checklist items the client has actually supplied —
	 *                       what the opinion rests on. Outstanding items are not listed: what the
	 *                       client still owes is the Coordinator's business
	 * @param awaitingAnswer whether the three answers are live, stated by the server rather than
	 *                       derived in the browser from a stage
	 * @param onHold         true while the case waits on the client for evidence this expert
	 *                       asked for — which is also why they cannot sign yet
	 * @param attestation    the exact wording the expert must tick to upload, composed here so the
	 *                       sentence stored as evidence is the sentence the server asked for
	 */
	public record ExpertCaseView(
			String caseReference,
			String applicantName,
			String expertName,
			ServiceType serviceType,
			VisaCategory visaCategory,
			String draftLink,
			List<String> evidence,
			ExpertSignStatus signStatus,
			SlaStatus signSla,
			boolean awaitingAnswer,
			boolean onHold,
			boolean signed,
			Instant signedAt,
			String attestation) {
	}

	/**
	 * One row of "my assignments" (Unit 35, D1 + D5).
	 *
	 * <p>Deliberately thinner than {@link ExpertCaseView}: no applicant name, no draft link, no
	 * attestation. A list is read before a case is chosen, and the applicant's identity is the
	 * client's, disclosed to the expert only on the case they are working — not across every case
	 * they have ever been offered.
	 *
	 * @param step           D5's projected label, or null before the case reaches this expert
	 * @param actionRequired whether this case is waiting on the expert to sign
	 */
	public record ExpertCaseSummary(
			UUID caseId,
			String caseReference,
			ServiceType serviceType,
			ExpertSignStatus signStatus,
			String step,
			boolean actionRequired) {
	}

	/**
	 * One payout row as its expert sees it (Unit 35, D6).
	 *
	 * <p><strong>The field list is the whole point of this record.</strong> Invariant 4 says
	 * {@code payment_detail} has no read path anywhere in EvalOS — not for the ENM who typed it,
	 * not here. This is a new surface onto money, so it is a named whitelist rather than a
	 * projection of the entity, and {@code ExpertPortalServiceTest} serializes it and asserts the
	 * secret is absent. A record that merely happens not to include the field today is one an
	 * innocent-looking widening breaks; a record with a test on its serialized form is not.
	 *
	 * @param settledOn the payment's paid date, null while the row is still owed — the one fact an
	 *                  expert most wants and the ledger alone cannot answer
	 */
	public record ExpertPayoutRow(
			String caseReference,
			BigDecimal amount,
			String currency,
			PayoutStatus status,
			Instant settledOn) {
	}

	/** What the expert gets back after signing. No object key — that is an internal address. */
	public record SignedLetterView(UUID documentId, String filename, int version, Instant signedAt,
			String contentSha256) {
	}

	/**
	 * The wording, and it lives on the server because it is the evidence.
	 *
	 * <p>A checkbox whose sentence the browser composed is a sentence nobody can prove was shown.
	 * The API refuses an attestation that is not this text (see {@link #uploadSignedLetter}), so
	 * what is stored is what was asked.
	 */
	static String attestationFor(String name) {
		return "I, %s, confirm this is my signature on this letter.".formatted(name);
	}

	private final CaseRepository cases;
	private final ContactSnapshotRepository contacts;
	private final ExpertRepository experts;
	private final PayoutLedgerRepository payouts;
	private final PayoutPaymentRepository payments;
	private final DocumentChecklistItemRepository checklistItems;
	private final CaseDocumentRepository documents;
	private final CaseLifecycleService lifecycle;
	private final SlaCalculator sla;
	private final DocumentStore store;
	private final AuditService audit;

	ExpertPortalService(CaseRepository cases, ContactSnapshotRepository contacts, ExpertRepository experts,
			PayoutLedgerRepository payouts, PayoutPaymentRepository payments,
			DocumentChecklistItemRepository checklistItems, CaseDocumentRepository documents,
			CaseLifecycleService lifecycle, SlaCalculator sla, DocumentStore store, AuditService audit) {
		this.cases = cases;
		this.contacts = contacts;
		this.experts = experts;
		this.payouts = payouts;
		this.payments = payments;
		this.checklistItems = checklistItems;
		this.documents = documents;
		this.lifecycle = lifecycle;
		this.sla = sla;
		this.store = store;
		this.audit = audit;
	}

	// --- the one screen ------------------------------------------------------

	/**
	 * The expert's screen, and the read receipt.
	 *
	 * <p>{@code expert_portal_read_at} is stamped <strong>once</strong>: it answers "has the expert
	 * opened this at all", which is what the Case Manager needs before chasing, and it is what
	 * replaces the signature provider's viewed callback. "When did they last look" is
	 * {@code portal_access.last_seen_at}, which moves on every request.
	 */
	@Transactional
	public ExpertCaseView view(PortalPrincipal principal) {
		Case subject = authorized(principal);

		if (subject.getExpertPortalReadAt() == null) {
			subject.setExpertPortalReadAt(Instant.now());
			cases.save(subject);
		}
		return project(subject);
	}

	private ExpertCaseView project(Case subject) {
		// Both lookups are by an id that came off the authorized case — the same provenance the
		// batched staff finders rely on, and neither id ever arrives from a request.
		String expertName = Optional.ofNullable(subject.getExpertId())
				.flatMap(experts::findById)
				.map(Expert::getFullName)
				.orElse(null);

		// The applicant is the person the letter is about; on an individual case that is the
		// contact, and on a firm's case it is not. Falling back keeps the expert from signing a
		// letter about "somebody".
		String applicant = subject.getApplicantName() != null ? subject.getApplicantName()
				: Optional.ofNullable(subject.getContactId())
						.flatMap(contacts::findById)
						.map(contact -> contact.getFullName())
						.orElse(null);

		List<String> evidence = checklistItems.findByCaseId(subject.getId()).stream()
				.filter(item -> item.getStatus().isComplete())
				.map(DocumentChecklistItem::getLabel)
				.toList();

		Optional<CaseDocument> signedLetter = signedLetter(subject);

		return new ExpertCaseView(
				subject.getCaseCode(),
				applicant,
				expertName,
				subject.getServiceType(),
				subject.getVisaCategory(),
				subject.getDraftLink(),
				evidence,
				subject.getExpertSignStatus(),
				sla.statusOf(subject),
				subject.getExceptionState() == ExceptionState.NONE
						&& subject.getExpertSignStatus() != ExpertSignStatus.SIGNED,
				subject.getExceptionState() == ExceptionState.ON_HOLD_AWAITING_CLIENT,
				signedLetter.isPresent(),
				signedLetter.map(CaseDocument::getUploadedAt).orElse(null),
				attestationFor(expertName == null ? "the assigned expert" : expertName));
	}

	// --- the three answers ---------------------------------------------------

	/** The expert takes the case. Idempotent on a second click; 409 on an offer that is over. */
	/**
	 * Every case this expert is on, newest first (Unit 35, D1).
	 *
	 * <p>Refused for a case-scoped token, like the client's list and for the same reason: the
	 * narrow credential does not get the wide one's reply.
	 */
	@Transactional(readOnly = true)
	public List<ExpertCaseSummary> expertCases(PortalPrincipal principal) {
		if (!principal.isPartyScoped()) {
			throw new ForbiddenException("This link admits you to one case, not a list");
		}
		return partyCases(principal).stream().map(subject -> {
			PortalStageProjection.PortalStep step = PortalStageProjection.forExpert(subject.getCurrentStage());
			return new ExpertCaseSummary(subject.getId(), subject.getCaseCode(), subject.getServiceType(),
					subject.getExpertSignStatus(),
					step == null ? null : step.label(),
					step != null && step.actionRequired());
		}).toList();
	}

	/** One of this expert's cases, named by the caller and checked against the credential. */
	@Transactional
	public ExpertCaseView view(PortalPrincipal principal, UUID caseId) {
		return project(authorized(principal, caseId));
	}

	/**
	 * This expert's payout rows (Unit 35, D6), newest first.
	 *
	 * <p>Answers for both token shapes: a payout belongs to the expert, not to a case, so a
	 * case-scoped token names its expert just as well as a party one does ({@code V37} put the
	 * expert on the row precisely so it could). The ledger is read for that expert in the token's
	 * brand and nowhere wider.
	 *
	 * <p>The settlement date is a second read rather than a join, because {@code payment_id} is
	 * null on most rows and a join would make the common case pay for the rare one.
	 */
	@Transactional(readOnly = true)
	public List<ExpertPayoutRow> payoutRows(PortalPrincipal principal) {
		UUID expertId = principal.expertId();
		if (expertId == null) {
			// V37's fail-closed rule: a token that names no expert is refused, not widened.
			throw new ForbiddenException("This link no longer points at an expert");
		}
		return payouts.findByBrandIdAndExpertIdOrderByCreatedAtDesc(principal.brandId(), expertId).stream()
				.map(row -> new ExpertPayoutRow(
						cases.findById(row.getCaseId()).map(Case::getCaseCode).orElse(null),
						row.getAmount(),
						row.getCurrency(),
						row.getStatus(),
						row.getPaymentId() == null ? null
								: payments.findById(row.getPaymentId())
										.map(PayoutPayment::getPaidDate)
										.orElse(null)))
				.toList();
	}

	@Transactional
	public ExpertCaseView accept(PortalPrincipal principal) {
		return project(lifecycle.expertAcceptedFromPortal(authorized(principal)));
	}

	/** Not until the client sends this. Opens a required checklist item and holds the case. */
	@Transactional
	public ExpertCaseView requestEvidence(PortalPrincipal principal, String missing) {
		return project(lifecycle.expertRequestEvidenceFromPortal(authorized(principal), missing));
	}

	/** The expert will not take it. The reason is required — it is what the rematch works from. */
	@Transactional
	public ExpertCaseView decline(PortalPrincipal principal, String reason) {
		return project(lifecycle.expertDeclinedFromPortal(authorized(principal), reason));
	}

	// --- the letter, out and back --------------------------------------------

	/**
	 * Where the letter is.
	 *
	 * <p><strong>A link, not bytes, and that is a real limitation rather than a design.</strong>
	 * {@code draft_link} is free text a Case Manager pastes — the draft is not an object in S3
	 * today (Unit 34 §9.5) — so EvalOS hands over the link it holds and <em>cannot hash what it
	 * sent</em>. The spec's {@code letter_sent_hash} is therefore not implemented and is not a
	 * column: half the hash pair is missing, which is stated here rather than mocked up with a
	 * value that would prove nothing. When a draft becomes an object, this method presigns it and
	 * the hash follows.
	 */
	@Transactional
	public String letterLink(PortalPrincipal principal) {
		Case subject = authorized(principal);
		String link = subject.getDraftLink();
		if (link == null || link.isBlank()) {
			throw new IllegalTransitionException("there is no letter on this case yet");
		}

		audit.recordPortalEvent(subject.getBrandId(), PortalAudience.EXPERT, "CASE", subject.getId(),
				AuditAction.EXPORTED, null, Map.of("opened", "the letter for signing"));
		return link;
	}

	/**
	 * The signature: the expert uploads the letter they signed (Unit 15, on Unit 30's store).
	 *
	 * <p><strong>Object first, row second, transition last, and the order is the whole design.</strong>
	 * A store failure answers 503 with nothing recorded and the case still {@code EXPERT_SIGNING},
	 * unsigned — a visible, recoverable state the board already shows. The reverse order marks a
	 * case signed with no letter behind it, which is the failure nobody notices until QC.
	 *
	 * <p><strong>PDF only, and by content.</strong> The caller sniffs the magic bytes before this
	 * is reached; a {@code .pdf}-named JPEG does not become a signed letter. Narrower than the
	 * client's allowlist deliberately — JPEG and PNG are fine for a client's supporting document
	 * and are not fine for the deliverable.
	 *
	 * <p><strong>The attestation is refused when absent, whatever the UI does</strong> — it is the
	 * evidence, so it cannot be a checkbox the API accepts without.
	 *
	 * <p><strong>The name on the attestation is the case's own expert, and the request cannot say
	 * otherwise.</strong> An earlier version took the name as a parameter and checked the sentence
	 * against it, which proved only that the caller could spell their own claim twice: any
	 * consistent pair passed, and the row this class calls the evidence could name somebody who was
	 * never on the case. The name is now read from {@code expert.full_name} for the expert this case
	 * names, and the submitted sentence must match the one composed from it.
	 *
	 * @param body a source the bytes can be read from <strong>twice</strong> — once to hash, once to
	 *             store; see below for why that is not one pass
	 */
	@Transactional
	public SignedLetterView uploadSignedLetter(PortalPrincipal principal, String filename, long size,
			InputStreamSource body, String attestation) {

		Case subject = authorized(principal);
		requireGiven(attestation, "the attestation must be ticked before the letter can be uploaded");

		// Whose signature this is, according to EvalOS rather than according to the request.
		String signer = Optional.ofNullable(subject.getExpertId())
				.flatMap(experts::findById)
				.map(Expert::getFullName)
				.filter(name -> !name.isBlank())
				// Reachable only when the roster row itself is gone or nameless: `authorized` has
				// already proved the token's expert is the case's expert, and both are non-null.
				.orElseThrow(() -> new IllegalTransitionException(
						"this case has no named expert on file, so there is nobody whose signature this could be"));

		String expected = attestationFor(signer);
		if (!expected.equals(attestation.strip())) {
			// Not pedantry: what is stored has to be the sentence the server asked for, or the
			// record is a sentence the uploader composed about themselves.
			throw new InvalidRequestException("the attestation must read exactly: " + expected);
		}

		// The transition is checked BEFORE the object is written, so a case that cannot legally be
		// signed — one held awaiting the client's evidence, one already rematched — does not leave
		// an orphaned object behind for a 409.
		CaseTransitions.target(subject, CaseTransitions.Action.EXPERT_SIGNED);

		UUID documentId = UUID.randomUUID();
		String key = DocumentStore.caseKey(subject.getBrandId(), subject.getId(), "signed", documentId);

		// **Two passes over the part, and the second one is the store's.** Wrapping the store's own
		// read in a DigestInputStream was a pass shorter and was wrong: the S3 SDK resets and
		// re-reads a mark-supporting stream on a retry, and a reset resets neither the digest nor a
		// byte count — so one transient retry recorded a digest of the file twice over. Hashing
		// first is its own read, cannot be corrupted by a retry, and still buffers nothing: the
		// digest consumes the stream in chunks and keeps 32 bytes.
		String sha256 = hashOf(body);
		try (InputStream stream = body.getInputStream()) {
			store.put(key, stream, size, "application/pdf");
		}
		catch (java.io.IOException ex) {
			// The part could not be re-read. Nothing is recorded, so the case is untouched — the
			// same guarantee a store failure gives. `DocumentStoreUnavailableException` is a
			// runtime exception and passes straight through this catch, as it must.
			throw new InvalidRequestException("that file could not be read. Nothing was saved - try again.");
		}

		CaseDocument letter = new CaseDocument(subject.getBrandId(), subject.getId(),
				DocumentKind.SIGNED_LETTER, nextVersion(subject.getId()), null, ActorType.EXPERT, null);
		letter.setObjectKey(key);
		letter.setFilename(filename == null || filename.isBlank() ? "signed-letter.pdf" : filename);
		letter.attested(sha256, attestation.strip(), signer);
		letter = documents.save(letter);

		// EXPERT_SIGNED stamps the offer ACCEPTED only if it is still OFFERED — an expert who
		// pressed Accept and then uploaded produces two writes of one outcome on the happy path,
		// and `ExpertCaseOffer.resolve` makes the second a no-op rather than an error.
		Case signed = lifecycle.expertSignedFromPortal(subject);

		// The snapshot names the file, the hash and the attestation — the three facts a dispute
		// turns on — and never the object key, which is an internal address.
		audit.recordPortalEvent(signed.getBrandId(), PortalAudience.EXPERT, "CASE_DOCUMENT",
				letter.getId(), AuditAction.CREATED, null,
				Map.of("filename", String.valueOf(letter.getFilename()),
						"sha256", String.valueOf(letter.getContentSha256()),
						"attestation", letter.getAttestation(),
						"attestedName", letter.getAttestedName()));

		return new SignedLetterView(letter.getId(), letter.getFilename(), letter.getVersion(),
				letter.getUploadedAt(), letter.getContentSha256());
	}

	// --- plumbing ------------------------------------------------------------

	/**
	 * The next signed-letter version on this case.
	 *
	 * <p>Normally 1. It is not always 1 because a failed final QC (V31's {@code PM_QC_FAIL}) sends
	 * the letter back to the Case Manager, and what comes back after that is a second signature on
	 * a second draft. Overwriting the first would destroy the evidence for the case that most needs
	 * it. The race is closed by {@code uq_case_document_version}, as it is for client uploads.
	 */
	private int nextVersion(UUID caseId) {
		return documents.findFirstByCaseIdAndKindOrderByVersionDesc(caseId, DocumentKind.SIGNED_LETTER)
				.map(latest -> latest.getVersion() + 1)
				.orElse(1);
	}

	private Optional<CaseDocument> signedLetter(Case subject) {
		return documents.findFirstByCaseIdAndKindOrderByVersionDesc(subject.getId(), DocumentKind.SIGNED_LETTER);
	}

	private static void requireGiven(String value, String why) {
		if (value == null || value.isBlank()) {
			throw new InvalidRequestException(why);
		}
	}

	/**
	 * The hash of what arrived. Half of a provenance chain, and the half EvalOS holds.
	 *
	 * <p>Streamed in 8 KB chunks and never accumulated, so a signed letter of any size costs the
	 * digest's own 32 bytes — invariant 14's "streams through, never held" applies to the hashing
	 * pass as much as to the storing one.
	 */
	private static String hashOf(InputStreamSource body) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 is mandated by the platform; unreachable.
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
		try (InputStream stream = body.getInputStream()) {
			byte[] buffer = new byte[8192];
			for (int read = stream.read(buffer); read > 0; read = stream.read(buffer)) {
				digest.update(buffer, 0, read);
			}
		}
		catch (java.io.IOException ex) {
			// Nothing is written at this point, so the case is untouched — the same guarantee a
			// store failure gives, for the same reason.
			throw new InvalidRequestException("that file could not be read. Nothing was saved - try again.");
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * The case this token admits, <strong>and the expert it admits them as</strong>.
	 *
	 * <p>{@code findById} and not {@code findScoped}, the same one deliberate exception
	 * {@link PortalCaseService} takes: the id is not a parameter a caller supplied, it is the row
	 * the credential names. The brand check is what makes brand isolation a predicate here rather
	 * than an argument from provenance.
	 *
	 * <p><strong>The expert check is the one the review added, and it is structural where the
	 * revocations are remembered.</strong> A {@code portal_access} row used to name a case and an
	 * audience only, so this method could not tell one expert's token from another's: A declines, the
	 * case is rematched to B and sent, and A's month-long link still accepted, held, declined or
	 * <em>uploaded the deliverable</em> against a case naming B.
	 * {@code CaseLifecycleService.revokeExpertLink} closes every path that exists today; this closes
	 * the ones nobody has written yet, because a mismatch is refused whether or not anything
	 * remembered to revoke.
	 *
	 * <p><strong>It fails closed on a null.</strong> A token minted before V37 carries no expert and
	 * is refused rather than waved through — one re-mint, and the safe direction to be wrong in.
	 * Every refusal here is the same message, for the reason the 401 is: which of the three it was
	 * is not the holder's business.
	 */
	private Case authorized(PortalPrincipal principal) {
		if (principal.isPartyScoped()) {
			List<Case> mine = partyCases(principal);
			if (mine.size() == 1) {
				return mine.get(0);
			}
			throw new AmbiguousCaseException(mine.isEmpty()
					? "This link has no cases behind it"
					: "You have several cases — say which one");
		}
		return authorized(principal, principal.caseId());
	}

	/**
	 * The same check for a case the caller named.
	 *
	 * <p><strong>The expert side needs no new rule for this.</strong> {@code V37} already binds a
	 * token to an expert and this method already refused a case whose expert is somebody else — a
	 * check written to stop a stale token outliving a rematch, which turns out to be exactly the
	 * party check as well. A case-scoped token is additionally pinned to its own case, so the
	 * narrow credential cannot reach a sibling case just because a path variable now exists.
	 */
	private Case authorized(PortalPrincipal principal, UUID caseId) {
		Case subject = cases.findById(caseId)
				.orElseThrow(() -> new ForbiddenException("This link no longer points at a case"));
		if (!subject.getBrandId().equals(principal.brandId())) {
			throw new ForbiddenException("This link no longer points at a case");
		}
		if (principal.expertId() == null || !principal.expertId().equals(subject.getExpertId())) {
			throw new ForbiddenException("This link no longer points at a case");
		}
		if (!principal.isPartyScoped() && !caseId.equals(principal.caseId())) {
			throw new ForbiddenException("This link no longer points at a case");
		}
		return subject;
	}

	/** The cases behind an expert party token, in the token's own brand. */
	private List<Case> partyCases(PortalPrincipal principal) {
		if (principal.expertId() == null) {
			throw new ForbiddenException("This link no longer points at an expert");
		}
		return cases.findByBrandIdAndExpertIdOrderByCreatedAtDesc(principal.brandId(), principal.expertId());
	}
}
