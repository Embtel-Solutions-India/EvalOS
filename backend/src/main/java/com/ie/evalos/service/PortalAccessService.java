package com.ie.evalos.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.PortalAccess;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.PortalAccessRepository;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.CaseLifecycleService.CaseSnapshot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mint, revoke and resolve the link that admits a client — and, from Unit 15, an expert — to one
 * case. The only place a portal token is created or checked.
 *
 * <p><strong>A portal link is a credential, and is treated as one.</strong> The token is 256 bits
 * from {@link SecureRandom}, base64url, returned exactly once at mint time and stored only as a
 * SHA-256 hash: a database read yields no working link. The comparison is
 * {@link MessageDigest#isEqual}, because a short-circuiting comparison on a secret leaks it a byte
 * at a time — the same reasoning a signature comparison demands.
 *
 * <p><strong>Unknown, expired and revoked are one answer.</strong> {@link #resolve} returns empty
 * for all three, so nothing about which it was is learnable from the response.
 */
@Service
public class PortalAccessService {

	private static final int TOKEN_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	/** What a mint answers. The URL carries the token, and this is the only time it exists. */
	public record MintedLink(String url, Instant expiresAt) {
	}

	/**
	 * What staff may know about a link without being shown it: whether one is live, when it
	 * expires, and when it was last opened. Never the token, and never a way back to it.
	 */
	public record LinkStatus(boolean live, Instant expiresAt, Instant lastSeenAt) {

		static final LinkStatus NONE = new LinkStatus(false, null, null);
	}

	private final PortalAccessRepository tokens;
	private final CaseLifecycleService cases;
	private final ContactSnapshotRepository contacts;
	private final AuditService audit;
	private final Duration ttl;
	private final Duration partyTtl;
	private final String baseUrl;
	private final String expertBaseUrl;

	PortalAccessService(PortalAccessRepository tokens, CaseLifecycleService cases,
			ContactSnapshotRepository contacts, AuditService audit,
			@Value("${evalos.portal.link-ttl}") Duration ttl,
			@Value("${evalos.portal.party-link-ttl}") Duration partyTtl,
			@Value("${evalos.portal.base-url}") String baseUrl,
			@Value("${evalos.portal.expert-base-url:}") String expertBaseUrl) {
		this.tokens = tokens;
		this.cases = cases;
		this.contacts = contacts;
		this.audit = audit;
		this.ttl = ttl;
		this.partyTtl = partyTtl;
		// A trailing slash is a configuration typo, not a different URL.
		this.baseUrl = trimSlash(baseUrl);
		// Blank falls back to the client's origin, which is what a single-deployment environment
		// wants and what every test that predates the split expects. Two apps on two subdomains
		// set it; one app serving both does not have to.
		this.expertBaseUrl = expertBaseUrl.isBlank() ? this.baseUrl : trimSlash(expertBaseUrl);
	}

	private static String trimSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	// --- mint ----------------------------------------------------------------

	/**
	 * Issues a link for this case, revoking whatever live one it had.
	 *
	 * <p><strong>Re-minting revokes the previous token, inside this transaction.</strong> A client
	 * who says "the link doesn't work" gets a new one and the old one stops working immediately —
	 * otherwise every support request permanently widens the number of live credentials pointing
	 * at one case.
	 *
	 * <p><strong>The invariant is the database's, not this method's.</strong> {@code V23} adds a
	 * partial unique index on {@code (case_id, audience) WHERE revoked_at IS NULL}, so two concurrent
	 * mints cannot both succeed — the loser's transaction rolls back instead of leaving two live
	 * credentials for one case. Revoking below is what keeps the winner legal, not what enforces the
	 * rule; a lookup followed by an insert is a check-then-act, and this codebase fixes those with a
	 * constraint (the {@code V15}/{@code V16} lesson). V21's header claims this could not be an
	 * index because the predicate needs {@code now()} — V23's header explains why that did not
	 * follow.
	 *
	 * <p>The case is loaded through the scoped read, so another brand's case — or, for a Case
	 * Manager, one that is not theirs — cannot have a link minted for it. The brand on the token
	 * comes off that case and never from a request.
	 *
	 * <p>Audited, because a credential was issued toward a client. The snapshot records the
	 * audience and the expiry and <strong>never the token</strong>.
	 */
	@Transactional
	public MintedLink mint(UUID caseId, PortalAudience audience) {
		Case subject = cases.load(caseId);
		// An expert link with no expert on the case is a credential naming nobody — and since
		// Unit 15 the link is the only way the expert is reached, minting one early is how a
		// Case Manager ends up sending a letter to a person who was never assigned.
		if (audience == PortalAudience.EXPERT && subject.getExpertId() == null) {
			throw new IllegalTransitionException("no expert is assigned to this case");
		}
		Instant now = Instant.now();

		retirePrevious(subject.getId(), audience, now);

		String token = freshToken();
		// **The expert is stamped on the credential, not just the case** (V37). A CLIENT row leaves
		// it null: the client is the case's own contact, and copying that here would be a second
		// place for it to disagree.
		PortalAccess minted = tokens.save(new PortalAccess(
				subject.getBrandId(), subject.getId(), audience,
				audience == PortalAudience.EXPERT ? subject.getExpertId() : null,
				hash(token), now.plus(ttl)));

		audit.recordEvent("CASE", subject.getId(), AuditAction.PORTAL_LINK_ISSUED,
				TenantContext.current().memberId(), CaseSnapshot.of(subject),
				CaseSnapshot.of(subject, "%s portal link issued, expires %s".formatted(
						audience.name().toLowerCase(), minted.getExpiresAt())));

		return new MintedLink(urlFor(audience, token), minted.getExpiresAt());
	}

	/**
	 * Mints a <strong>party-scoped</strong> link for the person this case names (Unit 35, D1):
	 * the client's GHL contact, or the assigned expert. It admits every case that party has in
	 * this brand, not only this one.
	 *
	 * <p><strong>The party is derived from a case here, never taken from the caller</strong> — and
	 * that remains true of <em>this</em> method. An id arriving from a request would make it an
	 * enumeration surface: type contact ids until one mints. A Case Manager is looking at a case
	 * when they issue a link, and the case has already been through a scoped read, so the party it
	 * names is one they may already see. Same reasoning {@link #mint} relies on for the brand.
	 *
	 * <p><strong>Unit 42 adds {@link #mintForClientAccount} anyway, and the refusal above is why
	 * it looks the way it does.</strong> That method takes a {@link ClientAccount} the caller has
	 * <em>already authenticated as</em> — a password was verified before it is reached — so there
	 * is nothing to enumerate: you cannot mint for an account you cannot sign in to. It takes an
	 * entity rather than an id precisely so that no route can pass one in from a request body.
	 *
	 * <p>Seven days rather than thirty, because this opens more than the case in front of you.
	 */
	@Transactional
	public MintedLink mintForParty(UUID caseId, PortalAudience audience) {
		Case subject = cases.load(caseId);
		Instant now = Instant.now();
		String token = freshToken();
		PortalAccess minted;

		if (audience == PortalAudience.EXPERT) {
			// Same guard as the case-scoped mint, and for the same reason: a link naming nobody is
			// the way a letter reaches a person who was never assigned.
			if (subject.getExpertId() == null) {
				throw new IllegalTransitionException("no expert is assigned to this case");
			}
			retirePreviousExpertParty(subject.getBrandId(), subject.getExpertId(), now);
			minted = tokens.save(PortalAccess.forParty(subject.getBrandId(), audience, null,
					subject.getExpertId(), hash(token), now.plus(partyTtl)));
		} else {
			String contact = contactOf(subject);
			retirePreviousClientParty(subject.getBrandId(), contact, now);
			minted = tokens.save(PortalAccess.forParty(subject.getBrandId(), audience, contact, null,
					hash(token), now.plus(partyTtl)));
		}

		audit.recordEvent("CASE", subject.getId(), AuditAction.PORTAL_LINK_ISSUED,
				TenantContext.current().memberId(), CaseSnapshot.of(subject),
				CaseSnapshot.of(subject, "%s party portal link issued, expires %s".formatted(
						audience.name().toLowerCase(), minted.getExpiresAt())));

		return new MintedLink(urlFor(audience, token), minted.getExpiresAt());
	}

	/**
	 * Mints a party-scoped client link for an account whose password has just been verified
	 * (Unit 42).
	 *
	 * <p><strong>This is not a new credential.</strong> It is the same party-scoped
	 * {@code PortalAccess} Unit 35 built and the staff mint button issues, so every screen behind
	 * {@code PortalTokenFilter} consumes it without knowing an account exists. Sign-in is a new
	 * <em>way to obtain</em> the existing credential, not a second kind of session — which is why
	 * this unit adds no security filter chain.
	 *
	 * <p><strong>It takes a {@link ClientAccount}, deliberately, not an email or a contact id.</strong>
	 * See {@link #mintForParty}'s note on enumeration: an entity can only be produced by a lookup
	 * the caller has already passed, so no request body can steer this.
	 *
	 * <p><strong>An account with no GHL contact still mints</strong>, scoped to the account id
	 * instead ({@code V44} widened the scope constraint to admit that row). That is the minority
	 * shape — {@code V45} seeds the contact id from {@code contact_snapshot}, so the ordinary
	 * signed-in client takes the branch above — but it is the one where "EvalOS works when GHL is
	 * removed" stops being a slogan: a self-signup who is not in GHL yet signs in and reaches
	 * their documents with no GHL row anywhere. The principal that comes back from
	 * {@link #resolve} then carries a null contact id, which the portal reads already fail closed
	 * on ({@code PortalCaseService}) or answer empty for ({@code PortalInvoiceService},
	 * {@code PortalMeetingService}).
	 *
	 * <p><strong>The account's previous token is retired whichever shape this mint takes</strong>,
	 * and that is not symmetry for its own sake. {@code linkGhlContact} is a normal transition —
	 * Unit 43 pushes a signed-up client to GHL — so a client can sign in contactless on Monday and
	 * with a contact on Tuesday. Retiring only within the shape being minted would leave Monday's
	 * account-scoped credential live for the rest of its seven days, and "re-minting revokes the
	 * previous" would quietly stop being true for exactly the client the shape change describes.
	 * The contact-scoped row is retired by the contact branch, which is the only shape that can
	 * collide with V38's index.
	 *
	 * <p><strong>A transient account is refused</strong>, the way {@link #contactOf} refuses a case
	 * with no contact. Its id is assigned at persist ({@code ScopedEntity}), so an unsaved entity
	 * would mint a row naming nobody — which {@code V44}'s widened constraint rejects at commit
	 * anyway, but as a 500 rather than as a sentence saying what went wrong. It also makes the
	 * retirement above safe: a null id there would match no row and silently retire nothing.
	 *
	 * <p>Not audited here. The caller audits {@code CLIENT_SIGNED_IN} with the account as the
	 * subject, because a credential issued <em>as part of</em> a sign-in is one event, not two.
	 */
	@Transactional
	public MintedLink mintForClientAccount(ClientAccount account) {
		if (account.getId() == null) {
			throw new IllegalTransitionException(
					"this account has not been persisted, so a token would name nobody");
		}
		Instant now = Instant.now();
		String token = freshToken();
		String contact = account.getGhlContactId();
		PortalAccess minted;

		retire(tokens.findByClientAccountIdOrderByCreatedAtDesc(account.getId()), now);

		if (contact != null && !contact.isBlank()) {
			retirePreviousClientParty(account.getBrandId(), contact, now);
			minted = tokens.save(PortalAccess.forParty(account.getBrandId(), PortalAudience.CLIENT,
					contact, null, hash(token), now.plus(partyTtl)));
		}
		else {
			minted = tokens.save(PortalAccess.forAccount(account.getBrandId(), account.getId(),
					hash(token), now.plus(partyTtl)));
		}

		return new MintedLink(urlFor(PortalAudience.CLIENT, token), minted.getExpiresAt());
	}

	/**
	 * The case's client, as GHL's contact id.
	 *
	 * <p>Refuses rather than minting a link scoped to nothing. A case with no contact, or a
	 * contact with no GHL id, cannot have a party link — {@code V27} settled that email is a
	 * fallback *matching* key and never an identity, so there is no second thing to fall back to
	 * here (invariant 7).
	 */
	private String contactOf(Case subject) {
		String ghlContactId = subject.getContactId() == null ? null
				: contacts.findById(subject.getContactId())
						.map(ContactSnapshot::getGhlContactId)
						.orElse(null);
		if (ghlContactId == null || ghlContactId.isBlank()) {
			throw new IllegalTransitionException(
					"this case has no GHL contact, so a client party link would name nobody");
		}
		return ghlContactId;
	}

	/**
	 * Stamps {@code revoked_at} on every row this mint supersedes — <strong>not only the live
	 * ones</strong>.
	 *
	 * <p>An already-expired row is dead either way ({@code isLive} checks both fields), so retiring
	 * it changes nothing about who may read a token. It matters because it is what makes
	 * "at most one unrevoked row per case and audience" true, which is the form of the invariant
	 * V23's index can enforce without a clock. Leaving expired rows unrevoked would collide with that
	 * index the next time a link was minted after a natural expiry.
	 */
	private void retirePrevious(UUID caseId, PortalAudience audience, Instant now) {
		retire(tokens.findByCaseIdAndAudienceOrderByCreatedAtDesc(caseId, audience), now);
	}

	/**
	 * The party equivalents, one per audience — {@code V38} indexes each shape separately, so each
	 * has its own row to supersede. Both are brand-scoped: the same contact may be a client of two
	 * brands and the same expert may sit on two panels, and minting one brand's link must not
	 * revoke the other's.
	 */
	private void retirePreviousClientParty(UUID brandId, String ghlContactId, Instant now) {
		retire(tokens.findByBrandIdAndGhlContactIdAndAudienceAndCaseIdIsNullOrderByCreatedAtDesc(
				brandId, ghlContactId, PortalAudience.CLIENT), now);
	}

	private void retirePreviousExpertParty(UUID brandId, UUID expertId, Instant now) {
		retire(tokens.findByBrandIdAndExpertIdAndAudienceAndCaseIdIsNullOrderByCreatedAtDesc(
				brandId, expertId, PortalAudience.EXPERT), now);
	}

	/**
	 * <strong>{@code saveAndFlush}, and the flush is load-bearing.</strong> Hibernate's ActionQueue
	 * runs insertions before updates at flush, so a plain {@code save} here leaves the revocation
	 * queued <em>behind</em> the insert the mint is about to make — and the partial unique indexes
	 * this method exists to satisfy are checked by the database when that insert lands, at which
	 * point the previous row is still unrevoked. The second mint then fails with a duplicate key on
	 * the very row it had just superseded.
	 *
	 * <p>Not a hypothesis: {@code LocalPostgresIntegrationTest
	 * .anAccountScopedPortalTokenIsLegalAndStillOnlyOneLives} reproduced it on
	 * {@code portal_access_one_live_per_account} the first time a client signed in twice. The shape
	 * is the same for {@code V23}'s and {@code V38}'s indexes, which is why the flush lives here,
	 * in the one method every mint retires through, rather than at the one call site that caught it.
	 */
	private void retire(List<PortalAccess> superseded, Instant now) {
		for (PortalAccess existing : superseded) {
			if (existing.getRevokedAt() == null) {
				existing.revoke(now);
				tokens.saveAndFlush(existing);
			}
		}
	}

	/**
	 * Whether this case has a live link, for the staff panel.
	 *
	 * <p>Reads the newest row rather than filtering for the live one, deliberately: an expired
	 * link is worth showing as expired, and "no link has ever been minted" is a different thing to
	 * say than "the link you sent has run out".
	 */
	@Transactional(readOnly = true)
	public LinkStatus status(UUID caseId, PortalAudience audience) {
		Case subject = cases.load(caseId);
		List<PortalAccess> issued = tokens.findByCaseIdAndAudienceOrderByCreatedAtDesc(subject.getId(), audience);
		if (issued.isEmpty()) {
			return LinkStatus.NONE;
		}
		PortalAccess newest = issued.get(0);
		return new LinkStatus(newest.isLive(Instant.now()), newest.getExpiresAt(), newest.getLastSeenAt());
	}

	// --- resolve -------------------------------------------------------------

	/**
	 * Turns a presented token into the principal it admits, stamping {@code last_seen_at}.
	 *
	 * <p>Empty for an unknown, expired or revoked token — one answer for three states, so a caller
	 * learns nothing from the refusal. The write is why this is not {@code readOnly}: last-seen is
	 * the field support needs, and it moves on every use, unlike the case's
	 * {@code client_portal_read_at}, which is stamped once.
	 *
	 * <p><strong>Unit 42's account-scoped row needs no branch here, and that is the point.</strong>
	 * This method never asks what shape the row is — {@link PortalPrincipal#of} copies the columns
	 * and {@code caseId == null} is what makes it party-scoped — so a token minted by
	 * {@link #mintForClientAccount} resolves through exactly the path a staff-minted party token
	 * does. What an account-scoped principal carries is a <em>null</em> contact id, which is the
	 * whole downstream contract: {@code PortalCaseService} fails closed on it and the two
	 * GHL-backed reads answer empty. Do not add a shape check here to "fix" that null.
	 */
	@Transactional
	public Optional<PortalPrincipal> resolve(String presented) {
		if (presented == null || presented.isBlank()) {
			return Optional.empty();
		}
		Instant now = Instant.now();
		String presentedHash = hash(presented);
		return tokens.findByTokenHash(presentedHash)
				// The unique index found the row; {@link PortalAccess#matches} is what accepts it,
				// in constant time. Two steps rather than one because the finder is an index lookup
				// and this is a secret comparison — if the lookup is ever widened (a prefix index, a
				// case-insensitive column), the check that matters is still exact and still timing-safe.
				.filter(access -> access.matches(presentedHash))
				.filter(access -> access.isLive(now))
				.map(access -> {
					access.seen(now);
					return PortalPrincipal.of(tokens.save(access));
				});
	}

	// --- the token itself ----------------------------------------------------

	/** 256 bits, base64url, no padding — safe in a URL fragment without escaping. */
	private static String freshToken() {
		return freshCredentialToken();
	}

	/**
	 * The same generator, promoted for {@code ClientCredentialToken} (Unit 42): a set/reset-password
	 * link is a credential exactly like a portal link, and there is no reason for the two kinds to
	 * draw randomness differently. One generator, one place, so they cannot drift in entropy.
	 */
	static String freshCredentialToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 is mandated by the platform; unreachable.
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	/**
	 * The whole URL, so the caller never assembles one. The token travels in the <strong>fragment
	 * </strong> and not the query string: a fragment is never sent to the server, so it stays out
	 * of access logs, {@code Referer} headers and any redirect chain. The SPA reads it there and
	 * puts it in the {@code X-Portal-Token} header.
	 */
	private String urlFor(PortalAudience audience, String token) {
		// **Two apps, two origins, two paths (Unit 34e).** The expert portal is its own deployment
		// serving `/case`; the client's link still points at `/portal/client`, which is where that
		// screen lives until Unit 34 slice 34b moves it. Getting either wrong mints a link to a
		// page that does not exist — which reads to its holder exactly like a revoked token.
		return audience == PortalAudience.CLIENT
				? baseUrl + "/portal/client#" + token
				: expertBaseUrl + "/case#" + token;
	}
}
