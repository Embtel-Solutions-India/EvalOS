package com.ie.evalos.service;

import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ClientApplication;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ClientApplicationRepository;
import com.ie.evalos.security.TenantContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requests a client started and never sent (D54, closes Q11): a staff screen, not a sweep.
 *
 * <p>Since D10 the deal opens at submit, so a draft reaches no salesperson. This reads rows EvalOS
 * already holds — no GHL write, no job, and no opportunity opened for a draft, which would be the
 * reverted D10 under another name.
 *
 * <p><strong>Brand-scoped by the caller's brand, not their pipeline.</strong> A draft has no
 * pipeline yet, so the pipeline tier would fail closed and a salesperson would see nothing; the
 * brand is the narrowest scope a draft carries. The GM reads every brand, as on every ALL-tier read.
 */
@Service
public class AbandonedRequestService {

	/** Untouched this long counts as abandoned. */
	public static final Duration AFTER = Duration.ofHours(48);

	public record Row(UUID id, UUID brandId, String serviceName, String clientName, String email, String phone,
			Instant startedAt, Instant lastTouchedAt) {
	}

	private final ClientApplicationRepository applications;
	private final ClientAccountRepository accounts;
	private final Clock clock;

	AbandonedRequestService(ClientApplicationRepository applications, ClientAccountRepository accounts, Clock clock) {
		this.applications = applications;
		this.accounts = accounts;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<Row> forCaller() {
		TenantContext ctx = TenantContext.current();
		Instant cutoff = clock.instant().minus(AFTER);
		List<ClientApplication> drafts = ctx.role() == Role.GM
				? applications.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(ClientApplication.Status.DRAFT, cutoff)
				: ctx.brandId() == null ? List.of()
						: applications.findByBrandIdAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(ctx.brandId(),
								ClientApplication.Status.DRAFT, cutoff);

		// One lookup for the page; an account is matched only within its draft's own brand.
		Map<UUID, ClientAccount> byId = accounts.findAllById(drafts.stream().map(ClientApplication::getClientAccountId)
				.distinct().toList()).stream().collect(Collectors.toMap(ClientAccount::getId, Function.identity()));

		return drafts.stream().map((draft) -> {
			ClientAccount account = byId.get(draft.getClientAccountId());
			boolean sameBrand = account != null && account.getBrandId().equals(draft.getBrandId());
			return new Row(draft.getId(), draft.getBrandId(), draft.getServiceName(),
					sameBrand ? name(account) : null, sameBrand ? account.getEmail() : null,
					sameBrand ? account.getPhone() : null, draft.getCreatedAt(), draft.getUpdatedAt());
		}).toList();
	}

	private static String name(ClientAccount account) {
		String full = ((account.getFirstName() == null ? "" : account.getFirstName()) + " "
				+ (account.getLastName() == null ? "" : account.getLastName())).trim();
		return full.isEmpty() ? null : full;
	}
}
