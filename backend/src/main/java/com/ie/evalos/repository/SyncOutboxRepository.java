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

	/** What a human looks for after an incident. */
	List<SyncOutboxEntry> findByBrandIdAndDeadAtIsNotNullOrderByDeadAtDesc(UUID brandId, Limit limit);
}
