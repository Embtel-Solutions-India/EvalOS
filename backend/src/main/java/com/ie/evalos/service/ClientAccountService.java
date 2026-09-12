package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ClientCredentialToken;
import com.ie.evalos.domain.CredentialPurpose;
import com.ie.evalos.domain.PortalAudience;
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

		/**
		 * Known to EvalOS, no password, and <strong>no mail was sent because none can be</strong>.
		 *
		 * <p>Split from {@link #NO_PASSWORD} because the two differ in exactly what the client is
		 * supposed to do next: wait for an inbox, or stop waiting and call. Answering
		 * {@code NO_PASSWORD} with mail unconfigured tells a client a link is coming that nobody
		 * sent, and the only thing they can do with that is wait forever.
		 */
		MAIL_UNAVAILABLE,

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

	/**
	 * The client portal's origin — {@code evalos.portal.client-base-url}, and the one place a
	 * client-facing URL is still built.
	 *
	 * <p><strong>It began as a fix and ended as the only survivor.</strong> The set-password link
	 * was originally built from {@code evalos.portal.base-url}, which named whatever served
	 * {@code /portal/client} — a second copy of the client portal inside the <em>staff</em> SPA,
	 * and that property's dev default. A client clicking their mail landed on the staff sign-in
	 * page with their credential in the fragment. This property was added to separate them;
	 * {@code base-url} and the screen it pointed at have since been deleted outright, because the
	 * client portal is one deployment clients navigate to themselves.
	 *
	 * <p>Two origins remain, one per app that a person is ever sent to: this, and
	 * {@code expert-base-url}. Nothing mints a client <em>link</em> any more — a set-password mail
	 * is the only client URL EvalOS composes.
	 */
	private final String clientAppBaseUrl;

	ClientAccountService(ClientAccountRepository accounts, ClientCredentialTokenRepository credentials,
			ClientMailer mailer, PortalAccessService links, AuditService audit, PasswordEncoder encoder,
			@Value("${evalos.portal.client-brand}") UUID brandId,
			@Value("${evalos.portal.credential-ttl}") Duration credentialTtl,
			@Value("${evalos.portal.client-base-url}") String clientAppBaseUrl) {
		this.accounts = accounts;
		this.credentials = credentials;
		this.mailer = mailer;
		this.links = links;
		this.audit = audit;
		this.encoder = encoder;
		this.brandId = brandId;
		this.credentialTtl = credentialTtl;
		this.clientAppBaseUrl = clientAppBaseUrl.endsWith("/")
				? clientAppBaseUrl.substring(0, clientAppBaseUrl.length() - 1) : clientAppBaseUrl;
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
	 *
	 * <p><strong>Deliberately NOT {@code @Transactional}</strong>, and this is the one method
	 * where that is a decision rather than an omission — {@link #forgotPassword} is the other.
	 * The work is a read, a read and at most one insert, with no invariant spanning them: losing
	 * a race mints two usable tokens, which is not a defect. What a transaction <em>would</em>
	 * add is a Hikari connection held across the SMTP conversation — up to fifteen seconds of it
	 * on the configured timeouts, on a route anyone may call sixty times a minute per IP. Bound
	 * the send and you still exhaust the pool; take the connection out of the send's way and you
	 * do not. Every other method here keeps its transaction, because every other method writes
	 * more than one row.
	 */
	public IdentifyState identify(String email) {
		Optional<ClientAccount> found = accounts.findByBrandIdAndEmailIgnoreCase(brandId, normalize(email));
		if (found.isEmpty()) {
			return IdentifyState.UNKNOWN;
		}
		ClientAccount account = found.get();
		if (account.hasPassword()) {
			return IdentifyState.PASSWORD_SET;
		}
		return issueCredential(account, CredentialPurpose.SET)
				? IdentifyState.NO_PASSWORD : IdentifyState.MAIL_UNAVAILABLE;
	}

	/**
	 * Mints a single-use link and mails it, unless one is already on its way.
	 *
	 * <p><strong>Returns false rather than throwing when mail cannot be sent</strong> — whether
	 * because none is configured or because the send failed — and in neither case does a row
	 * survive: a token whose link has no way of reaching the client can only ever expire. The
	 * caller turns that into {@link IdentifyState#MAIL_UNAVAILABLE} so the screen can say
	 * something true. A throw would turn a mail outage into a 500 on a sign-in attempt.
	 *
	 * <p><strong>The mail goes out BEFORE the row is written, which is the opposite of the
	 * obvious order and is the point.</strong> Save-then-send leaves an unspent token behind when
	 * the send fails, and the cooldown below then reads that row as "a link is already on its
	 * way" — so the client is told to check an inbox nothing was ever delivered to, and told it
	 * again for a full {@code credential-ttl}, with the retry they would otherwise get suppressed
	 * by the failure itself. The cost of this order is the mirror case: mail lands and the insert
	 * fails, giving a link that refuses. That is a database outage, which is already answering
	 * 500 to everything; the SMTP outage is the one that happens on its own.
	 *
	 * <p><strong>An outstanding unspent token short-circuits the send</strong>, and that is the
	 * only thing bounding this. Both callers are reachable unauthenticated at 60 requests/min/IP,
	 * so minting on every call is a way to make EvalOS mail a named address without limit — and
	 * {@code client_credential_token} has no cleanup job, so it is also unbounded table growth.
	 * Reusing the outstanding one caps it at one mail per {@code credential-ttl} per account and
	 * per purpose. Returning true is correct there: a working link <em>is</em> in that inbox.
	 */
	private boolean issueCredential(ClientAccount account, CredentialPurpose purpose) {
		if (!mailer.isConfigured()) {
			return false;
		}
		if (credentials.findFirstByClientAccountIdAndPurposeAndUsedAtIsNullAndExpiresAtAfter(
				account.getId(), purpose, Instant.now()).isPresent()) {
			return true;
		}
		String token = PortalAccessService.freshCredentialToken();
		String link = clientAppBaseUrl + "/set-password#" + token;
		boolean sent = purpose == CredentialPurpose.SET
				? mailer.sendSetPassword(account.getEmail(), link)
				: mailer.sendResetPassword(account.getEmail(), link);
		if (!sent) {
			return false;
		}
		credentials.save(new ClientCredentialToken(account.getBrandId(), account.getId(),
				PortalAccessService.hash(token), purpose, Instant.now().plus(credentialTtl)));
		return true;
	}

	/**
	 * Verifies a password and hands back the portal credential.
	 *
	 * <p><strong>Both outcomes are audited</strong> (invariant 13, {@code actor_type = CLIENT}).
	 * A failed sign-in is the one event a support conversation actually needs, and an unaudited
	 * one is invisible forever.
	 *
	 * <p><strong>One message for every refusal.</strong> A wrong password, an account with no
	 * password and an unknown email all answer identically here — {@code identify} is where the
	 * difference is told, deliberately and once, so this route does not become a second and
	 * unthrottled enumeration surface.
	 *
	 * <p><strong>{@code noRollbackFor = InvalidRequestException.class}, and this is load-bearing.</strong>
	 * {@link AuditService#recordPortalEvent} is {@code @Transactional} and joins this method's
	 * transaction by design (its own javadoc: the trail commits with the change it describes or not
	 * at all) — but on the refusal path the audit row <em>is</em> the change, and {@code refused()}
	 * is an unchecked exception. Spring's default rollback rule would roll the whole transaction
	 * back on that throw, discarding the very row {@code CLIENT_SIGN_IN_REFUSED} exists to
	 * guarantee. The refusal path mutates nothing else — the account is loaded and never written to
	 * before the throw — so committing here commits exactly the audit row and nothing more.
	 */
	@Transactional(noRollbackFor = InvalidRequestException.class)
	public PortalAccessService.MintedToken signIn(String email, String password) {
		ClientAccount account = accounts.findByBrandIdAndEmailIgnoreCase(brandId, normalize(email))
				.orElseThrow(ClientAccountService::refused);

		// Checked before the hash comparison: encoder.matches against a null stored hash throws
		// on some encoders and returns false on others, and neither is a decision worth relying on.
		if (!account.hasPassword() || !encoder.matches(password, account.getPasswordHash())) {
			audit.recordPortalEvent(account.getBrandId(), PortalAudience.CLIENT, "CLIENT_ACCOUNT",
					account.getId(), AuditAction.CLIENT_SIGN_IN_REFUSED, null,
					"sign-in refused for " + account.getEmail());
			throw refused();
		}

		account.recordSignIn(Instant.now());
		audit.recordPortalEvent(account.getBrandId(), PortalAudience.CLIENT, "CLIENT_ACCOUNT",
				account.getId(), AuditAction.CLIENT_SIGNED_IN, null,
				"signed in as " + account.getEmail());
		return links.mintForClientAccount(account);
	}

	/**
	 * Sends a reset link if the address is known, and says nothing either way.
	 *
	 * <p><strong>Deliberately does not differentiate, unlike {@link #identify}.</strong> The
	 * three-way answer earns its enumeration on the sign-in screen because it tells a client
	 * something true and actionable. Here the client already believes they have an account, so
	 * differentiating buys nothing and the leak is not taken. The controller answers 204
	 * regardless.
	 *
	 * <p><strong>"Regardless" now includes a mail outage, and it did not.</strong> The send used
	 * to throw a {@code MailException} out of here, so a known address answered 500 while an
	 * unknown one answered 204 — the difference this method exists to hide, appearing on exactly
	 * the day somebody is probing. {@link ClientMailer} reports failure instead of throwing.
	 *
	 * <p>Not {@code @Transactional}, for the reason {@link #identify} states at length.
	 */
	public void forgotPassword(String email) {
		accounts.findByBrandIdAndEmailIgnoreCase(brandId, normalize(email))
				.ifPresent(account -> issueCredential(account, CredentialPurpose.RESET));
	}

	/**
	 * Spends a single-use link, stores the new password, and signs the client straight in.
	 *
	 * <p>Signing in here rather than bouncing to the sign-in screen is the point of returning a
	 * token: somebody who has just proved control of the mailbox and chosen a password should not
	 * immediately be asked for that password.
	 */
	@Transactional
	public PortalAccessService.MintedToken setPassword(String token, String password) {
		ClientCredentialToken credential = credentials.findByTokenHash(PortalAccessService.hash(token))
				.orElseThrow(ClientAccountService::linkRefused);
		Instant now = Instant.now();
		if (!credential.isUsable(now)) {
			throw linkRefused();
		}
		ClientAccount account = accounts.findById(credential.getClientAccountId())
				.orElseThrow(ClientAccountService::linkRefused);
		// The configured brand, checked here too. Every other method reaches the account through a
		// brand-scoped finder; this one reaches it through the token's own foreign key, which is
		// not scoped by anything. Without this line a deployment serving brand A would set a
		// password on a brand-B account from a link minted before its own `client-brand` changed —
		// and then mint a party token for it. Answers the same refusal as a spent link: which of
		// the reasons a link does not work is not the client's business.
		if (!brandId.equals(account.getBrandId())) {
			throw linkRefused();
		}

		credential.markUsed(now);
		// Explicit, matching every other write in this area (issueCredential's credentials.save,
		// PortalAccessService.retire's saveAndFlush) rather than relying on dirty checking: single-use
		// enforcement rides on this row actually being written, and no test would catch it if the
		// entity ever became detached.
		credentials.save(credential);
		account.setPasswordHash(encoder.encode(password));
		account.recordSignIn(now);
		audit.recordPortalEvent(account.getBrandId(), PortalAudience.CLIENT, "CLIENT_ACCOUNT",
				account.getId(), AuditAction.CLIENT_PASSWORD_SET, null,
				"password set for " + account.getEmail());
		return links.mintForClientAccount(account);
	}

	private static InvalidRequestException refused() {
		return new InvalidRequestException("That email and password do not match an account.");
	}

	private static InvalidRequestException linkRefused() {
		return new InvalidRequestException(
				"This link is no longer valid. It may have been used already, or it may have expired. "
						+ "Please request a new one.");
	}

	private static String normalize(String email) {
		return email == null ? "" : email.trim();
	}
}
