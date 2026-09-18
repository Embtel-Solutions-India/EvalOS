package com.ie.evalos.service;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.ApplicationDocument;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ClientApplication;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.integration.DocumentStore;
import com.ie.evalos.repository.ApplicationDocumentRepository;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ClientApplicationRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The documents that ride with a request — Unit 53 ({@code 53-request-documents.md}, D33/D34).
 *
 * <p><strong>Two audiences, one table, and deliberately different doors.</strong> The client
 * uploads and lists their own, proved through their portal token and their own application. Sales
 * lists and opens them through the opportunity they can already reach — {@code 53} §3: the
 * documents "ask no new authorisation question", so there is no case access anywhere in this unit
 * and no second permission to get wrong.
 *
 * <p><strong>The object is written before the row, exactly as {@code PortalCaseService.upload}
 * does it.</strong> The reverse order leaves a row pointing at nothing, which every later read has
 * to defend against; this order can leave an orphaned object, which costs storage and nothing else.
 *
 * <p><strong>A contact with no GHL id is refused before S3 is touched.</strong> The prefix is the
 * contact (D41), so without one there is nowhere to file the document that anything would ever
 * look in — and a guessed prefix is worse than a refusal naming the repair.
 */
@Service
public class ApplicationDocumentService {

	/**
	 * What one request may carry.
	 *
	 * <pre>
	 * ponytail: a constant, not a setting. It bounds a surface a signed-in stranger can reach; the
	 * number a deployment would actually want to tune is the per-file size, which is Spring's
	 * multipart limit and already a property.
	 * </pre>
	 */
	private static final int MAX_DOCUMENTS = 20;

	private final ApplicationDocumentRepository documents;
	private final ClientApplicationRepository applications;
	private final ClientAccountRepository accounts;
	private final ContactSnapshotRepository contacts;
	private final DocumentStore store;
	private final AuditService audit;

	ApplicationDocumentService(ApplicationDocumentRepository documents,
			ClientApplicationRepository applications, ClientAccountRepository accounts,
			ContactSnapshotRepository contacts, DocumentStore store, AuditService audit) {
		this.documents = documents;
		this.applications = applications;
		this.accounts = accounts;
		this.contacts = contacts;
		this.store = store;
		this.audit = audit;
	}

	/**
	 * One document, as either audience reads it.
	 *
	 * <p>The object key is deliberately absent: it is an internal address, and a client who can
	 * read one can guess at the shape of everybody else's.
	 */
	public record DocumentView(UUID id, String filename, String contentType, Long sizeBytes,
			Instant uploadedAt, boolean carriedToCase) {
	}

	// --- the client's own -------------------------------------------------------

	/**
	 * Attach a document to the caller's own request.
	 *
	 * <p><strong>DRAFT or SUBMITTED both allowed</strong>, per {@code 53} §3. A client who realises
	 * after submitting that they forgot a transcript should be able to send it rather than open a
	 * second request, and Sales chasing exactly that is the conversation this unit exists to serve.
	 */
	@Transactional
	public DocumentView upload(PortalPrincipal principal, UUID applicationId, String filename,
			String contentType, long size, InputStream body) {
		ClientAccount client = account(principal);
		ClientApplication application = owned(client, applicationId);

		if (documents.findByBrandIdAndClientApplicationIdOrderByUploadedAtDesc(
				client.getBrandId(), application.getId()).size() >= MAX_DOCUMENTS) {
			throw new InvalidRequestException(
					"That is as many documents as one request can carry. Send the rest to your case "
							+ "manager once we are in touch.");
		}

		// **The contact comes off the account, never off the request.** A prefix built from
		// anything the caller sent would let one client write into another's — the same rule
		// PortalCaseService.upload states, for the same reason.
		ContactSnapshot contact = Optional.ofNullable(client.getContactId())
				.flatMap(contacts::findById)
				.filter((row) -> row.getBrandId().equals(client.getBrandId()))
				.orElseThrow(ApplicationDocumentService::noContactYet);

		String ghlContactId = contact.getGhlContactId();
		if (ghlContactId == null || ghlContactId.isBlank()) {
			// Named rather than guessed: nothing would ever look under a made-up prefix, so the
			// document would be written and lost in the same breath. It is backfilled at the
			// client's next sign-in or request (D3c).
			throw noContactYet();
		}

		String key = DocumentStore.clientKey(client.getBrandId(), ghlContactId, UUID.randomUUID());
		store.put(key, body, size, contentType);

		ApplicationDocument saved = documents.save(new ApplicationDocument(client.getBrandId(),
				application.getId(), contact.getId(), key, filename, contentType, size));

		// The filename, never the key. The trail is read by people asking what the client sent.
		audit.recordPortalEvent(client.getBrandId(), PortalAudience.CLIENT, "CLIENT_APPLICATION",
				application.getId(), AuditAction.UPDATED, null,
				java.util.Map.of("uploaded", filename));
		return view(saved);
	}

	@Transactional(readOnly = true)
	public List<DocumentView> mine(PortalPrincipal principal, UUID applicationId) {
		ClientAccount client = account(principal);
		ClientApplication application = owned(client, applicationId);
		return documents
				.findByBrandIdAndClientApplicationIdOrderByUploadedAtDesc(client.getBrandId(),
						application.getId())
				.stream().map(ApplicationDocumentService::view).toList();
	}

	/**
	 * Remove one, <strong>while the request is still a draft</strong>.
	 *
	 * <p>After submit it is evidence Sales may already have read, and a client deleting it from
	 * under them is a worse failure than one they have to ask about.
	 *
	 * <p>The row goes and the S3 object stays. An orphaned object costs storage; a delete that
	 * half-succeeded costs the file, and there is no undo for that.
	 */
	@Transactional
	public void remove(PortalPrincipal principal, UUID applicationId, UUID documentId) {
		ClientAccount client = account(principal);
		ClientApplication application = owned(client, applicationId);
		if (!application.isDraft()) {
			throw new InvalidRequestException(
					"This request has been sent, so its documents can no longer be removed here. "
							+ "Ask us and we will take it off for you.");
		}
		ApplicationDocument held = ours(client.getBrandId(), application.getId(), documentId);
		documents.delete(held);
		audit.recordPortalEvent(client.getBrandId(), PortalAudience.CLIENT, "CLIENT_APPLICATION",
				application.getId(), AuditAction.UPDATED,
				java.util.Map.of("removed", held.getFilename()), null);
	}

	// --- what Sales reads -------------------------------------------------------

	/**
	 * The documents behind a deal, for staff who can already open it.
	 *
	 * <p>Empty for a deal that did not come from the portal, which is most of them — an ordinary
	 * state rather than a failure, exactly as the application read beside it answers a null payload
	 * rather than a 404.
	 */
	@Transactional(readOnly = true)
	public List<DocumentView> forOpportunity(String ghlOpportunityId) {
		return application(ghlOpportunityId)
				.map((row) -> documents
						.findByBrandIdAndClientApplicationIdOrderByUploadedAtDesc(row.getBrandId(),
								row.getId())
						.stream().map(ApplicationDocumentService::view).toList())
				.orElseGet(List::of);
	}

	/**
	 * A five-minute URL for one document, audited.
	 *
	 * <p>The document is matched against the deal rather than taken on trust: a document id is a
	 * bare UUID on this route, and without the match a staff member holding one deal could read
	 * another deal's document by id alone.
	 */
	@Transactional(readOnly = true)
	public String urlFor(String ghlOpportunityId, UUID documentId) {
		ClientApplication application = application(ghlOpportunityId)
				.orElseThrow(() -> new ForbiddenException("That deal has no request behind it"));
		ApplicationDocument held = ours(application.getBrandId(), application.getId(), documentId);

		// `EXPORTED` and the document's own id, matching `CaseLifecycleService.readUrl` — a staff
		// presign of a client's file is the same event whichever table the row sits in, and the
		// brand is deliberately not passed: `recordEvent` takes it from the authenticated caller.
		audit.recordEvent("APPLICATION_DOCUMENT", held.getId(), AuditAction.EXPORTED,
				TenantContext.current().memberId(), null,
				java.util.Map.of("opened", held.getFilename()));
		return store.presignedUrl(held.getObjectKey());
	}

	// --- Handoff A --------------------------------------------------------------

	/**
	 * How many documents rode with this request.
	 *
	 * <p>All of them, carried or not: the confirmation tells a client what arrived, and whether
	 * Handoff A has since copied one onto a case is not a fact about their request.
	 */
	@Transactional(readOnly = true)
	public int countFor(UUID applicationId) {
		return documents.countByClientApplicationId(applicationId);
	}

	/**
	 * What this request still owes the case it became.
	 *
	 * <p>Empty on a replayed {@code opportunity.won}, which is how the skip is expressed: the
	 * carry-forward has no separate "have I run?" question to ask.
	 */
	@Transactional(readOnly = true)
	public List<ApplicationDocument> uncarried(UUID applicationId) {
		return documents.findByClientApplicationIdAndCarriedToCaseDocumentIdIsNull(applicationId);
	}

	/** Records that one document reached the case. Write-once in the entity, so a replay is a no-op. */
	@Transactional
	public void markCarried(UUID documentId, UUID caseDocumentId) {
		documents.findById(documentId).ifPresent((row) -> {
			row.carriedTo(caseDocumentId);
			documents.save(row);
		});
	}

	// --- plumbing ---------------------------------------------------------------

	private Optional<ClientApplication> application(String ghlOpportunityId) {
		UUID brandId = TenantContext.current().brandId();
		return ghlOpportunityId == null || ghlOpportunityId.isBlank() ? Optional.empty()
				: applications.findByBrandIdAndGhlOpportunityId(brandId, ghlOpportunityId);
	}

	/**
	 * The account the token names, brand-checked.
	 *
	 * <p>The same two arms {@code ClientApplicationService.account} uses and for the same reason: a
	 * Unit 42 account token names the account, while a party-scoped link names only a GHL contact.
	 */
	private ClientAccount account(PortalPrincipal principal) {
		Optional<ClientAccount> found = principal.namesAnAccountDirectly()
				? accounts.findById(principal.clientAccountId())
				: Optional.ofNullable(principal.ghlContactId()).flatMap(
						(contact) -> accounts.findByBrandIdAndGhlContactId(principal.brandId(), contact));

		return found.filter((account) -> account.getBrandId().equals(principal.brandId()))
				.orElseThrow(() -> new ForbiddenException("This link does not admit you to that"));
	}

	/** Compared on the account id, so somebody else's request is a 403 and not an empty result. */
	private ClientApplication owned(ClientAccount client, UUID applicationId) {
		return applications.findById(applicationId)
				.filter((row) -> row.getClientAccountId().equals(client.getId()))
				.orElseThrow(() -> new ForbiddenException("That request is not one of yours"));
	}

	/** One document, proved to be on this application and in this brand. Both halves matter. */
	private ApplicationDocument ours(UUID brandId, UUID applicationId, UUID documentId) {
		return documents.findById(documentId)
				.filter((row) -> row.getBrandId().equals(brandId))
				.filter((row) -> row.getClientApplicationId().equals(applicationId))
				.orElseThrow(() -> new ForbiddenException("That document is not on this request"));
	}

	/**
	 * One wording for both arms of the contact check.
	 *
	 * <p>It says nothing about GHL. The client cannot act on "your contact has no GHL id", and the
	 * repair is the same either way — sign in again, which is what backfills it.
	 */
	private static InvalidRequestException noContactYet() {
		return new InvalidRequestException(
				"We have not finished setting up your client record yet, so there is nowhere to file "
						+ "this. Sign in again in a moment and try once more.");
	}

	private static DocumentView view(ApplicationDocument row) {
		return new DocumentView(row.getId(), row.getFilename(), row.getContentType(),
				row.getSizeBytes(), row.getUploadedAt(), row.isCarried());
	}
}
