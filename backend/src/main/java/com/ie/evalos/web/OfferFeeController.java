package com.ie.evalos.web;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.OfferFeeService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The fee on a case's current expert offer (Unit 65).
 *
 * <p>The read has no role list, like {@code GET /api/cases/{id}}: every production role may open a
 * case, and which cases is the service's scoped load. The edit is the fee setters only, pinned to
 * {@link OfferFeeService#MAY_SET_FEE} by {@code OfferFeeControllerTest} and re-checked in the service.
 */
@RestController
@RequestMapping("/api/cases/{id}/expert/offer")
public class OfferFeeController {

	public record FeeRequest(@NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal fee) {
	}

	private final OfferFeeService fees;

	OfferFeeController(OfferFeeService fees) {
		this.fees = fees;
	}

	/** The case's latest offer; {@code data} is null when nobody has been offered it yet. */
	@GetMapping
	public ApiResponse<OfferFeeService.OfferView> current(@PathVariable UUID id) {
		return ApiResponse.ok(fees.current(id).orElse(null));
	}

	@PatchMapping("/fee")
	@PreAuthorize("hasAnyRole('GM', 'PROJECT_MANAGER', 'PROJECT_COORDINATOR', 'EXPERT_NETWORK_MANAGER')")
	public ApiResponse<OfferFeeService.OfferView> editFee(@PathVariable UUID id,
			@Valid @RequestBody FeeRequest request) {
		return ApiResponse.ok(fees.editFee(id, request.fee()));
	}
}
