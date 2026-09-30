package com.ie.evalos.service;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.service.ClientAccountService.CaseAccountOutcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opens the client's portal account when their case is created (Unit 64, D3d) — the way a client
 * reaches the portal now that there is no public sign-up. Spec {@code 64} §2.
 *
 * <p><strong>After commit, in a new transaction, never throwing</strong>, as
 * {@code ChatLifecycleListener}: the case must already exist, and mail must never go out for a case
 * that rolled back. A failure is logged and flagged on the case; the case stands, and the client can
 * still recover through sign-in, which re-sends a set-password link to an account without one.
 *
 * <p><strong>ponytail: the set-password mail is sent inside that new transaction.</strong>
 * {@code identify} keeps SMTP out of transactions because it is an unauthenticated flood surface;
 * this runs once per won deal. Move the send after the transaction if case volume ever makes a held
 * connection matter.
 */
@Component
public class CasePortalAccountListener {

	private static final Logger log = LoggerFactory.getLogger(CasePortalAccountListener.class);

	/** Outcomes a person has to act on, so each is written on the case as a flag. */
	static final Set<CaseAccountOutcome> FLAGGED = EnumSet.of(CaseAccountOutcome.NO_EMAIL,
			CaseAccountOutcome.NO_GHL_CONTACT, CaseAccountOutcome.OTHER_CONTACT, CaseAccountOutcome.MAIL_UNAVAILABLE);

	private final ContactSnapshotRepository contacts;
	private final CaseRepository cases;
	private final ClientAccountService accounts;
	private final AuditService audit;
	private final UUID portalBrand;
	private final TransactionTemplate newTransaction;

	CasePortalAccountListener(ContactSnapshotRepository contacts, CaseRepository cases, ClientAccountService accounts,
			AuditService audit, PlatformTransactionManager transactions,
			@Value("${evalos.portal.client-brand}") UUID portalBrand) {
		this.contacts = contacts;
		this.cases = cases;
		this.accounts = accounts;
		this.audit = audit;
		this.portalBrand = portalBrand;
		this.newTransaction = new TransactionTemplate(transactions);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void on(CaseEvents.CaseEvent event) {
		if (event.type() != CaseEvents.Type.CASE_CREATED || !portalBrand.equals(event.brandId())) {
			// The portal is single-brand (D7): a case in any other brand has no portal to open.
			return;
		}
		try {
			CaseAccountOutcome outcome = newTransaction.execute((status) -> {
				if (event.contactId() == null) {
					return CaseAccountOutcome.NO_GHL_CONTACT;
				}
				// D65: the mail names the case. Unscoped by id, as the chat listener's case read: a
				// listener has no caller whose scope applies, and the brand is checked against the
				// event's own.
				Case subject = cases.findById(event.caseId())
						.filter((row) -> event.brandId().equals(row.getBrandId())).orElse(null);
				String caseCode = subject == null ? null : subject.getCaseCode();
				String service = serviceLabel(subject == null ? null : subject.getServiceType());
				return contacts.findById(event.contactId())
						.filter((contact) -> event.brandId().equals(contact.getBrandId()))
						.map((contact) -> accounts.openForCase(contact, caseCode, service))
						.orElse(CaseAccountOutcome.NO_GHL_CONTACT);
			});
			if (FLAGGED.contains(outcome)) {
				flag(event, reasonFor(outcome));
			}
		}
		catch (RuntimeException failed) {
			log.warn("No portal account was opened for case {}; the case stands", event.caseId(), failed);
			flag(event, "could not be opened: " + failed.getMessage());
		}
	}

	private void flag(CaseEvents.CaseEvent event, String reason) {
		try {
			newTransaction.executeWithoutResult((status) -> audit.recordSystemEvent(event.brandId(), "CASE",
					event.caseId(), AuditAction.FLAGGED, null, "no portal account — " + reason));
		}
		catch (RuntimeException failed) {
			log.warn("Could not flag case {} for its portal account", event.caseId(), failed);
		}
	}

	/**
	 * {@code CREDENTIAL_EVALUATION} → "Credential evaluation". Reshaped rather than looked up, so a
	 * service type added later still reads as words; null → "Evaluation" (intake may not know it).
	 */
	static String serviceLabel(ServiceType type) {
		if (type == null) {
			return "Evaluation";
		}
		String words = type.name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
		return Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}

	static String reasonFor(CaseAccountOutcome outcome) {
		return switch (outcome) {
			case NO_EMAIL -> "the contact has no email; add one in GHL";
			case NO_GHL_CONTACT -> "the case has no GHL contact to link";
			case OTHER_CONTACT -> "a portal account for this email belongs to another contact";
			case MAIL_UNAVAILABLE -> "the account exists but the set-password email could not be sent; "
					+ "the client can sign in to get another";
			default -> outcome.name();
		};
	}
}
