package com.ie.evalos.chat;

import java.util.UUID;

import com.ie.evalos.domain.Role;
import com.ie.evalos.security.TenantContext;

/** Who is calling, on any of the three surfaces. {@code staffRole} is null for a client or expert. */
public record ChatIdentity(ParticipantKind kind, UUID id, UUID brandId, Role staffRole) {

	public static ChatIdentity staff(TenantContext ctx) {
		return new ChatIdentity(ParticipantKind.STAFF, ctx.memberId(), ctx.brandId(), ctx.role());
	}

	public static ChatIdentity client(UUID clientAccountId, UUID brandId) {
		return new ChatIdentity(ParticipantKind.CLIENT, clientAccountId, brandId, null);
	}

	public static ChatIdentity expert(UUID expertId, UUID brandId) {
		return new ChatIdentity(ParticipantKind.EXPERT, expertId, brandId, null);
	}

	public boolean isViewerRole() {
		return staffRole == Role.GM || staffRole == Role.BRAND_MANAGER;
	}
}
