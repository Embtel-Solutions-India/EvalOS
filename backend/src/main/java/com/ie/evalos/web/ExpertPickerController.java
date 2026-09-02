package com.ie.evalos.web;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The experts a case can currently be put to, as a name and an id.
 *
 * <p><strong>Not the expert database</strong> — that is Unit 11, with search, taxonomy
 * matching, quality scores and the sheet upload. This exists because Unit 08's
 * {@code assign-cm} and {@code reassign-expert} need an expert id and there was no way to
 * learn one, which left the board's most important action asking a Project Manager to type
 * a UUID. Unit 11 supersedes this with the real screen; the endpoint can stay as the
 * picker's read.
 *
 * <p>Only {@code AVAILABLE} experts, because {@code CaseLifecycleService.availableExpert}
 * refuses any other — offering one the transition would then reject is a worse experience
 * than not offering it. The optional {@code forCase} below applies that same rule to the
 * second thing {@code reassignExpert} refuses: the expert already on the case. Id and name only: {@code payment_detail} lives on this entity and
 * must never leave it, and the quality/performance fields are a Unit 11 concern with their
 * own audience.
 */
@RestController
@RequestMapping("/api/experts")
public class ExpertPickerController {

	public record ExpertOption(UUID id, String fullName) {
	}

	private final ExpertRepository experts;
	private final CaseRepository cases;

	ExpertPickerController(ExpertRepository experts, CaseRepository cases) {
		this.experts = experts;
		this.cases = cases;
	}

	/**
	 * The roles that put an expert on a case, plus the Expert Network Manager, whose Supply
	 * tier is the roster. A Case Manager is not here: they draft for the expert the PM chose.
	 */
	/**
	 * @param forCase the case this pick is for, which drops the expert already on it.
	 *
	 *                <p><strong>Optional, and the reason it exists is the paragraph above.</strong>
	 *                {@code reassignExpert} refuses "that is the expert who declined", so on a case
	 *                in {@code EXPERT_DECLINED_REMATCHING} this list was offering exactly one choice
	 *                the transition would answer 409 to — the failure this class's own javadoc
	 *                declares it exists to prevent, caught one filter short.
	 *
	 *                <p>Filtered here rather than in the client because the client does not have the
	 *                fact: {@code CaseBoardController.BoardCard} deliberately carries no
	 *                {@code expertId}, and widening every card in the app so one dialog can drop one
	 *                row is the wrong end to fix it from. {@code ExpertMatchService.shortlist}
	 *                applies the identical filter for the identical reason — this is the full picker
	 *                underneath it catching up, not a new rule.
	 *
	 *                <p>Read through {@code findScoped}, so naming a case outside the caller's scope
	 *                narrows nothing and reveals nothing: an unreadable id is treated as no case at
	 *                all rather than refused, since this parameter is a convenience and its absence
	 *                is already a legal call. A case with no expert yet — every {@code assign-cm} —
	 *                is a no-op, which is why the dialog can pass it unconditionally.
	 */
	@GetMapping
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER', 'EXPERT_NETWORK_MANAGER')")
	public ApiResponse<List<ExpertOption>> available(@RequestParam(required = false) UUID forCase) {
		TenantContext ctx = TenantContext.current();
		UUID onCase = forCase == null ? null
				: cases.findScoped(ctx, forCase).map(subject -> subject.getExpertId()).orElse(null);

		return ApiResponse.ok(experts.findScoped(ctx).stream()
				.filter(expert -> expert.getAvailability() == Availability.AVAILABLE)
				// `onCase` first: it is the nullable one, and an id-vs-id comparison the other way
				// round NPEs on an expert whose id is not yet assigned.
				.filter(expert -> onCase == null || !onCase.equals(expert.getId()))
				.sorted(Comparator.comparing(Expert::getFullName,
						Comparator.nullsLast(String::compareToIgnoreCase)))
				.map(expert -> new ExpertOption(expert.getId(), expert.getFullName()))
				.toList());
	}
}
