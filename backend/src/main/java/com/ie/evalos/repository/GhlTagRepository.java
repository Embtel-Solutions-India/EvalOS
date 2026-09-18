package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.GhlReference;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.JpaRepository;

/** The mirrored GHL location tags (Unit 47b). Brand only, like every other location list. */
public interface GhlTagRepository extends JpaRepository<GhlReference.Tag, UUID> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	List<GhlReference.Tag> findByBrandIdOrderByNameAsc(UUID brandId);

	Optional<GhlReference.Tag> findByBrandIdAndGhlId(UUID brandId, String ghlId);

	long countByBrandId(UUID brandId);
}
