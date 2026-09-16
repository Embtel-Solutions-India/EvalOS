package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.PipelineStage;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** The mirrored stages of the mirrored pipelines (Unit 44a). */
public interface PipelineStageRepository
		extends JpaRepository<PipelineStage, UUID>, JpaSpecificationExecutor<PipelineStage> {

	/** Brand only, for the same reason as {@link PipelineRepository#SCOPE}. */
	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	List<PipelineStage> findByBrandIdOrderByPositionAsc(UUID brandId);

	List<PipelineStage> findByPipelineIdOrderByPositionAsc(UUID pipelineId);

	Optional<PipelineStage> findByBrandIdAndGhlId(UUID brandId, String ghlId);

	/**
	 * The secondary natural key ({@code 00d} §6.7) — how a stage is re-found after GHL recreated
	 * it under a new id.
	 *
	 * <p>Returns a list, not an {@code Optional}, because GHL promises neither distinct positions
	 * nor distinct names within a pipeline. The caller repoints only on exactly one match: a wrong
	 * repoint moves every opportunity in a stage, which is worse than an ambiguity Unit 45's drift
	 * report can name.
	 */
	List<PipelineStage> findByPipelineIdAndPositionAndName(UUID pipelineId, int position, String name);
}
