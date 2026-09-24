package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientApplication;
import com.ie.evalos.service.ScopePredicate;

/**
 * No team or assignee axis: an application belongs to a brand and to one client account, the same
 * shape as {@code ClientAccountRepository}.
 *
 * <p><strong>Every finder here takes the account id, and that is the portal's scope.</strong> The
 * caller on this surface is a portal token, which has no {@code TenantContext} — so
 * {@code findScoped} is not the guard, the account is. A finder without it would be a client
 * reading somebody else's questionnaire.
 */
public interface ClientApplicationRepository extends ScopedRepository<ClientApplication> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/** Newest first, which is the order the dashboard shows them in. */
	List<ClientApplication> findByClientAccountIdOrderByCreatedAtDesc(UUID clientAccountId);

	/**
	 * The one in progress, if there is one. At most one row can match — the partial unique index
	 * `client_application_one_draft_idx` is what makes that true rather than this signature.
	 */
	Optional<ClientApplication> findByClientAccountIdAndStatus(UUID clientAccountId,
			ClientApplication.Status status);

	/** What Sales reads when standing on a deal. Brand-scoped, because the staff side has one. */
	Optional<ClientApplication> findByBrandIdAndGhlOpportunityId(UUID brandId, String ghlOpportunityId);

	/** The same, for a whole board at once — the service a portal-born card falls back to. */
	List<ClientApplication> findByBrandIdAndGhlOpportunityIdIn(UUID brandId,
			java.util.Collection<String> ghlOpportunityIds);
}
