package com.ie.evalos.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.service.PayoutService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payout ledger: what each delivered draft owes an expert, and the week somebody
 * works down on payout day.
 *
 * <p><strong>No DTO here declares {@code paymentDetail}</strong> — not blanked, not
 * masked, <em>not a member</em> (invariant 4). An ENM who needs an expert's bank
 * details looks nowhere, because there is no read path anywhere in EvalOS.
 *
 * <p>Thin, like every controller: validate, authorize, call {@link PayoutService},
 * return a DTO in {@link ApiResponse}. Every rule lives in the service, which re-checks
 * the role itself — {@code @PreAuthorize} guards a route, the service guards the
 * operation.
 */
@RestController
@RequestMapping("/api/payouts")
public class PayoutController {

	/**
	 * Who works the ledger. Reads and writes are the same three roles, so this is one
	 * constant rather than two: the ENM sends the transfer and records it, and the GM and
	 * Brand Manager keep the oversight they have everywhere else. A PM, a Coordinator and
	 * a Case Manager are absent — none of them pays anybody.
	 */
	private static final String PAYOUTS = "hasAnyRole('GM', 'BRAND_MANAGER', 'EXPERT_NETWORK_MANAGER')";

	private final PayoutService payouts;

	PayoutController(PayoutService payouts) {
		this.payouts = payouts;
	}

	/** The one field a draft's amount can be corrected to, before anything settles it. */
	public record CorrectAmountRequest(
			@NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
	}

	/**
	 * The flat, filterable ledger (spec 16b) — every row in scope, narrowed by whichever
	 * filters are given. Distinct from {@link #batch}, which groups one week's
	 * {@code PENDING} rows by expert: this is the read behind, for instance, one expert's
	 * pending drafts across every week.
	 */
	@GetMapping
	@PreAuthorize(PAYOUTS)
	public ApiResponse<List<PayoutService.LedgerRow>> list(
			@RequestParam(required = false) PayoutStatus status,
			@RequestParam(required = false) UUID expertId,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate weekOf,
			@RequestParam(required = false, defaultValue = "false") boolean overdue) {
		return ApiResponse.ok(payouts.list(status, expertId, weekOf, overdue));
	}

	@GetMapping("/{id}")
	@PreAuthorize(PAYOUTS)
	public ApiResponse<PayoutService.LedgerRow> one(@PathVariable UUID id) {
		return ApiResponse.ok(payouts.payout(id));
	}

	@GetMapping("/batch")
	@PreAuthorize(PAYOUTS)
	public ApiResponse<PayoutService.BatchView> batch(
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate weekOf) {
		return ApiResponse.ok(payouts.batch(weekOf));
	}

	/** Corrects a still-{@code PENDING} draft's amount, audited, then answers the refreshed row. */
	@PatchMapping("/{id}")
	@PreAuthorize(PAYOUTS)
	public ApiResponse<PayoutService.LedgerRow> correctAmount(@PathVariable UUID id,
			@Valid @RequestBody CorrectAmountRequest request) {
		payouts.correctAmount(id, request.amount());
		return ApiResponse.ok(payouts.payout(id));
	}

	@PostMapping("/settle")
	@PreAuthorize(PAYOUTS)
	public ApiResponse<Map<String, UUID>> settle(@Valid @RequestBody PayoutService.SettleForm form) {
		return ApiResponse.ok(Map.of("paymentId", payouts.settle(form)));
	}
}
