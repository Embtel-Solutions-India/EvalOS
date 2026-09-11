package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ClientCredentialToken;
import com.ie.evalos.domain.CredentialPurpose;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ClientCredentialTokenRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The client's sign-in door (Unit 42).
 *
 * <p><strong>This service issues no credential of its own.</strong> A verified password is
 * exchanged for the party-scoped {@code PortalAccess} token Unit 35 already built, through
 * {@link PortalAccessService#mintForClientAccount}. That is what keeps this unit to two tables
 * and one {@code permitAll} matcher rather than the "third Spring Security chain" spec 34's D1
 * predicted.
 */
@Service
public class ClientAccountService {

	/**
	 * What the sign-in screen branches on.
	 *
	 * <p><strong>Three answers rather than one, and that is the whole feature.</strong> Every
	 * client who has ever had a case is seeded with a null password, so a combined
	 * email-and-password form would tell all of them "wrong password" — which is false and
	 * unactionable. Email first lets the truthful answer be given and fixed in one step.
	 */
	public enum IdentifyState {

		/** Signed up already. Reveal the password field in place. */
		PASSWORD_SET,

		/** Known to EvalOS, never set a password. A set-password mail has just been sent. */
		NO_PASSWORD,

		/** No account. Offer Get Started, carrying the email forward. */
		UNKNOWN
	}

	private final ClientAccountRepository accounts;

	private final ClientCredentialTokenRepository credentials;

	private final ClientMailer mailer;

	private final PortalAccessService links;

	private final AuditService audit;

	private final PasswordEncoder encoder;

	private final UUID brandId;

	private final Duration credentialTtl;

	private final String portalBaseUrl;

	ClientAccountService(ClientAccountRepository accounts, ClientCredentialTokenRepository credentials,
			ClientMailer mailer, PortalAccessService links, AuditService audit, PasswordEncoder encoder,
			@Value("${evalos.portal.client-brand}") UUID brandId,
			@Value("${evalos.portal.credential-ttl}") Duration credentialTtl,
			@Value("${evalos.portal.base-url}") String portalBaseUrl) {
		this.accounts = accounts;
		this.credentials = credentials;
		this.mailer = mailer;
		this.links = links;
		this.audit = audit;
		this.encoder = encoder;
		this.brandId = brandId;
		this.credentialTtl = credentialTtl;
		this.portalBaseUrl = portalBaseUrl.endsWith("/")
				? portalBaseUrl.substring(0, portalBaseUrl.length() - 1) : portalBaseUrl;
	}

	/**
	 * Which of the three screens the client should see next.
	 *
	 * <p><strong>This reveals whether an email is known, and that is a decision.</strong> It is
	 * email enumeration, accepted because the three-way answer <em>is</em> the feature: hiding it
	 * makes the common case (existing client, no password) indistinguishable from a typo. What
	 * contains it is the per-IP limiter already running over {@code /api/portal/**} in
	 * {@code PortalTokenFilter}. See spec §3.
	 *
	 * <p><strong>An unknown email sends nothing.</strong> Otherwise this route is a way to make
	 * EvalOS mail an arbitrary address, which is a different and worse hole than enumeration.
	 */
	@Transactional
	public IdentifyState identify(String email) {
		Optional<ClientAccount> found = accounts.findByBrandIdAndEmailIgnoreCase(brandId, normalize(email));
		if (found.isEmpty()) {
			return IdentifyState.UNKNOWN;
		}
		ClientAccount account = found.get();
		if (account.hasPassword()) {
			return IdentifyState.PASSWORD_SET;
		}
		issueCredential(account, CredentialPurpose.SET);
		return IdentifyState.NO_PASSWORD;
	}

	/**
	 * Mints a single-use link and mails it.
	 *
	 * <p>Returns quietly when mail is unconfigured: {@link ClientMailer} has already logged it,
	 * and the screen's copy tells the client to contact support. A throw here would turn a
	 * configuration gap into a 500 on a sign-in attempt.
	 */
	private void issueCredential(ClientAccount account, CredentialPurpose purpose) {
		String token = PortalAccessService.freshCredentialToken();
		credentials.save(new ClientCredentialToken(account.getBrandId(), account.getId(),
				PortalAccessService.hash(token), purpose, Instant.now().plus(credentialTtl)));
		String link = portalBaseUrl + "/set-password#" + token;
		if (purpose == CredentialPurpose.SET) {
			mailer.sendSetPassword(account.getEmail(), link);
		}
		else {
			mailer.sendResetPassword(account.getEmail(), link);
		}
	}

	private static String normalize(String email) {
		return email == null ? "" : email.trim();
	}
}
