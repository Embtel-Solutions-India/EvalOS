package com.ie.evalos.chat;

import java.util.List;
import java.util.UUID;

/**
 * Everything membership depends on, for one case, already loaded. Pure data so the rules can be
 * tested without a database. Lists are never null; single ids may be.
 */
public record CaseRoster(UUID caseId, UUID brandId, UUID clientAccountId, List<UUID> pipelineSales,
		UUID pm, UUID coordinator, UUID caseManager, List<UUID> enms, UUID expertId) {

	public CaseRoster {
		pipelineSales = List.copyOf(pipelineSales);
		enms = List.copyOf(enms);
	}
}
