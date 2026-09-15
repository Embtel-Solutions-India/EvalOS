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
}
