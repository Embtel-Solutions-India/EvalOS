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
import com.ie.evalos.integration.GhlWriteClient;
import com.ie.evalos.integration.MailTransport;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ClientCredentialTokenRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(ClientAccountService.class);

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

		/**
		 * No account. Offer sign-up, carrying the email forward.
		 *
		 * <p>That offer led to a placeholder apologising for itself until 2026-09-15, because
		 * nothing created a {@code client_account} at runtime. {@link #signUp} is what it reaches
		 * now, and is the one state that route cannot return.
		 */
		UNKNOWN
	}

	private final ClientAccountRepository accounts;

	private final ClientCredentialTokenRepository credentials;

	private final ClientMailer mailer;

	/**
	 * GHL's contact door, for {@link #ensureCrmIdentity} and nothing else on this class.
	 *
	 * <p>Invariant 7: GHL owns contact identity, so EvalOS asks it whether this email is somebody
	 * it already has rather than deciding for itself. One call covers both branches.
	 *
	 * <p><strong>It was {@link #signUp}'s until 2026-09-16 (D3a).</strong> That method is
	 * {@code permitAll}; this one is reached only by spending an emailed single-use token.
	 */
	private final GhlWriteClient ghlContacts;

	/**
	 * The CRM row for the person signing up — Unit 44c.
	 *
	 * <p><strong>This closes the prospect gap</strong> ({@code 00d} §5.4). Until now the only writer
	 * of {@code contact_snapshot} was Handoff A, so it held <em>only contacts that won an
	 * opportunity</em> — every prospect, and every lead Marketing opened this month, was unknown to
	 * the portal. A client who signs up now has a CRM row from that moment, and the account points
	 * at it.
	 */
	private final ContactSnapshotService contacts;

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
			ClientMailer mailer, GhlWriteClient ghlContacts, ContactSnapshotService contacts,
			PortalAccessService links, AuditService audit,
			PasswordEncoder encoder,
			@Value("${evalos.portal.client-brand}") UUID brandId,
			@Value("${evalos.portal.credential-ttl}") Duration credentialTtl,
			@Value("${evalos.portal.client-base-url}") String clientAppBaseUrl) {
		this.accounts = accounts;
		this.credentials = credentials;
		this.mailer = mailer;
		this.ghlContacts = ghlContacts;
		this.contacts = contacts;
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
	 * The other half of the front door: a client EvalOS has never heard of, signing themselves up.
	 *
	 * <p><strong>Nothing created a {@code client_account} at runtime before this.</strong> Every
	 * row in that table came from {@code V45}, a one-shot backfill of the clients EvalOS already
	 * knew on 2026-09-12. Handoff A creates a case and a {@code contact_snapshot} and no account,
	 * and no staff route mints one — so every client acquired since then was told
	 * <em>"we couldn't find that email"</em> by a front door with nothing behind it. This is the
	 * only way in.
	 *
	 * <p><strong>This writes to the live CRM from a {@code permitAll} route, and that is a hazard
	 * held down by something other than ordering now (D3d).</strong> D3a moved the contact to
	 * set-password precisely to avoid this: an IP-rotating script could fill the sub-account Sales
	 * works in and spend GHL's shared 100-requests-per-10-seconds-per-location budget, which makes
	 * every GHL-backed staff screen answer 502 — a signup form taking the sales desk down.
	 *
	 * <p>It came back because GHL sends the mail now and will not mail a stranger: a contactId is
	 * required, so the contact has to exist <em>before</em> the set-password link, not after it.
	 * The security property D3a bought is kept by other means — a proof-of-human gate on this one
	 * route, its own tighter budget rather than the shared sixty, and {@code PORTAL_CLEANUP}
	 * removing what a flood leaves behind. The ordering was never the protection; it was one way
	 * of getting it.
	 *
	 * <p><strong>An address we already hold is not an error and does not create anything.</strong>
	 * It falls through to {@link #identify}, which is the same answer the sign-in screen would
	 * have given — so a returning client who clicks <em>Sign up</em> out of habit is recognised
	 * rather than duplicated, and signing up cannot be used to overwrite an account.
	 *
	 * <p><strong>This never signs anyone in, and that is the security property.</strong> The reply
	 * is an {@link IdentifyState}, never a token: when GHL already held the address, the account
	 * minted here reaches that contact's cases, so handing out a session for an unproven mailbox
	 * would be account takeover by typing a stranger's email. Control of the inbox is proved by
	 * the set-password link, exactly as it is for every seeded client.
	 *
	 * <p><strong>A local-only account is now the normal state, not a failure.</strong> This method
	 * used to refuse with a 502 when GHL was unreachable, on the argument that an account with no
	 * contact is a lead no salesperson can see. That argument survives; its answer moved. The
	 * account is invisible to Sales for exactly as long as the mailbox is unproved — which is the
	 * period in which it is not yet a lead — and {@link #setPassword} makes it visible the moment
	 * it is. If GHL is down at that moment, {@code ClientApplicationService} backfills the contact
	 * when the client actually asks for something (D3c), so nothing is lost either way.
	 *
	 * <p>Not {@code @Transactional}, for the reason {@link #identify} states at length: the tail
	 * of this method sends mail.
	 */
	public IdentifyState signUp(String email, String firstName, String lastName, String phone) {
		String normalized = normalize(email);
		if (accounts.findByBrandIdAndEmailIgnoreCase(brandId, normalized).isEmpty()) {
			ClientAccount account = new ClientAccount(brandId, normalized);
			account.setFirstName(firstName);
			account.setLastName(lastName);
			account.setPhone(phone);
			// **The contact is created here, and D3a's mechanism changed to allow it (D3d).** GHL
			// sends the set-password mail now, and `POST /conversations/messages` requires a
			// contactId — there is no contact-less send and a conversationId is no escape, because
			// a conversation belongs to a contact. So the contact cannot wait for setPassword: the
			// mail that leads to setPassword needs it. What replaced the delay as the flood defence
			// is the gate on this route plus PORTAL_CLEANUP, not the ordering.
			//
			// A GHL outage does not refuse the sign-up. The account stands with no contact,
			// `identify` answers MAIL_UNAVAILABLE because GHL cannot reach them, and
			// `ensureCrmIdentity` repairs it on their next sign-in (D3c).
			ensureCrmIdentity(account);
			try {
				// The repository's own transaction, and no method-level one here on purpose: a
				// @Transactional wrapper would hold a connection across identify()'s SMTP call
				// below, which is the trap that method's javadoc describes at length.
				accounts.saveAndFlush(account);
			}
			catch (DataIntegrityViolationException duplicate) {
				// `client_account_brand_email_key`. Two submissions of the same form raced — a
				// double-click is enough. The row the winner wrote is the row this caller wanted,
				// so falling through to identify() gives the same answer they would have got a
				// millisecond later, rather than a 500 on the front door.
				return identify(normalized);
			}
			audit.recordPortalEvent(brandId, PortalAudience.CLIENT, "CLIENT_ACCOUNT", account.getId(),
					AuditAction.CREATED, null, "signed up as " + normalized);
		}
		return identify(normalized);
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
		// **canReach, not isConfigured, and the difference is the GHL transport.** A configured
		// transport that cannot address THIS person is a real state now: GHL needs a linked
		// contact, and an account whose sign-up hit a GHL outage has none. Asking only whether
		// mail is configured would mint a token and report NO_PASSWORD for a link that never left.
		MailTransport.Recipient recipient =
				new MailTransport.Recipient(account.getEmail(), account.getGhlContactId());
		if (!mailer.canReach(recipient)) {
			return false;
		}
		if (credentials.findFirstByClientAccountIdAndPurposeAndUsedAtIsNullAndExpiresAtAfter(
				account.getId(), purpose, Instant.now()).isPresent()) {
			return true;
		}
		String token = PortalAccessService.freshCredentialToken();
		String link = clientAppBaseUrl + "/set-password#" + token;
		boolean sent = purpose == CredentialPurpose.SET
				? mailer.sendSetPassword(recipient, link)
				: mailer.sendResetPassword(recipient, link);
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
		// **"Login verify and update if something missing."** A client whose CRM link never got
		// written — GHL was down when they set their password, or their row was seeded by V45 from
		// a snapshot that carried no id — repairs it here, on the one route every returning client
		// passes through. Idempotent and silent: an account that already has a contact makes no
		// call, and a GHL outage leaves the sign-in alone, exactly as it does at set-password.
		ensureCrmIdentity(account);
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
	 *
	 * <p><strong>And it is where the client becomes a GHL contact (D3a).</strong> Proving the
	 * mailbox is the gate; see {@link #ensureCrmIdentity}.
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
		ensureCrmIdentity(account);
		return links.mintForClientAccount(account);
	}

	/**
	 * Gives this client a GHL contact, now that they have proved they can read their own mail.
	 *
	 * <p><strong>Called from three places, and idempotent so that it can be.</strong> Sign-up
	 * (the contact must exist before GHL can mail them), set-password, and sign-in — the last two
	 * as the repair D3c describes, for an account whose sign-up met a GHL outage or whose row was
	 * seeded by {@code V45} from a snapshot carrying no id.
	 *
	 * <p><strong>Idempotent on {@code ghl_contact_id}, which is what makes it safe here.</strong>
	 * A seeded {@code V45} client resetting a forgotten password already has an id, and re-upserting
	 * would be a pointless write against the rate budget. Only an account with no id calls out.
	 *
	 * <p><strong>A GHL refusal does not fail the set-password</strong>, for the same reason the
	 * snapshot write never failed the sign-up: locking a client out of their own account over a
	 * third party's outage is the worse failure, and the id is recoverable —
	 * {@code ClientApplicationService} creates the contact at the first request that needs one
	 * (D3c). This is the one place where "eventually" is genuinely good enough, because nothing
	 * between here and there reads the id.
	 *
	 * <p>Inside the transaction, matching {@code ClientApplicationService.start}, which already
	 * calls GHL from a {@code @Transactional} method on a bounded timeout. {@code identify}'s
	 * no-transaction rule is about SMTP on an unauthenticated flood surface and does not reach
	 * here.
	 */
	public void ensureCrmIdentity(ClientAccount account) {
		if (account.getGhlContactId() != null) {
			return;
		}
		try {
			GhlWriteClient.UpsertedContact contact = ghlContacts.upsertContact(account.getFirstName(),
					account.getLastName(), account.getEmail(), account.getPhone(),
					GhlWriteClient.SOURCE_CLIENT_PORTAL);
			account.linkGhlContact(contact.id());
			// The CRM mirror, found or created — Unit 44c. Nested rather than beside the upsert:
			// a snapshot keyed on a contact id needs the contact to have answered first.
			try {
				account.linkContact(contacts.findOrCreate(account.getBrandId(),
						ContactSnapshotService.Details.fromSignUp(contact.id(),
								fullNameOf(account.getFirstName(), account.getLastName()),
								account.getEmail(), account.getPhone()))
						.getId());
			}
			catch (RuntimeException couldNotLink) {
				log.warn("Linked {} to GHL without a CRM row: {}", account.getEmail(),
						couldNotLink.getMessage());
			}
		}
		catch (RuntimeException ghlRefused) {
			log.warn("Set a password for {} without a GHL contact: {}. It will be created on their "
					+ "first request.", account.getEmail(), ghlRefused.getMessage());
		}
	}

	private static InvalidRequestException refused() {
		return new InvalidRequestException("That email and password do not match an account.");
	}

	private static InvalidRequestException linkRefused() {
		return new InvalidRequestException(
				"This link is no longer valid. It may have been used already, or it may have expired. "
						+ "Please request a new one.");
	}

	/** What the CRM row is called. Null when neither part was given, rather than a blank string. */
	private static String fullNameOf(String firstName, String lastName) {
		String full = java.util.stream.Stream.of(firstName, lastName)
				.filter((part) -> part != null && !part.isBlank())
				.reduce((first, second) -> first + " " + second)
				.orElse(null);
		return full;
	}

	private static String normalize(String email) {
		return email == null ? "" : email.trim();
	}
}
