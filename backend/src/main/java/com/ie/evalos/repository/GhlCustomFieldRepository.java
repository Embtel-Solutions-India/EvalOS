package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.GhlReference;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The mirrored GHL custom field definitions (Unit 47).
 *
 * <p><strong>Brand only</strong>, exactly as {@code PipelineRepository}: this list belongs to the
 * location, and narrowing it by the caller's pipeline would make "which of these exist" depend on
 * which board you work.
 */
public interface GhlCustomFieldRepository extends JpaRepository<GhlReference.CustomField, UUID> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	List<GhlReference.CustomField> findByBrandIdAndModelOrderByNameAsc(UUID brandId, String model);

	long countByBrandIdAndModel(UUID brandId, String model);

	Optional<GhlReference.CustomField> findByBrandIdAndGhlId(UUID brandId, String ghlId);
}
