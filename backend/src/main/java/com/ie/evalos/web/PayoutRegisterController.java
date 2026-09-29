package com.ie.evalos.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.OfferLog;
import com.ie.evalos.service.PayoutRegisterService;
import com.ie.evalos.service.PayoutRegisterService.Filter;
import com.ie.evalos.service.PayoutRegisterService.RegisterStatus;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Payouts module's reads (Unit 65): the cases register and its CSV, one offer's log, the
 * per-expert totals and the overview. The same three roles as every other payout route, pinned to
 * {@code PayoutService.MAY_RECORD} by {@code PayoutRegisterControllerTest}.
 */
@RestController
@RequestMapping("/api/payouts")
@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'EXPERT_NETWORK_MANAGER')")
public class PayoutRegisterController {

	private final PayoutRegisterService register;

	PayoutRegisterController(PayoutRegisterService register) {
		this.register = register;
	}

	@GetMapping("/cases")
	public ApiResponse<List<PayoutRegisterService.RegisterRow>> cases(
			@RequestParam(required = false) RegisterStatus status, @RequestParam(required = false) UUID expertId,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
			@RequestParam(required = false) String q) {
		return ApiResponse.ok(register.rows(new Filter(status, expertId, from, to, q)));
	}

	/** The register exactly as filtered, as a CSV download. */
	@GetMapping(value = "/cases/export", produces = "text/csv")
	public ResponseEntity<String> export(@RequestParam(required = false) RegisterStatus status,
			@RequestParam(required = false) UUID expertId,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
			@RequestParam(required = false) String q) {
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payout-cases.csv\"")
				.body(register.exportCsv(new Filter(status, expertId, from, to, q)));
	}

	@GetMapping("/cases/{offerId}/history")
	public ApiResponse<List<OfferLog.Entry>> history(@PathVariable UUID offerId) {
		return ApiResponse.ok(register.history(offerId));
	}

	@GetMapping("/experts")
	public ApiResponse<List<PayoutRegisterService.ExpertTotals>> experts() {
		return ApiResponse.ok(register.experts());
	}

	@GetMapping("/overview")
	public ApiResponse<List<PayoutRegisterService.Overview>> overview(
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
		return ApiResponse.ok(register.overview(from, to));
	}
}
