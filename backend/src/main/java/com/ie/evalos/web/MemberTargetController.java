package com.ie.evalos.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.common.DateWindow;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.common.NotFoundException;
import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.BusinessCalendar;
import com.ie.evalos.service.GmOverviewService;
import com.ie.evalos.service.MemberTargetService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Monthly targets for Sales and Marketing members (D75, Unit 77).
 *
 * <p>The GM lists and sets them; a member reads <strong>only their own</strong>, taken from the
 * token and never from a parameter. Progress is the GM overview's per-desk figure for the same
 * month, so a target and the desk row beside it cannot disagree.
 *
 * <p>Each read asks {@link GmOverviewService} for the month, which is a GHL read per request.
 * ponytail: no cache yet; add a short per-month one if a member's board load proves slow.
 */
@RestController
@RequestMapping("/api")
public class MemberTargetController {

	/** {@code month} is any day of the month; the first is what is stored. */
	public record SetRequest(@NotNull LocalDate month, @NotNull @PositiveOrZero BigDecimal amount) {
	}

	private final MemberTargetService targets;
	private final GmOverviewService overview;
	private final SellingBrand sellingBrand;

	MemberTargetController(MemberTargetService targets, GmOverviewService overview, SellingBrand sellingBrand) {
		this.targets = targets;
		this.overview = overview;
		this.sellingBrand = sellingBrand;
	}

	@GetMapping("/gm/targets")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<List<MemberTargetService.TargetRow>> list(@RequestParam String month) {
		return ApiResponse.ok(rowsFor(parse(month)));
	}

	@PutMapping("/gm/targets/{memberId}")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<Void> set(@PathVariable UUID memberId, @Valid @RequestBody SetRequest request,
			@AuthenticationPrincipal StaffPrincipal principal) {
		targets.set(memberId, request.month(), request.amount(), principal.memberId());
		return ApiResponse.ok(null);
	}

	@GetMapping("/me/target")
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING')")
	public ApiResponse<MemberTargetService.TargetRow> mine(@RequestParam String month,
			@AuthenticationPrincipal StaffPrincipal principal) {
		return rowsFor(parse(month)).stream()
				.filter((row) -> row.memberId().equals(principal.memberId()))
				.findFirst()
				.map(ApiResponse::ok)
				.orElseThrow(() -> new NotFoundException("No monthly target applies to you"));
	}

	private List<MemberTargetService.TargetRow> rowsFor(LocalDate first) {
		DateWindow window = DateWindow.of("custom", first.toString(), first.plusMonths(1).minusDays(1).toString(),
				BusinessCalendar.clock());
		return targets.overview(sellingBrand.id(), first, overview.forCaller(window, null).desks());
	}

	private static LocalDate parse(String month) {
		try {
			return YearMonth.parse(month.length() > 7 ? month.substring(0, 7) : month).atDay(1);
		}
		catch (DateTimeParseException malformed) {
			throw new InvalidRequestException("month must be yyyy-MM");
		}
	}
}
