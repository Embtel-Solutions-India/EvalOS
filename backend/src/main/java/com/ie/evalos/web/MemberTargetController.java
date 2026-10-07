package com.ie.evalos.web;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
import jakarta.validation.constraints.Digits;
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
 * <p>A member's line needs the month's desk figures, which {@link GmOverviewService} reads from GHL
 * live (two reads per pipeline, for every desk). Every Sales and Marketing board mounts it and every
 * tab focus re-reads it, and a 429 pauses the shared pacer for the whole location, so the month's
 * desks are kept for {@link #TTL}: a minute of members costs one overview. A failed read is not kept.
 */
@RestController
@RequestMapping("/api")
public class MemberTargetController {

	/** {@code month} is any day of the month; the first is what is stored. */
	public record SetRequest(@NotNull LocalDate month,
			@NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal amount) {
	}

	/** How long a month's desk figures are reused by the member view. */
	static final Duration TTL = Duration.ofSeconds(60);

	private record Kept(List<GmOverviewService.DeskRow> desks, Instant at) {
	}

	private final Map<LocalDate, Kept> kept = new ConcurrentHashMap<>();
	private final MemberTargetService targets;
	private final GmOverviewService overview;
	private final SellingBrand sellingBrand;

	MemberTargetController(MemberTargetService targets, GmOverviewService overview, SellingBrand sellingBrand) {
		this.targets = targets;
		this.overview = overview;
		this.sellingBrand = sellingBrand;
	}

	/** A member's target for the month. A member with none is absent from the list: "not set", not 0. */
	public record TargetAmount(UUID memberId, BigDecimal target) {
	}

	/**
	 * The month's targets, amounts only and no GHL read: the GM dashboard already holds each desk's
	 * progress from the overview it loaded, and asking for it again would double that read.
	 */
	@GetMapping("/gm/targets")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<List<TargetAmount>> list(@RequestParam String month) {
		return ApiResponse.ok(targets.latestForMonth(sellingBrand.id(), parse(month)).entrySet().stream()
				.map((entry) -> new TargetAmount(entry.getKey(), entry.getValue())).toList());
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
	public ApiResponse<MemberTargetService.TargetRow> mine(@RequestParam(required = false) String month,
			@AuthenticationPrincipal StaffPrincipal principal) {
		// No month means this month by the business calendar, the one the GM's dashboard counts in; a
		// browser's own clock is up to a day ahead of it for a team in another zone.
		LocalDate first = month == null ? LocalDate.now(BusinessCalendar.clock()).withDayOfMonth(1) : parse(month);
		return rowsFor(first).stream()
				.filter((row) -> row.memberId().equals(principal.memberId()))
				.findFirst()
				.map(ApiResponse::ok)
				.orElseThrow(() -> new NotFoundException("No monthly target applies to you"));
	}

	/** Forgets every kept month. Public because the controller is proxied for {@code @PreAuthorize}. */
	public void clearCache() {
		kept.clear();
	}

	private List<MemberTargetService.TargetRow> rowsFor(LocalDate first) {
		Kept held = kept.get(first);
		if (held == null || Duration.between(held.at(), Instant.now()).compareTo(TTL) >= 0) {
			DateWindow window = DateWindow.of("custom", first.toString(),
					first.plusMonths(1).minusDays(1).toString(), BusinessCalendar.clock());
			held = new Kept(overview.forCaller(window, null).desks(), Instant.now());
			kept.put(first, held);
		}
		return targets.overview(sellingBrand.id(), first, held.desks());
	}

	private static LocalDate parse(String month) {
		try {
			return YearMonth.parse(month).atDay(1);
		}
		catch (DateTimeParseException malformed) {
			throw new InvalidRequestException("month must be yyyy-MM");
		}
	}
}
