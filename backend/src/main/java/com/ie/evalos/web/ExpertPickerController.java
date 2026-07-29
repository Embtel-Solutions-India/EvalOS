package com.ie.evalos.web;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
 * than not offering it. Id and name only: {@code payment_detail} lives on this entity and
 * must never leave it, and the quality/performance fields are a Unit 11 concern with their
 * own audience.
 */
@RestController
@RequestMapping("/api/experts")
public class ExpertPickerController {

	public record ExpertOption(UUID id, String fullName) {
	}

	private final ExpertRepository experts;

	ExpertPickerController(ExpertRepository experts) {
		this.experts = experts;
	}

	/**
	 * The roles that put an expert on a case, plus the Expert Network Manager, whose Supply
	 * tier is the roster. A Case Manager is not here: they draft for the expert the PM chose.
	 */
	@GetMapping
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER', 'EXPERT_NETWORK_MANAGER')")
	public ApiResponse<List<ExpertOption>> available() {
		return ApiResponse.ok(experts.findScoped(TenantContext.current()).stream()
				.filter(expert -> expert.getAvailability() == Availability.AVAILABLE)
				.sorted(Comparator.comparing(Expert::getFullName,
						Comparator.nullsLast(String::compareToIgnoreCase)))
				.map(expert -> new ExpertOption(expert.getId(), expert.getFullName()))
				.toList());
	}
}
