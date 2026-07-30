package com.ie.evalos.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;

import org.springframework.data.repository.Repository;

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
	 * One action across a page of objects — today, the last document chase on each case
	 * the Coordinator's board is drawing (Unit 10).
	 *
	 * <p>A read, so it does not weaken anything above: the point of this interface is that
	 * no method here can <em>change</em> a row. "Last chased" is derived from the trail
	 * rather than kept in a column on the case, because a second record of one fact is a
	 * second thing that can disagree with the first — and the trail already had to record
	 * the chase regardless.
	 */
	List<AuditEvent> findByObjectTypeAndActionAndObjectIdIn(String objectType, AuditAction action,
			Collection<UUID> objectIds);
}
