package com.ie.evalos.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Append-only by construction: this extends the bare {@link Repository} marker
 * rather than {@code JpaRepository}, so {@code save} and the two finders below are
 * the only methods that exist. Nothing here can update or delete an audit row,
 * and nothing may be added that can — the trail is the record of what happened,
 * and a record that can be edited is not one.
 *
 * <p>The finders match the two indexes on the table: one object's history, and one
 * brand's most recent activity.
 */
public interface AuditEventRepository extends Repository<AuditEvent, UUID> {

	AuditEvent save(AuditEvent event);

	List<AuditEvent> findByObjectTypeAndObjectIdOrderByCreatedAtAsc(String objectType, UUID objectId);

	List<AuditEvent> findByBrandIdOrderByCreatedAtDesc(UUID brandId);

	/**
	 * One action across a page of cases — today, the last document chase on each case the
	 * Coordinator's board is drawing (Unit 10).
	 *
	 * <p>A read, so it does not weaken anything above: the point of this interface is that
	 * no method here can <em>change</em> a row. "Last chased" is derived from the trail
	 * rather than kept in a column on the case, because a second record of one fact is a
	 * second thing that can disagree with the first — and the trail already had to record
	 * the chase regardless.
	 *
	 * <p><b>Brand-scoped through the case, not through the audit row.</b> The predecessor
	 * took only object ids and relied on a javadoc asking callers not to pass ids from a
	 * request; a convention is not a scope. It cannot filter on {@code audit_event.brand_id}
	 * either — that column is nullable by design (a system event has no brand) and is
	 * stamped from {@code TenantContext}, so every action the GM takes carries null and
	 * would silently vanish from this result. The case's {@code brand_id} is the real
	 * scope truth: non-null, and the thing the caller was authorised against.
	 *
	 * <p>Native rather than JPQL because the entity is named {@code Case}, which collides
	 * with the JPQL {@code CASE} expression; {@code CaseRepository} takes the same way out
	 * for the same reason. {@code action} is passed as its name, matching the
	 * {@code EnumType.STRING} mapping.
	 */
	@Query(nativeQuery = true, value = """
			SELECT a.* FROM audit_event a
			  JOIN evalos_case c ON c.id = a.object_id
			 WHERE a.object_type = :objectType
			   AND a.action = :action
			   AND a.object_id IN (:caseIds)
			   AND c.brand_id IN (:brandIds)
			""")
	List<AuditEvent> findCaseActionScoped(@Param("objectType") String objectType, @Param("action") String action,
			@Param("caseIds") Collection<UUID> caseIds, @Param("brandIds") Collection<UUID> brandIds);

	/** Overload keeping the enum at the call site; the query needs the stored name. */
	default List<AuditEvent> findCaseActionScoped(String objectType, AuditAction action, Collection<UUID> caseIds,
			Collection<UUID> brandIds) {
		return findCaseActionScoped(objectType, action.name(), caseIds, brandIds);
	}
}
