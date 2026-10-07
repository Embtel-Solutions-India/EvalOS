package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
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

	MemberTargetService(JdbcTemplate jdbc, TeamMemberRepository members) {
		this.jdbc = jdbc;
		this.members = members;
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
		TeamMember member = members.findById(teamMemberId)
				.orElseThrow(() -> new InvalidRequestException("That team member does not exist"));
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

	/** The newest target for this member and month in this brand; empty means "not set", which is not 0. */
	public Optional<BigDecimal> current(UUID brandId, UUID teamMemberId, LocalDate month) {
		return jdbc.query("SELECT amount FROM member_monthly_target WHERE brand_id = ? AND team_member_id = ? "
				+ "AND month = ? ORDER BY set_at DESC LIMIT 1", (rs, n) -> rs.getBigDecimal(1), brandId,
				teamMemberId, month.withDayOfMonth(1)).stream().findFirst();
	}
}
