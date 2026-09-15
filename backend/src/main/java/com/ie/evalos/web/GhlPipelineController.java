package com.ie.evalos.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.PipelinePurpose;
import com.ie.evalos.service.PipelineMirrorService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The GHL pipelines, as EvalOS holds them.
 *
 * <p><strong>Its own controller rather than a method on the funnel controller</strong> (which was
 * {@code MarketingController}, removed with its two screens on 2026-09-16), because this is
 * structural data a GM assigns work against rather than a report.
 *
 * <p><strong>It reads the mirror now, not GHL</strong> (Unit 44a). Before, every call to this route
 * was a live {@code GET /opportunities/pipelines} — one network round trip against a shared
 * 100-per-10-seconds budget, to render a dropdown. The rows are refreshed by the
 * {@code PIPELINE_MIRROR} sweep, and {@code syncedAt} is on every row so the screen can say how old
 * the answer is rather than implying it is live.
 *
 * <p><strong>An empty list means the sweep has not run, and that is recoverable without a
 * deploy:</strong> {@code POST /api/jobs/PIPELINE_MIRROR/run} already exists and is GM-only, so the
 * bootstrap and the manual repair are the same button. No route was added for it here.
 *
 * <p>GM-only, like everything over this location: {@code evalos.ghl.location-id} names one GHL
 * sub-account with no link to a brand, which is invariant 1's one stated exception and holds only
 * for the cross-brand reader.
 */
@RestController
@RequestMapping("/api/ghl")
public class GhlPipelineController {

	/**
	 * One pipeline as the assignment screen needs it.
	 *
	 * <p>{@code id} is <strong>GHL's</strong> id, deliberately: {@code team_member.ghl_pipeline_id}
	 * stores that, so a dropdown handing back EvalOS's uuid would need a translation step at every
	 * caller. {@code mirrorId} carries EvalOS's own key beside it for the routes that address the
	 * row itself, which is the two-names shape {@code 00c} §2a asks for.
	 *
	 * <p>{@code missingSince} is non-null when GHL has stopped returning this pipeline. It is
	 * <em>shown</em> rather than filtered out, because a member already assigned to it is still
	 * assigned and a screen that hid the row would leave that unexplained.
	 */
	public record PipelineOption(String id, UUID mirrorId, String name, int position,
			PipelinePurpose purpose, int stages, Instant syncedAt, Instant missingSince) {
	}

	public record PurposeRequest(String purpose) {
	}

	private final PipelineMirrorService mirror;

	GhlPipelineController(PipelineMirrorService mirror) {
		this.mirror = mirror;
	}

	@GetMapping("/pipelines")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<List<PipelineOption>> list() {
		return ApiResponse.ok(mirror.all().stream().map(this::option).toList());
	}

	/**
	 * Says what a pipeline is for — the one column on a mirrored pipeline EvalOS owns.
	 *
	 * <p>It replaces a config property per pipeline ({@code 00d} §6.7: config carried three
	 * {@code *-pipeline-name} settings and forbade a list, which was right at three pipelines and
	 * wrong at nine). Nothing infers a purpose from a name, because a rename in GHL would then
	 * silently reroute a client's request.
	 *
	 * <p>Addressed by the <strong>mirror id</strong>, not GHL's: this writes an EvalOS row, and a
	 * route keyed on a foreign id would be one recreate away from addressing nothing.
	 */
	@PutMapping("/pipelines/{mirrorId}/purpose")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<PipelineOption> setPurpose(@PathVariable UUID mirrorId,
			@RequestBody PurposeRequest request) {
		return ApiResponse.ok(option(mirror.setPurpose(mirrorId, parse(request.purpose()))));
	}

	private PipelineOption option(Pipeline pipeline) {
		return new PipelineOption(pipeline.getGhlId(), pipeline.getId(), pipeline.getName(),
				pipeline.getPosition(), pipeline.getPurpose(),
				mirror.stagesOf(pipeline.getId()).size(), pipeline.getSyncedAt(),
				pipeline.getMissingSince());
	}

	/**
	 * The enum, or a 400 naming what was allowed.
	 *
	 * <p>Parsed here rather than bound by Jackson so the refusal is a sentence a GM can act on
	 * instead of a deserialisation error — the same reason {@code SalesDeskService.close} spells
	 * out its three statuses.
	 */
	private static PipelinePurpose parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new InvalidRequestException("A purpose is required, one of: "
					+ String.join(", ", names()));
		}
		try {
			return PipelinePurpose.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			throw new InvalidRequestException("'" + raw + "' is not a purpose. One of: "
					+ String.join(", ", names()));
		}
	}

	private static List<String> names() {
		return java.util.Arrays.stream(PipelinePurpose.values()).map(Enum::name).toList();
	}

}
