package com.ie.evalos.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ExpertAccount;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ExpertAccountRepository;
import com.ie.evalos.security.PortalPrincipal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The portal's policies, accepted once per account (Unit 72, D71): the Privacy Policy, the
 * Disclaimer and the Document Retention Policy, on the client and the expert portal alike.
 *
 * <p><strong>Accepted against a version, not a flag.</strong> {@link #VERSION} is the policies' date;
 * when they change, bumping it asks every account again. The account columns say where each one
 * stands; the append-only {@code TERMS_ACCEPTED} audit row is the evidence.
 *
 * <p><strong>A case-scoped link has no account</strong> (the pre-Unit 59 per-case portal links), so
 * there is nothing to ask it or to record, and it is answered as not required.
 */
@Service
public class PortalTermsService {

	/** The policies' last update — `LEGAL_UPDATED` in `shared/src/legal/legal.ts`. Keep the two in step. */
	public static final String VERSION = "2026-09-25";

	/** Whether this caller must accept before using the portal. */
	public record TermsStatus(boolean required, String version) {
	}

	private final ClientAccountRepository clients;
	private final ExpertAccountRepository experts;
	private final AuditService audit;

	PortalTermsService(ClientAccountRepository clients, ExpertAccountRepository experts, AuditService audit) {
		this.clients = clients;
		this.experts = experts;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public TermsStatus status(PortalPrincipal principal) {
		boolean required = principal.audience() == PortalAudience.CLIENT
				? client(principal).map((account) -> !account.hasAccepted(VERSION)).orElse(false)
				: expert(principal).map((account) -> !account.hasAccepted(VERSION)).orElse(false);
		return new TermsStatus(required, VERSION);
	}

	/** Idempotent: a second acceptance of the same version writes nothing. */
	@Transactional
	public TermsStatus accept(PortalPrincipal principal) {
		Instant now = Instant.now();
		if (principal.audience() == PortalAudience.CLIENT) {
			client(principal).filter((account) -> !account.hasAccepted(VERSION)).ifPresent((account) -> {
				account.acceptTerms(VERSION, now);
				clients.save(account);
				record(principal, "CLIENT_ACCOUNT", account.getId());
			});
		}
		else {
			expert(principal).filter((account) -> !account.hasAccepted(VERSION)).ifPresent((account) -> {
				account.acceptTerms(VERSION, now);
				experts.save(account);
				record(principal, "EXPERT_ACCOUNT", account.getId());
			});
		}
		return new TermsStatus(false, VERSION);
	}

	private void record(PortalPrincipal principal, String objectType, java.util.UUID accountId) {
		audit.recordPortalEvent(principal.brandId(), principal.audience(), objectType, accountId,
				AuditAction.TERMS_ACCEPTED, null, Map.of("version", VERSION));
	}

	// Unscoped by id, as the other portal reads: the id comes off the token's own row, and the brand is
	// checked against the token's, so no request value can reach another account.
	//
	// A client token names the account one of two ways, never both (V44): by `client_account_id` when
	// the account has no GHL contact yet, else by `ghl_contact_id`. Both lead to the same row.
	private Optional<ClientAccount> client(PortalPrincipal principal) {
		if (!principal.isPartyScoped()) {
			return Optional.empty();
		}
		if (principal.clientAccountId() != null) {
			return clients.findById(principal.clientAccountId())
					.filter((account) -> principal.brandId().equals(account.getBrandId()));
		}
		if (principal.ghlContactId() != null) {
			return clients.findByBrandIdAndGhlContactId(principal.brandId(), principal.ghlContactId());
		}
		return Optional.empty();
	}

	private Optional<ExpertAccount> expert(PortalPrincipal principal) {
		if (!principal.isPartyScoped() || principal.expertId() == null) {
			return Optional.empty();
		}
		return experts.findByExpertId(principal.expertId())
				.filter((account) -> principal.brandId().equals(account.getBrandId()));
	}
}
