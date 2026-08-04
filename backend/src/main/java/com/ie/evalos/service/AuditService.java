package com.ie.evalos.service;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one way an audit row is written. Every state change in EvalOS records one
 * (invariant 13), and because this joins the caller's transaction, the trail
 * commits with the change it describes or not at all.
 *
 * <p>Pass DTOs or plain maps as snapshots, not entities: a snapshot is a record of
 * what the fields were, not a live object graph to be lazily loaded later. The
 * expert's {@code payment_detail} is {@code @JsonIgnore}d, so it stays out of a
 * snapshot even if an entity is passed by mistake.
 */
@Service
public class AuditService {

	private final AuditEventRepository auditEvents;
	private final ObjectMapper objectMapper;

	AuditService(AuditEventRepository auditEvents, ObjectMapper objectMapper) {
		this.auditEvents = auditEvents;
		this.objectMapper = objectMapper;
	}

	/**
	 * @param objectType what was acted on, e.g. {@code CASE}, {@code EXPERT}
	 * @param actorId    the staff member responsible, or null for a system action
	 * @param before     state before the change, or null when there was none
	 * @param after      state after the change, or null when the object is gone
	 * @return the persisted row, whose {@code created_at} the database stamps
	 */
	@Transactional
	public AuditEvent recordEvent(String objectType, UUID objectId, AuditAction action, UUID actorId,
			Object before, Object after) {
		// The brand is not a parameter on purpose: it comes from the authenticated
		// caller, never from an argument a caller could get wrong. A system action
		// outside a request has no brand, which the nullable column allows.
		UUID brandId = TenantContext.find().map(TenantContext::brandId).orElse(null);
		return auditEvents.save(new AuditEvent(
				brandId, objectType, objectId, action, actorId, ActorType.STAFF, asJson(before), asJson(after)));
	}

	/**
	 * The same trail, for an action with no authenticated caller to derive a brand
	 * from — today only an inbound webhook, which resolved its brand from the
	 * endpoint token before writing anything.
	 *
	 * <p>Separately named rather than an overload with a brand parameter, so no
	 * request-scoped caller can reach it and quietly claim a brand: the argument is
	 * only trustworthy because the endpoint token is the most authoritative brand
	 * signal in the system (invariant 8). The actor is always the system.
	 */
	@Transactional
	public AuditEvent recordSystemEvent(UUID brandId, String objectType, UUID objectId, AuditAction action,
			Object before, Object after) {
		return auditEvents.save(new AuditEvent(
				brandId, objectType, objectId, action, null, ActorType.SYSTEM, asJson(before), asJson(after)));
	}

	/**
	 * The same trail, for something a client or an expert did through their own portal link
	 * (Unit 14; Unit 15 uses the {@code EXPERT} audience).
	 *
	 * <p><strong>Why a third writer rather than a widened first one.</strong> A portal caller is
	 * not staff and is not the system: {@code actor_id} stays null because no
	 * {@code team_member} row acted, and {@code actor_type} is what stops that null being read as
	 * "the system" — which matters most for the one action a client performs, since their approval
	 * is what commits a letter to an expert's signature.
	 *
	 * <p>The brand is a parameter for the same reason {@link #recordSystemEvent} takes one, and it
	 * is trustworthy for the same reason: it comes off the <strong>token's own row</strong>, which
	 * is the most authoritative brand signal available on that surface, and never from a request
	 * body. {@code TenantContext} is deliberately not consulted — a portal request has none.
	 */
	@Transactional
	public AuditEvent recordPortalEvent(UUID brandId, PortalAudience audience, String objectType, UUID objectId,
			AuditAction action, Object before, Object after) {
		return auditEvents.save(new AuditEvent(brandId, objectType, objectId, action, null,
				audience.actorType(), asJson(before), asJson(after)));
	}

	private String asJson(Object snapshot) {
		if (snapshot == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(snapshot);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalArgumentException("Audit snapshot could not be serialized", ex);
		}
	}
}
