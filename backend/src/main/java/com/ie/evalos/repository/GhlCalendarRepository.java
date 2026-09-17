package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.GhlReference;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The mirrored GHL calendars (Unit 47).
 *
 * <p><strong>Brand only</strong>, exactly as {@code PipelineRepository}: this list belongs to the
 * location, and narrowing it by the caller's pipeline would make "which of these exist" depend on
 * which board you work.
 */
public interface GhlCalendarRepository extends JpaRepository<GhlReference.Calendar, UUID> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	List<GhlReference.Calendar> findByBrandIdOrderByNameAsc(UUID brandId);

	long countByBrandId(UUID brandId);

	Optional<GhlReference.Calendar> findByBrandIdAndGhlId(UUID brandId, String ghlId);
}
