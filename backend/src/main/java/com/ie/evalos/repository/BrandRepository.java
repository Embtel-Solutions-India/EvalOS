package com.ie.evalos.repository;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Brand;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<Brand, UUID> {

	Optional<Brand> findBySlug(String slug);

	/** Brand resolution for inbound per-brand webhook endpoints (Handoff A). */
	Optional<Brand> findByWebhookEndpointTokenAndActiveTrue(String webhookEndpointToken);
}
