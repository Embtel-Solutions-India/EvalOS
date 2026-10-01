package com.ie.evalos.service;

import java.util.UUID;

import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.integration.MailTransport;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.service.MailTemplates.CaseUpdate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The case progress mails (Unit 64c, D58; spec {@code 64c-case-progress-emails.md}) — and the expert
 * offer mail (D67, which closed Q17).
 *
 * <p><strong>After commit, reads in a read-only transaction, sends outside it, never throws</strong>
 * — the mail must not go out for a transition that rolled back, and SMTP must not hold a
 * connection. A failed or skipped send is logged only: each fact is already on the portal screen
 * the link opens, so the mail is a nudge and not the record.
 */
@Component
public class CaseMailListener {

	private static final Logger log = LoggerFactory.getLogger(CaseMailListener.class);

	/** Everything a send needs, read inside the transaction so nothing lazy is touched after it. */
	record Outgoing(MailTransport.Recipient to, CaseUpdate kind, String name, String service, String caseCode,
			int documents, String fee, String link) {
	}

	private final CaseRepository cases;
	private final ClientAccountRepository accounts;
	private final ExpertRepository experts;
	private final DocumentChecklistItemRepository checklist;
	private final ExpertCaseOfferRepository offers;
	private final BrandRepository brands;
	private final ClientMailer mailer;
	private final UUID portalBrand;
	private final String clientBase;
	private final String expertBase;
	private final TransactionTemplate readOnly;

	CaseMailListener(CaseRepository cases, ClientAccountRepository accounts, ExpertRepository experts,
			DocumentChecklistItemRepository checklist, ExpertCaseOfferRepository offers, BrandRepository brands,
			ClientMailer mailer, PlatformTransactionManager transactions,
			@Value("${evalos.portal.client-brand}") UUID portalBrand,
			@Value("${evalos.portal.client-base-url}") String clientBase,
			@Value("${evalos.portal.expert-base-url}") String expertBase) {
		this.cases = cases;
		this.accounts = accounts;
		this.experts = experts;
		this.checklist = checklist;
		this.offers = offers;
		this.brands = brands;
		this.mailer = mailer;
		this.portalBrand = portalBrand;
		this.clientBase = trim(clientBase);
		this.expertBase = trim(expertBase);
		this.readOnly = new TransactionTemplate(transactions);
		this.readOnly.setReadOnly(true);
	}

	static CaseUpdate kindOf(CaseEvents.Type type) {
		return switch (type) {
			// Every EXPERT_ASSIGNED opens an offer in the same transaction — first assignment,
			// rematch and retake alike (CaseLifecycleService.openOffer).
			case EXPERT_ASSIGNED -> CaseUpdate.OFFER;
			case CHECKLIST_REQUESTED -> CaseUpdate.CHECKLIST;
			case CHECKLIST_CHASED -> CaseUpdate.CHASE;
			case DRAFT_READY_FOR_CLIENT -> CaseUpdate.DRAFT_READY;
			case EXPERT_SENT_FOR_SIGNING -> CaseUpdate.SIGNING;
			case CASE_DELIVERED -> CaseUpdate.DELIVERED;
			default -> null;
		};
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void on(CaseEvents.CaseEvent event) {
		CaseUpdate kind = kindOf(event.type());
		// The portal is single-brand (D7): a case in any other brand has no portal to link to.
		if (kind == null || !portalBrand.equals(event.brandId())) {
			return;
		}
		try {
			Outgoing mail = readOnly.execute((status) -> resolve(kind, event));
			if (mail == null) {
				return;
			}
			if (!mailer.canReach(mail.to()) || !mailer.sendCaseUpdate(mail.to(), mail.kind(), mail.name(),
					mail.service(), mail.caseCode(), mail.documents(), mail.fee(), mail.link())) {
				log.warn("'{}' mail for case {} was not sent", kind, mail.caseCode());
			}
		}
		catch (RuntimeException failed) {
			log.warn("'{}' mail for case {} failed; the case is unaffected", kind, event.caseId(), failed);
		}
	}

	/** Null when there is nobody to tell, or (checklist) nothing to ask for. */
	private Outgoing resolve(CaseUpdate kind, CaseEvents.CaseEvent event) {
		// Unscoped by id, as CasePortalAccountListener: a listener has no caller whose scope applies,
		// and the brand is checked against the event's own.
		Case subject = cases.findById(event.caseId())
				.filter((row) -> event.brandId().equals(row.getBrandId())).orElse(null);
		if (subject == null) {
			return null;
		}
		String service = CasePortalAccountListener.serviceLabel(subject.getServiceType());
		UUID brand = subject.getBrandId();
		if (kind.expert) {
			if (subject.getExpertId() == null) {
				log.warn("Case {} has no expert for its '{}' mail; none sent", subject.getCaseCode(), kind);
				return null;
			}
			String fee = kind == CaseUpdate.OFFER ? offeredFee(subject) : null;
			return experts.findByIdAndBrandId(subject.getExpertId(), brand)
					.filter((expert) -> expert.getEmail() != null && !expert.getEmail().isBlank())
					.map((expert) -> new Outgoing(new MailTransport.Recipient(brand, expert.getEmail()), kind,
							expert.getFullName(), service, subject.getCaseCode(), 0, fee,
							expertBase + "/case?caseId=" + subject.getId()))
					.orElse(null);
		}
		int documents = 0;
		if (kind.countsDocuments()) {
			documents = (int) checklist.findByCaseId(subject.getId()).stream()
					.filter((item) -> item.isSent() && !item.getStatus().isComplete()).count();
			if (documents == 0) {
				return null;
			}
		}
		if (subject.getContactId() == null) {
			return null;
		}
		int count = documents;
		return accounts.findByBrandIdAndContactId(brand, subject.getContactId())
				.map((account) -> new Outgoing(new MailTransport.Recipient(brand, account.getEmail()), kind,
						account.getFirstName(), service, subject.getCaseCode(), count, null,
						clientBase + "/cases/" + subject.getId()))
				.orElse(null);
	}

	/**
	 * The open offer's fee to this case's expert, as "USD 250.00" — the amount the portal shows
	 * above Accept (Unit 65). Null when none is priced, and the mail then names no amount.
	 */
	private String offeredFee(Case subject) {
		String currency = brands.findById(subject.getBrandId()).map(Brand::getCurrency).orElse(null);
		return offers.findByCaseIdAndOutcome(subject.getId(), OfferOutcome.OFFERED).stream()
				.filter((offer) -> subject.getExpertId().equals(offer.getExpertId()) && offer.getFee() != null)
				.findFirst()
				.map((offer) -> (currency == null ? "" : currency + " ")
						+ offer.getFee().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
				.orElse(null);
	}

	private static String trim(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
