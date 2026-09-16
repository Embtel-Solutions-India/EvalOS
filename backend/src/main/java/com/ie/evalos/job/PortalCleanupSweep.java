package com.ie.evalos.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ClientCredentialTokenRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Takes out what the portal's front door leaves behind.
 *
 * <p><strong>This is the half of D3a that is not a refusal.</strong> Moving the GHL contact off
 * sign-up stopped a flood reaching the CRM, which was the damage that mattered — but the route is
 * still {@code permitAll} at sixty requests a minute per IP, so a script still gets a
 * {@code client_account} row and a {@code client_credential_token} row for every attempt. Refusing
 * the write was the fix for the CRM; sweeping is the fix for the tables. Without this, "we made
 * the flood cheap" would just mean the growth is unbounded somewhere quieter.
 *
 * <p><strong>Two deletes, not one, because they answer to different clocks.</strong> A credential
 * token is dead the moment it expires — thirty minutes, a value the business never sees. An
 * abandoned account is a judgement about a person: somebody who signed up, never opened the mail,
 * and might still. So the token cutoff is derived from the TTL and the account cutoff is its own
 * property, and neither is expressed in terms of the other.
 *
 * <p><strong>Nothing here touches audit.</strong> Append-only is an invariant, and the
 * {@code CLIENT_ACCOUNT / CREATED} row is supposed to outlive the row it describes — that is what
 * makes it a trail rather than a mirror. A flood therefore still grows {@code audit_event}, and
 * that is accepted rather than overlooked: the audit table is the one place growth is the feature.
 *
 * <p>ponytail: two whole-table deletes, no batching. At this size that is one index scan each and
 * a daily interval; if {@code client_account} ever reaches millions of abandoned rows, delete in
 * chunks with a {@code limit} rather than making the sweep cleverer.
 */
@Component
public class PortalCleanupSweep implements Sweep {

	static final String JOB_TYPE = "PORTAL_CLEANUP";

	private static final Logger log = LoggerFactory.getLogger(PortalCleanupSweep.class);

	/** What the sweep does, one per transaction — so a failing delete cannot take the other. */
	private enum Target {
		EXPIRED_CREDENTIALS, ABANDONED_SIGN_UPS
	}

	private final SweepRunner runner;

	private final ClientCredentialTokenRepository credentials;

	private final ClientAccountRepository accounts;

	/**
	 * How long a dead token is kept past its expiry.
	 *
	 * <p>Derived from {@code credential-ttl} rather than configured: a token is unusable the
	 * instant it expires, so any retention beyond that is for a human reading the table during an
	 * incident, and one extra TTL is enough for that. A second property here would be a knob whose
	 * only correct value is this one.
	 */
	private final Duration credentialTtl;

	/**
	 * How long an unproved sign-up is kept.
	 *
	 * <p>Thirty days by default, and it is deliberately generous: this is the window in which a
	 * real client who signed up, missed the mail and came back still finds their own row rather
	 * than being told "we couldn't find that email" a second time. Shorter would make the sweep
	 * tidier and the client's experience worse, and tidiness is not what this exists for.
	 */
	private final Duration abandonedAfter;

	PortalCleanupSweep(SweepRunner runner, ClientCredentialTokenRepository credentials,
			ClientAccountRepository accounts,
			@Value("${evalos.portal.credential-ttl}") Duration credentialTtl,
			@Value("${evalos.portal.abandoned-sign-up-after}") Duration abandonedAfter) {
		this.runner = runner;
		this.credentials = credentials;
		this.accounts = accounts;
		this.credentialTtl = credentialTtl;
		this.abandonedAfter = abandonedAfter;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.PORTAL_CLEANUP}")
	@Override
	public boolean run() {
		return runner.sweep(JOB_TYPE, () -> List.of(Target.values()), this::purge);
	}

	/**
	 * @return true when rows went, which is what separates {@code acted} from {@code seen} on the
	 *         admin panel — a quiet deployment should read zero, not two
	 */
	private boolean purge(Target target) {
		Instant now = Instant.now();
		long removed = switch (target) {
			case EXPIRED_CREDENTIALS -> credentials.deleteByExpiresAtBefore(now.minus(credentialTtl));
			case ABANDONED_SIGN_UPS -> accounts.deleteAbandonedSignUps(now.minus(abandonedAfter));
		};
		if (removed > 0) {
			log.info("Portal cleanup removed {} {} rows", removed, target);
		}
		return removed > 0;
	}
}
