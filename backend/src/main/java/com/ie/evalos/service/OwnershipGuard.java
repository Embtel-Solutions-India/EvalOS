package com.ie.evalos.service;

import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Component;

/**
 * The write-side counterpart to {@link ScopePredicate}: reads are filtered, but
 * a mutation targets one known row, so it has to be checked rather than
 * filtered. Call this before every state change on a scoped entity.
 */
@Component
public class OwnershipGuard {

	/** Brand check only — for entities with no assignee axis. */
	public void assertCanAct(UUID entityBrandId) {
		assertCanAct(entityBrandId, null);
	}

	/**
	 * @param entityBrandId the row's brand; null is treated as unscoped and denied
	 *                      to everyone but the GM
	 * @param assigneeId    the row's assigned member, or null when it has none
	 */
	public void assertCanAct(UUID entityBrandId, UUID assigneeId) {
		TenantContext ctx = TenantContext.current();
		if (ctx.isCrossBrand()) {
			return;
		}
		if (entityBrandId == null || !entityBrandId.equals(ctx.brandId())) {
			throw new ForbiddenException("Row belongs to another brand");
		}
		if (ctx.role().tier() == Role.Tier.SELF && assigneeId != null && !assigneeId.equals(ctx.memberId())) {
			throw new ForbiddenException("Row is assigned to another member");
		}
	}
}
