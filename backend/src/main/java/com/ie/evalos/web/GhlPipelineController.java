package com.ie.evalos.web;

import java.util.List;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.integration.GhlPipelineClient;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pipeline list a GM picks from when assigning one to a sales or marketing employee.
 *
 * <p><strong>It exists because the id is opaque.</strong> {@code team_member.ghl_pipeline_id}
 * holds a 20-character GHL id, and asking a GM to paste one is how the wrong id gets pasted — a
 * wrong id there is <em>silent</em>, because the employee just sees an empty board. The list is
 * the difference between a typo and a choice.
 *
 * <p><strong>GM-only, and it is the fourth screen over the unattributable location</strong> that
 * {@code architecture.md} invariant 1 names as its one stated exception. That paragraph says
 * plainly that a fourth screen over this location inherits all of it, and the nav test asserts
 * every such path is GM-only in one loop — so this route is gated for the same reason Units 24,
 * 26 and 27 are, not for a new one.
 *
 * <p><strong>Its own controller rather than a method on {@code MarketingController}</strong>,
 * which owns the three funnel <em>windows</em>. This is neither marketing nor a window: it is
 * administration of who may see what, and folding it in would make that controller's name a lie
 * the next reader has to discover.
 *
 * <p>Read-only. Invariant 2 is untouched by Unit 36 — the first write verb is Unit 37's.
 */
@RestController
@RequestMapping("/api/ghl")
public class GhlPipelineController {

	/**
	 * Id and name, and deliberately not the stages.
	 *
	 * <p>A picker needs a label and a value. The stages are several times the payload, change
	 * independently of anything this screen does, and would be the beginning of EvalOS holding a
	 * view of GHL's pipeline structure — which is Unit 38's decision to take, with its own
	 * argument, not a field that arrived because it was in the response.
	 */
	public record PipelineOption(String id, String name) {
	}

	private final GhlPipelineClient pipelines;

	GhlPipelineController(GhlPipelineClient pipelines) {
		this.pipelines = pipelines;
	}

	@GetMapping("/pipelines")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<List<PipelineOption>> list() {
		return ApiResponse.ok(pipelines.pipelines().stream()
				.map((pipeline) -> new PipelineOption(pipeline.id(), pipeline.name()))
				.toList());
	}
}
