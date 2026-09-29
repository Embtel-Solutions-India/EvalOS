package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.CredentialPurpose;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertAccount;
import com.ie.evalos.domain.ExpertCredentialToken;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.integration.MailTransport;
import com.ie.evalos.repository.ExpertAccountRepository;
import com.ie.evalos.repository.ExpertCredentialTokenRepository;
import com.ie.evalos.repository.ExpertRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expert sign-up and sign-in (Unit 59, D23) — {@link ClientAccountService}'s shape, bound to the
 * roster.
 *
 * <p><strong>The email finds a roster row and nothing more.</strong> An account is bound to
 * {@code expert.id}; a caller who types an address is never matched to an account by that address
 * directly, so claiming an email cannot reach anybody's payouts — only a roster row whose own address
 * receives the link can.
 *
 * <p><strong>{@link #sendLink} answers the same way whatever happens</strong> (the route is a 204),
 * so the form cannot be used to learn who is on the panel.
 */
@Service
public class ExpertAccountService {

	private final ExpertRepository experts;

	private final ExpertAccountRepository accounts;

	private final ExpertCredentialTokenRepository credentials;

	private final ClientMailer mailer;

	private final PortalAccessService links;

	private final AuditService audit;

	private final PasswordEncoder encoder;

	private final UUID brandId;

	private final Duration credentialTtl;

	private final String expertAppBaseUrl;

	ExpertAccountService(ExpertRepository experts, ExpertAccountRepository accounts,
			ExpertCredentialTokenRepository credentials, ClientMailer mailer, PortalAccessService links,
			AuditService audit, PasswordEncoder encoder,
			@Value("${evalos.portal.expert-brand}") UUID brandId,
			@Value("${evalos.portal.credential-ttl}") Duration credentialTtl,
			@Value("${evalos.portal.expert-base-url}") String expertAppBaseUrl) {
		this.experts = experts;
		this.accounts = accounts;
		this.credentials = credentials;
		this.mailer = mailer;
		this.links = links;
		this.audit = audit;
		this.encoder = encoder;
		this.brandId = brandId;
		this.credentialTtl = credentialTtl;
		this.expertAppBaseUrl = expertAppBaseUrl.endsWith("/")
				? expertAppBaseUrl.substring(0, expertAppBaseUrl.length() - 1) : expertAppBaseUrl;
	}

	/**
	 * Sign-up and forgot-password, one act (spec 59 §1.5): a known expert gets a set-password link, or
	 * a reset link once they have a password. The account is created on first use.
	 */
	public void sendLink(String email) {
		Optional<Expert> found = roster(email);
		if (found.isEmpty()) {
			// Q6b (2026-09-28): nothing, by decision. An expert signs up only after being hired and
			// told to, so an unknown address has nobody to write to. The caller answers identically.
			return;
		}
		Expert expert = found.get();
		ExpertAccount account = accounts.findByExpertId(expert.getId()).orElseGet(() -> create(expert));
		CredentialPurpose purpose = account.hasPassword() ? CredentialPurpose.RESET : CredentialPurpose.SET;

		MailTransport.Recipient to = new MailTransport.Recipient(brandId, expert.getEmail().trim());
		// An outstanding link is the rate limit on an unauthenticated route that sends mail.
		if (!mailer.canReach(to) || credentials.findFirstByExpertAccountIdAndPurposeAndUsedAtIsNullAndExpiresAtAfter(
				account.getId(), purpose, Instant.now()).isPresent()) {
			return;
		}
		String token = PortalAccessService.freshCredentialToken();
		if (mailer.sendExpertLink(to, expert.getFullName(), expertAppBaseUrl + "/set-password#" + token,
				purpose == CredentialPurpose.RESET)) {
			credentials.save(new ExpertCredentialToken(brandId, account.getId(), PortalAccessService.hash(token), purpose,
					Instant.now().plus(credentialTtl)));
		}
	}

	private ExpertAccount create(Expert expert) {
		try {
			ExpertAccount account = accounts.saveAndFlush(new ExpertAccount(brandId, expert.getId()));
			audit.recordPortalEvent(brandId, PortalAudience.EXPERT, "EXPERT_ACCOUNT", account.getId(),
					AuditAction.CREATED, null, "account opened for expert " + expert.getId());
			return account;
		}
		catch (DataIntegrityViolationException raced) {
			// Two sign-ups at once: unique (expert_id) let one in, and that one is ours too.
			return accounts.findByExpertId(expert.getId()).orElseThrow(() -> raced);
		}
	}

	/** One refusal for an unknown email, no account, no password and a wrong password. */
	@Transactional(noRollbackFor = InvalidRequestException.class)
	public PortalAccessService.MintedToken signIn(String email, String password) {
		ExpertAccount account = roster(email).flatMap((expert) -> accounts.findByExpertId(expert.getId()))
				.orElseThrow(ExpertAccountService::refused);
		if (!account.hasPassword() || !encoder.matches(password, account.getPasswordHash())) {
			audit.recordPortalEvent(brandId, PortalAudience.EXPERT, "EXPERT_ACCOUNT", account.getId(),
					AuditAction.EXPERT_SIGN_IN_REFUSED, null, "expert sign-in refused");
			throw refused();
		}
		account.recordSignIn(Instant.now());
		audit.recordPortalEvent(brandId, PortalAudience.EXPERT, "EXPERT_ACCOUNT", account.getId(),
				AuditAction.EXPERT_SIGNED_IN, null, "expert signed in");
		return links.mintForExpertAccount(account);
	}

	@Transactional
	public PortalAccessService.MintedToken setPassword(String token, String password) {
		ExpertCredentialToken credential = credentials.findByTokenHash(PortalAccessService.hash(token))
				.orElseThrow(ExpertAccountService::linkRefused);
		Instant now = Instant.now();
		if (!credential.isUsable(now) || !brandId.equals(credential.getBrandId())) {
			throw linkRefused();
		}
		ExpertAccount account = accounts.findById(credential.getExpertAccountId())
				.orElseThrow(ExpertAccountService::linkRefused);
		credential.markUsed(now);
		credentials.save(credential);
		account.setPasswordHash(encoder.encode(password));
		account.recordSignIn(now);
		audit.recordPortalEvent(brandId, PortalAudience.EXPERT, "EXPERT_ACCOUNT", account.getId(),
				AuditAction.EXPERT_PASSWORD_SET, null, "expert password set");
		return links.mintForExpertAccount(account);
	}

	/** The portal's own brand only: an expert on two panels is two rows, and two accounts. */
	private Optional<Expert> roster(String email) {
		String normalized = email == null ? "" : email.trim();
		return normalized.isEmpty() ? Optional.empty() : experts.findByBrandIdAndEmailIgnoreCase(brandId, normalized);
	}

	private static InvalidRequestException refused() {
		return new InvalidRequestException("That email and password do not match an account.");
	}

	private static InvalidRequestException linkRefused() {
		return new InvalidRequestException(
				"This link is no longer valid. It may have been used already, or it may have expired. "
						+ "Please request a new one.");
	}
}
