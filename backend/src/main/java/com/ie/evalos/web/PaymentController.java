package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.PayoutService;

import jakarta.validation.Valid;

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
 * Payments recorded against the ledger: one transfer per row, and every draft it
 * settled.
 *
 * <p><strong>No DTO here declares {@code paymentDetail}</strong> — same invariant as
 * {@link PayoutController}, for the same reason: nothing this controller returns
 * touches an expert's bank details, because nothing in {@link PayoutService}'s payment
 * projections ever reads that column.
 *
 * <p>Thin, like every controller: validate, authorize, call {@link PayoutService},
 * return a DTO in {@link ApiResponse}. The service re-checks the role itself —
 * {@code @PreAuthorize} guards a route, the service guards the operation.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

	/**
	 * Declared again rather than shared with {@link PayoutController} — each controller
	 * states its own gate, as {@code ExpertController} does — but it is the same three
	 * roles: reads and writes on a payment are the same act as on the draft it settled.
	 */
	private static final String PAYOUTS = "hasAnyRole('GM', 'BRAND_MANAGER', 'EXPERT_NETWORK_MANAGER')";

	private final PayoutService payouts;

	PaymentController(PayoutService payouts) {
		this.payouts = payouts;
	}

	@GetMapping
	@PreAuthorize(PAYOUTS)
	public ApiResponse<List<PayoutService.PaymentRow>> history(@RequestParam UUID expertId) {
		return ApiResponse.ok(payouts.history(expertId));
	}

	@GetMapping("/{id}")
	@PreAuthorize(PAYOUTS)
	public ApiResponse<PayoutService.PaymentDetailView> one(@PathVariable UUID id) {
		return ApiResponse.ok(payouts.payment(id));
	}

	/** Corrects how an unconfirmed transfer was described, audited, then answers the refreshed payment. */
	@PatchMapping("/{id}")
	@PreAuthorize(PAYOUTS)
	public ApiResponse<PayoutService.PaymentDetailView> edit(@PathVariable UUID id,
			@Valid @RequestBody PayoutService.PaymentEditForm form) {
		payouts.editPayment(id, form);
		return ApiResponse.ok(payouts.payment(id));
	}

	/** The expert acknowledged the transfer; cascades to every draft it settled. */
	@PostMapping("/{id}/confirm")
	@PreAuthorize(PAYOUTS)
	public ApiResponse<PayoutService.PaymentDetailView> confirm(@PathVariable UUID id) {
		payouts.confirm(id);
		return ApiResponse.ok(payouts.payment(id));
	}
}
