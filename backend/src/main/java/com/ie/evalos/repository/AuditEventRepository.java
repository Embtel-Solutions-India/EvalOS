package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

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
}
