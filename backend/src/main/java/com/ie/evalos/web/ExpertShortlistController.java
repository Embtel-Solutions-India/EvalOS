package com.ie.evalos.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertTier;
import com.ie.evalos.domain.FieldTag;
import com.ie.evalos.domain.PerformanceFlag;
import com.ie.evalos.service.ExpertMatchService;
import com.ie.evalos.service.ExpertMatchService.Factor;
import com.ie.evalos.service.ExpertMatchService.ScoredExpert;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The match engine's one route: the ranked experts for one case, with the arithmetic.
 *
 * <p><strong>A read that sits beside the picker, never in front of it.</strong>
 * {@code GET /api/experts} and {@code POST /api/cases/{id}/assign-cm} are unchanged, and this
 * endpoint is not a step on the way to either — a PM can ignore the shortlist entirely and
 * assign anybody available. That is what "assist mode" means, and it is asserted in
 * {@code CaseLifecycleServiceTest} rather than merely intended here.
 *
 * <p>Case Managers, Coordinators and the Expert Network Manager are deliberately <em>not</em>
 * on this route. The ENM owns the roster but does not staff cases, and a shortlist necessarily
 * reveals which case needs which discipline — supply-side access does not extend to case content
 * ({@code architecture.md}, scope tiers). The CM and Coordinator work the case the expert was
 * chosen for; neither chooses.
 *
 * <p>Brand scoping is decided inside the service, through {@code CaseLifecycleService.read} for
 * the case and {@code ExpertRepository.findScoped} for the roster. Nothing here re-derives it.
 */
@RestController
public class ExpertShortlistController {

	/**
	 * One shortlist card.
	 *
	 * <p>{@code payment_detail} is not a member, and neither is the email or the standard fee:
	 * this is a staffing decision, not the roster screen, and the encrypted field lives on the
	 * entity this DTO is mapped from (invariant 4).
	 *
	 * @param factors      the per-factor breakdown. Its {@code earned} values sum to
	 *                     {@code score} by construction — an unexplained ranking gets ignored,
	 *                     and one that does not add up gets distrusted
	 * @param flags        shown as warnings, not scored. {@code DECLINED_CASES} is already
	 *                     dropped by the service, being a worse version of the acceptance rate
	 * @param activeLoad   derived from the cases, never {@code current_active_count}
	 * @param qualityScore the tie-break, sent so the card can show why two equal scores are
	 *                     ordered as they are
	 */
	public record ShortlistCard(
			UUID id,
			String fullName,
			String title,
			String institution,
			ExpertTier tier,
			BigDecimal qualityScore,
			int score,
			List<Factor> factors,
			List<PerformanceFlag> flags,
			int activeLoad) {

		static ShortlistCard of(ScoredExpert scored) {
			Expert expert = scored.expert();
			return new ShortlistCard(expert.getId(), expert.getFullName(), expert.getTitle(),
					expert.getInstitution(), expert.getTier(), expert.getQualityScore(), scored.score(),
					scored.factors(), scored.flags(), scored.activeLoad());
		}
	}

	/**
	 * @param emptyReason null when {@code experts} is non-empty. When it is empty this names
	 *                    which factor emptied the list, because "no matches" tells the PM
	 *                    nothing they can act on
	 */
	public record ShortlistView(List<ShortlistCard> experts, String emptyReason) {
	}

	private final ExpertMatchService matching;

	ExpertShortlistController(ExpertMatchService matching) {
		this.matching = matching;
	}

	/**
	 * @param fieldTag which discipline the case needs. <strong>Required</strong>, and typed, so
	 *                 an unknown tag is a 400 rather than a shortlist that silently matched
	 *                 nothing. It is not stored on the case: the PM has just read the documents
	 *                 and is the only person who knows — see {@link ExpertMatchService}
	 */
	@GetMapping("/api/cases/{id}/expert-shortlist")
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER')")
	public ApiResponse<ShortlistView> shortlist(@PathVariable UUID id, @RequestParam FieldTag fieldTag) {
		ExpertMatchService.Shortlist shortlist = matching.shortlist(id, fieldTag);
		return ApiResponse.ok(new ShortlistView(
				shortlist.experts().stream().map(ShortlistCard::of).toList(), shortlist.emptyReason()));
	}
}
