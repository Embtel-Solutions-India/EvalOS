package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.PipelinePurpose;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** The mirrored GHL pipelines (Unit 44a). */
public interface PipelineRepository extends JpaRepository<Pipeline, UUID>, JpaSpecificationExecutor<Pipeline> {

	/**
	 * Brand only — a pipeline has no team and no assignee.
	 *
	 * <p><strong>Deliberately not {@code brandAndPipeline}</strong>, even though this table is
	 * about pipelines. That axis narrows a row to the desk that owns it, and the answer to "which
	 * pipelines exist" must not depend on which one you work: a salesperson needs to see the stage
	 * names of their own board, and Unit 44b's {@code team_member_pipeline} is what says which
	 * board that is. Narrowing here would be the same fact expressed twice, in two places that can
	 * disagree.
	 */
	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	List<Pipeline> findByBrandIdOrderByPositionAscNameAsc(UUID brandId);

	Optional<Pipeline> findByBrandIdAndGhlId(UUID brandId, String ghlId);

	/**
	 * Every pipeline a brand uses for one purpose, live ones only.
	 *
	 * <p>A list rather than an {@code Optional}, and that is the shape {@code 00d} §6.7 asks for:
	 * there are three marketing pipelines and four sales ones. A caller that needs exactly one —
	 * {@code INTAKE} — says so at its own call site, where it can also say what it does about
	 * none and about two.
	 */
	List<Pipeline> findByBrandIdAndPurposeAndMissingSinceIsNullOrderByPositionAsc(UUID brandId,
			PipelinePurpose purpose);
}
