package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.SyncOutboxEntry;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Durable EvalOS→GHL pushes (Unit 45c). */
public interface SyncOutboxRepository
		extends JpaRepository<SyncOutboxEntry, UUID>, JpaSpecificationExecutor<SyncOutboxEntry> {

	/** Brand only — an outbox row has no team, no assignee and no pipeline of its own. */
	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	/**
	 * The drain's read: oldest pending first, capped.
	 *
	 * <p><strong>Oldest first so a write is never starved by a newer one</strong>, and capped so one
	 * pass cannot spend the whole GHL budget — the pacer would serialise it anyway, and a sweep that
	 * runs for ten minutes is a sweep the next tick overlaps.
	 */
	List<SyncOutboxEntry> findByBrandIdAndSentAtIsNullAndDeadAtIsNullOrderByQueuedAtAsc(UUID brandId,
			Limit limit);

	long countByBrandIdAndSentAtIsNullAndDeadAtIsNull(UUID brandId);

	long countByBrandIdAndDeadAtIsNotNull(UUID brandId);

	/**
	 * Queues a push, or does nothing if the same one is already pending.
	 *
	 * <p><strong>{@code ON CONFLICT DO NOTHING}, not a caught constraint violation</strong>
	 * (2026-09-24). A failed {@code saveAndFlush} marks the surrounding transaction rollback-only
	 * even when the exception is caught, so every collapse — a deal edited twice before the drain,
	 * a note edited twice — failed its commit and answered 500 for a write that had landed. The
	 * conflict target is {@code uq_sync_outbox_pending}'s own columns and predicate.
	 *
	 * <p><strong>{@code @Transactional} on the method, and it is load-bearing.</strong> The drain
	 * re-queues through a self-call that bypasses {@code enqueue}'s {@code REQUIRES_NEW}, and a
	 * derived query otherwise inherits the repository's read-only default — the {@code saveAndFlush}
	 * this replaced brought its own read-write transaction, and a bare modifying query does not.
	 *
	 * @return 1 if queued, 0 if an identical push was already pending
	 */
	@org.springframework.transaction.annotation.Transactional
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
			INSERT INTO sync_outbox (id, brand_id, entity_type, entity_id, intent, queued_at)
			VALUES (gen_random_uuid(), :brandId, :entityType, :entityId, :intent, now())
			ON CONFLICT (brand_id, entity_type, entity_id, intent)
			    WHERE sent_at IS NULL AND dead_at IS NULL
			DO NOTHING
			""")
	int enqueueIfAbsent(@org.springframework.data.repository.query.Param("brandId") UUID brandId,
			@org.springframework.data.repository.query.Param("entityType") String entityType,
			@org.springframework.data.repository.query.Param("entityId") UUID entityId,
			@org.springframework.data.repository.query.Param("intent") String intent);

	/** What a human looks for after an incident. */
	List<SyncOutboxEntry> findByBrandIdAndDeadAtIsNotNullOrderByDeadAtDesc(UUID brandId, Limit limit);
}
