package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.common.NotFoundException;
import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.TeamMemberRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A monthly target per sales or marketing member (D75, Unit 77). Append-only, like the brand goal
 * ({@code GmOverviewService#setGoal}): the newest row for (member, month) is the target, and the
 * rows before it are the history of who moved it.
 */
@Service
public class MemberTargetService {

	/** What a target counts. The member's role decides it; the caller never does. */
	public enum TargetKind {
		WON_VALUE, LEADS
	}

	private final JdbcTemplate jdbc;
	private final TeamMemberRepository members;
	private final SellingBrand sellingBrand;

	MemberTargetService(JdbcTemplate jdbc, TeamMemberRepository members, SellingBrand sellingBrand) {
		this.jdbc = jdbc;
		this.members = members;
		this.sellingBrand = sellingBrand;
	}

	/** Sales are held to what they win, Marketing to the leads they open; no other role has a target. */
	public static Optional<TargetKind> kindOf(Role role) {
		return switch (role) {
			case SALES -> Optional.of(TargetKind.WON_VALUE);
			case MARKETING -> Optional.of(TargetKind.LEADS);
			default -> Optional.empty();
		};
	}

	/** Appends a target for one member and month. The brand is the member's own, never the caller's input. */
	@Transactional
	public void set(UUID teamMemberId, LocalDate month, BigDecimal amount, UUID setBy) {
		// Targets are read from the selling brand's desks only, so a row for anyone else, or for someone
		// no longer on a desk, would be stored and never shown. They are out of scope, not a bad request.
		TeamMember member = members.findById(teamMemberId)
				.filter((found) -> found.isActive() && sellingBrand.id() != null && sellingBrand.id().equals(found.getBrandId()))
				.orElseThrow(() -> new NotFoundException("That team member is not on a desk with a target"));
		TargetKind kind = kindOf(member.getRole()).orElseThrow(
				() -> new InvalidRequestException("Only Sales and Marketing members have a monthly target"));
		if (amount == null || amount.signum() < 0) {
			throw new InvalidRequestException("A monthly target is zero or more");
		}
		if (kind == TargetKind.LEADS && amount.stripTrailingZeros().scale() > 0) {
			throw new InvalidRequestException("A leads target is a whole number");
		}
		jdbc.update("INSERT INTO member_monthly_target (brand_id, team_member_id, month, kind, amount, set_by) "
				+ "VALUES (?, ?, ?, ?, ?, ?)", member.getBrandId(), teamMemberId, month.withDayOfMonth(1),
				kind.name(), amount, setBy);
	}

	/**
	 * One member's target against what their desk did.
	 *
	 * @param target   null when nobody set one — "not set", never 0
	 * @param progress won value for a Sales member, new leads for a Marketing member: the same
	 *                 per-desk figures the GM overview shows, so the two cannot disagree
	 */
	public record TargetRow(UUID memberId, String name, Role role, TargetKind kind, BigDecimal target,
			BigDecimal progress) {
	}

	/** A row per desk whose role has a target, in the order the overview lists them. */
	public List<TargetRow> overview(UUID brandId, LocalDate month, List<GmOverviewService.DeskRow> desks) {
		Map<UUID, BigDecimal> targets = latestForMonth(brandId, month);
		return desks.stream()
				.flatMap((desk) -> kindOf(desk.role()).stream().map((kind) -> new TargetRow(desk.memberId(),
						desk.name(), desk.role(), kind,
						targets.get(desk.memberId()),
						kind == TargetKind.WON_VALUE ? desk.wonValue() : BigDecimal.valueOf(desk.newLeads()))))
				.toList();
	}

	/**
	 * Every member's newest target for the month in one read, keyed by member. A member with no row is
	 * absent from the map, which is "not set" and not 0.
	 */
	public Map<UUID, BigDecimal> latestForMonth(UUID brandId, LocalDate month) {
		Map<UUID, BigDecimal> latest = new HashMap<>();
		jdbc.query("SELECT DISTINCT ON (team_member_id) team_member_id, amount FROM member_monthly_target "
				+ "WHERE brand_id = ? AND month = ? ORDER BY team_member_id, set_at DESC",
				(rs) -> {
					latest.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
				}, brandId, month.withDayOfMonth(1));
		return latest;
	}

	/** The newest target for this member and month in this brand; empty means "not set", which is not 0. */
	public Optional<BigDecimal> current(UUID brandId, UUID teamMemberId, LocalDate month) {
		return jdbc.query("SELECT amount FROM member_monthly_target WHERE brand_id = ? AND team_member_id = ? "
				+ "AND month = ? ORDER BY set_at DESC LIMIT 1", (rs, n) -> rs.getBigDecimal(1), brandId,
				teamMemberId, month.withDayOfMonth(1)).stream().findFirst();
	}
}
