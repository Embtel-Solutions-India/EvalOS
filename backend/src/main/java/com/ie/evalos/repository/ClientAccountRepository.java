package com.ie.evalos.repository;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * No team or assignee axis: a client account belongs to a brand and to nobody narrower, the
 * same shape as {@code ContactSnapshotRepository}.
 */
public interface ClientAccountRepository extends ScopedRepository<ClientAccount> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/**
	 * The one lookup sign-in needs. <strong>Brand-scoped</strong>, matching the
	 * {@code (brand_id, lower(email))} unique index — a query here without the brand would be a
	 * cross-brand read of a credential.
	 *
	 * <p><strong>Spelled out rather than derived, and the {@code lower()} is the whole reason.</strong>
	 * Spring Data's {@code IgnoreCase} keyword generates {@code upper(email) = upper(?)}, and V43's
	 * unique index is on {@code lower(email)} — a functional index Postgres can only use for the
	 * expression it was built on, so the derived form is a sequential scan of every client account
	 * on the one query every sign-in attempt makes. The method name keeps the {@code IgnoreCase}
	 * suffix because it still describes what this does; the {@code @Query} is what decides.
	 */
	@Query("select a from ClientAccount a where a.brandId = :brandId and lower(a.email) = lower(:email)")
	Optional<ClientAccount> findByBrandIdAndEmailIgnoreCase(@Param("brandId") UUID brandId,
			@Param("email") String email);

	/**
	 * The account behind a party-scoped portal token (Unit 43).
	 *
	 * <p><strong>Why this is needed at all:</strong> {@code mintForClientAccount} writes a
	 * {@code ghl_contact_id} row when the account has a contact, and that row does not also carry
	 * the account id — so the common signed-in client arrives holding a contact and nothing else.
	 * Matches V43's `client_account_ghl_contact_idx`.
	 *
	 * <p><strong>Brand-scoped, and that is load-bearing rather than habitual.</strong> A GHL
	 * contact id is a *foreign* key: the same person can legitimately hold one in two brands, so
	 * a lookup without the brand is a cross-brand read of a credential's owner. V38's party index
	 * carries the brand for the same reason.
	 */
	Optional<ClientAccount> findByBrandIdAndGhlContactId(UUID brandId, String ghlContactId);

	/**
	 * Drops sign-ups that never became anything — {@code PortalCleanupSweep}.
	 *
	 * <p><strong>This is the other half of D3a's bargain, and D3d made it load-bearing.</strong>
	 * Sign-up is cheap to flood — a script gets a row per request — and now that the GHL contact is
	 * created there again, a flood also leaves contacts behind. This deletes the EvalOS side of
	 * that: <em>no password</em> (never proved the mailbox) and older than the cutoff.
	 *
	 * <p><strong>{@code ghl_contact_id} is deliberately NOT in the predicate any more.</strong> It
	 * was, while D3a held the contact back to set-password — and it would now match nothing at all,
	 * because every sign-up gets a contact before the mail. A condition that silently stops
	 * selecting anything is worse than no sweep: the table grows and the ledger reports a healthy
	 * job doing it.
	 *
	 * <p><strong>The GHL contact is left alone, and that is not an oversight.</strong> GHL owns
	 * contact identity (invariant 7) and a contact may by now carry a note, a tag or an
	 * appointment that EvalOS cannot see. Deleting over there on a schedule, from a predicate
	 * about an EvalOS row, is not a decision this sweep gets to make. Clearing them up is a GHL-side
	 * job, and the `source: "Client Portal"` on every one of them is what makes that filterable.
	 *
	 * <p><strong>The three {@code not exists} clauses are what keep this from being a footgun.</strong>
	 * Each is a foreign key into this table — a live credential token means somebody is mid-flow
	 * right now, an application means they filed a request, a portal access means they hold a
	 * session. None should be reachable for a null-password account, and that is exactly why they
	 * are checked: a delete whose safety rests on "should be unreachable" is one schema change away
	 * from removing a real client. Postgres would refuse the delete anyway; this makes it skip the
	 * row instead of failing the sweep.
	 *
	 * <p><strong>The audit rows are NOT touched</strong> — append-only, by invariant. The
	 * {@code CLIENT_ACCOUNT / CREATED} row outlives the account it describes, which is what an
	 * audit trail is for.
	 *
	 * <p>Not brand-scoped, for the reason {@code ClientCredentialTokenRepository}'s delete states.
	 */
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.transaction.annotation.Transactional
	@Query(nativeQuery = true, value = """
			delete from client_account a
			 where a.password_hash is null
			   and a.created_at < :cutoff
			   and not exists (select 1 from client_credential_token t where t.client_account_id = a.id)
			   and not exists (select 1 from client_application p where p.client_account_id = a.id)
			   and not exists (select 1 from portal_access x where x.client_account_id = a.id)
			""")
	int deleteAbandonedSignUps(@Param("cutoff") java.time.Instant cutoff);
}
