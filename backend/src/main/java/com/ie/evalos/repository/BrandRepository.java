package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Brand;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<Brand, UUID> {

	Optional<Brand> findBySlug(String slug);

	/**
	 * Every live brand, for the GM's brand switcher. Deliberately unscoped — this is
	 * the cross-brand read, and the only caller gates it on the one cross-brand role.
	 * Ordered so the switcher does not reshuffle between loads.
	 */
	List<Brand> findByActiveTrueOrderByNameAsc();

	/** Brand resolution for inbound per-brand webhook endpoints (Handoff A). */
	Optional<Brand> findByWebhookEndpointTokenAndActiveTrue(String webhookEndpointToken);
}
