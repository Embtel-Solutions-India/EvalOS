package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.PipelineStage;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.GhlCustomFieldRepository;
import com.ie.evalos.repository.TeamMemberPipelineRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Sales and Marketing dashboards' one read: a year of leads by month and source, the open
 * pipeline by stage, and this month's target — for a whole audience or for one desk.
 *
 * <p><strong>Drawn from the local mirror, never from GHL.</strong> {@code GmOverviewService} asks GHL
 * live and pays a paced request per desk; a dashboard a salesperson leaves open cannot. The mirror
 * already carries everything a monthly series needs ({@code ghl_created_at}, status, amount, source,
 * stage), so this costs no rate budget and cannot answer differently from the board beside it.
 *
 * <p><strong>The scope is decided here, from the principal, and the request only narrows it.</strong>
 * A SALES or MARKETING caller reads their own pipelines and nothing else — naming another member is a
 * refusal, not an empty answer (D19c). The GM reads one audience's desks, or one desk of it.
 */
@Service
public class PipelineJourneyService {

	static final String LEAD_SOURCE_FIELD = "opportunity.lead_source";
	static final String UNATTRIBUTED = "Unattributed";

	/** One month of the year. {@code leads} are deals opened in it; {@code won} are deals closed won in it. */
	public record MonthRow(int month, int leads, BigDecimal leadValue, int won, BigDecimal wonValue) {
	}

	/** A source's year, with its leads per month so the chart can compare channels without a second read. */
	public record SourceRow(String source, int leads, BigDecimal value, List<Integer> monthlyLeads) {
	}

	/** An open-deal stage, in GHL's order. A snapshot of now, not bounded by the year. */
	public record StageRow(String stageId, String name, int position, int deals, BigDecimal value) {
	}

	/** {@code kind} is {@code WON_VALUE} or {@code LEADS}; {@code target} is null for "not set", never 0. */
	public record TargetRow(MemberTargetService.TargetKind kind, LocalDate month, BigDecimal target,
			BigDecimal progress) {
	}

	/** One desk, for the GM's drill-down list. */
	public record DeskRow(UUID memberId, String name, int leads, BigDecimal leadValue, int won,
			BigDecimal wonValue, int open, BigDecimal openValue) {
	}

	public record Member(UUID memberId, String name) {
	}

	public record Journey(int year, String audience, Member member, List<MonthRow> months,
			List<SourceRow> sources, List<StageRow> stages, TargetRow target, List<DeskRow> desks) {
	}

	/** A mirrored deal reduced to what the arithmetic needs, so the arithmetic is testable without a database. */
	record Deal(Instant createdAt, String status, BigDecimal amount, String source, String stageId,
			Instant statusChangedAt) {
	}

	record Aggregate(List<MonthRow> months, List<SourceRow> sources, Map<String, int[]> stageCounts,
			Map<String, BigDecimal> stageValues) {
	}

	private final OpportunityMirrorService mirror;
	private final PipelineMirrorService pipelines;
	private final TeamMemberPipelineRepository assignments;
	private final TeamMemberRepository teamMembers;
	private final GhlCustomFieldRepository customFields;
	private final MemberTargetService memberTargets;
	private final GmOverviewService gm;
	private final UUID sellingBrandId;

	PipelineJourneyService(OpportunityMirrorService mirror, PipelineMirrorService pipelines,
			TeamMemberPipelineRepository assignments, TeamMemberRepository teamMembers,
			GhlCustomFieldRepository customFields, MemberTargetService memberTargets, GmOverviewService gm,
			SellingBrand sellingBrand) {
		this.mirror = mirror;
		this.pipelines = pipelines;
		this.assignments = assignments;
		this.teamMembers = teamMembers;
		this.customFields = customFields;
		this.memberTargets = memberTargets;
		this.gm = gm;
		this.sellingBrandId = sellingBrand.id();
	}

	/**
	 * @param audience {@code sales} or {@code marketing}; the GM chooses, anyone else is fixed by role
	 * @param memberId one desk to scope to; only the GM may name someone other than themselves
	 * @param source   narrows months and stages to one source; the per-source series stays whole so the
	 *                 chart can still compare channels
	 */
	@Transactional(readOnly = true)
	public Journey forCaller(Integer year, String audience, UUID memberId, String source) {
		TenantContext caller = TenantContext.current();
		LocalDate today = LocalDate.now(BusinessCalendar.clock());
		int shown = year == null ? today.getYear() : year;
		if (shown < 2000 || shown > today.getYear() + 1) {
			throw new InvalidRequestException("That year is out of range");
		}

		Role role = roleFor(caller, audience);
		boolean gmView = caller.role() == Role.GM;
		List<TeamMember> desks = sellingBrandId == null ? List.of()
				: teamMembers.findByActiveTrueAndRoleAndBrandId(role, sellingBrandId).stream()
						.filter((member) -> !assignments.ghlIdsFor(member.getId()).isEmpty())
						.sorted(Comparator.comparing(TeamMember::getDisplayName))
						.toList();

		List<TeamMember> scope;
		Member selected = null;
		if (gmView) {
			if (memberId == null) {
				scope = desks;
			}
			else {
				TeamMember one = desks.stream().filter((desk) -> desk.getId().equals(memberId)).findFirst()
						.orElseThrow(() -> new InvalidRequestException("That member is not on a " + role.name().toLowerCase(Locale.ROOT) + " desk"));
				scope = List.of(one);
				selected = new Member(one.getId(), one.getDisplayName());
			}
		}
		else {
			if (memberId != null && !memberId.equals(caller.memberId())) {
				throw new ForbiddenException("You can read your own dashboard only.");
			}
			scope = desks.stream().filter((desk) -> desk.getId().equals(caller.memberId())).toList();
			if (scope.isEmpty()) {
				throw new ForbiddenException("You have no GHL pipeline assigned. A GM assigns one.");
			}
			selected = new Member(scope.getFirst().getId(), scope.getFirst().getDisplayName());
		}

		String leadSourceFieldId = leadSourceFieldId();
		Map<String, String> stageNames = new HashMap<>();
		Map<String, Integer> stagePositions = new HashMap<>();

		List<Deal> deals = dealsOf(scope, leadSourceFieldId, stageNames, stagePositions);
		ZoneId zone = BusinessCalendar.ZONE;
		Aggregate all = aggregate(deals, shown, zone, source);
		Aggregate whole = aggregate(deals, shown, zone, null);

		List<StageRow> stages = all.stageCounts().entrySet().stream()
				.map((entry) -> new StageRow(entry.getKey(), stageNames.getOrDefault(entry.getKey(), entry.getKey()),
						stagePositions.getOrDefault(entry.getKey(), Integer.MAX_VALUE), entry.getValue()[0],
						all.stageValues().get(entry.getKey())))
				.sorted(Comparator.comparingInt(StageRow::position))
				.toList();

		LocalDate month = today.withDayOfMonth(1);
		Aggregate thisYear = shown == today.getYear() ? whole : aggregate(deals, today.getYear(), zone, null);
		List<DeskRow> deskRows = gmView && selected == null ? deskRows(desks, leadSourceFieldId, shown, zone) : List.of();

		return new Journey(shown, role == Role.SALES ? "sales" : "marketing", selected, all.months(),
				whole.sources(), stages, target(role, scope, selected != null, month, thisYear), deskRows);
	}

	private static Role roleFor(TenantContext caller, String audience) {
		if (caller.role() == Role.SALES || caller.role() == Role.MARKETING) {
			return caller.role();
		}
		return "marketing".equalsIgnoreCase(audience) ? Role.MARKETING : Role.SALES;
	}

	private String leadSourceFieldId() {
		if (sellingBrandId == null) {
			return null;
		}
		return customFields.findByBrandIdAndModelOrderByNameAsc(sellingBrandId, ReferenceMirrorService.OPPORTUNITY_MODEL)
				.stream().filter((field) -> LEAD_SOURCE_FIELD.equals(field.getFieldKey()))
				.map((field) -> field.getGhlId()).findFirst().orElse(null);
	}

	private List<Deal> dealsOf(List<TeamMember> scope, String leadSourceFieldId, Map<String, String> stageNames,
			Map<String, Integer> stagePositions) {
		List<String> ghlIds = scope.stream().flatMap((member) -> assignments.ghlIdsFor(member.getId()).stream())
				.distinct().toList();
		pipelines.all().stream().filter((pipeline) -> ghlIds.contains(pipeline.getGhlId()))
				.flatMap((pipeline) -> pipelines.stagesOf(pipeline.getId()).stream())
				.filter(PipelineStage::isLive)
				.forEach((stage) -> {
					stageNames.putIfAbsent(stage.getGhlId(), stage.getName());
					stagePositions.putIfAbsent(stage.getGhlId(), stage.getPosition());
				});

		List<Deal> deals = new ArrayList<>();
		for (Opportunity row : mirror.onPipelines(ghlIds)) {
			String source = row.getSource();
			if (source == null || source.isBlank()) {
				source = leadSourceFieldId == null ? null : row.getCustomFields().get(leadSourceFieldId);
			}
			deals.add(new Deal(row.getGhlCreatedAt(), row.getStatus(), row.getAmount(), source,
					row.getGhlStageId(), row.getLastStatusChangeAt()));
		}
		return deals;
	}

	private List<DeskRow> deskRows(List<TeamMember> desks, String leadSourceFieldId, int year, ZoneId zone) {
		List<DeskRow> rows = new ArrayList<>();
		for (TeamMember desk : desks) {
			Aggregate one = aggregate(dealsOf(List.of(desk), leadSourceFieldId, new HashMap<>(), new HashMap<>()), year, zone, null);
			int leads = one.months().stream().mapToInt(MonthRow::leads).sum();
			int won = one.months().stream().mapToInt(MonthRow::won).sum();
			int open = one.stageCounts().values().stream().mapToInt((count) -> count[0]).sum();
			rows.add(new DeskRow(desk.getId(), desk.getDisplayName(), leads,
					one.months().stream().map(MonthRow::leadValue).reduce(BigDecimal.ZERO, BigDecimal::add), won,
					one.months().stream().map(MonthRow::wonValue).reduce(BigDecimal.ZERO, BigDecimal::add), open,
					one.stageValues().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
		}
		return rows;
	}

	/**
	 * This month against its target. A desk reads its own row; the team reads the brand goal (Sales) or
	 * the sum of the targets its members have set (Marketing) — the same figures the GM overview shows.
	 */
	private TargetRow target(Role role, List<TeamMember> scope, boolean oneDesk, LocalDate month, Aggregate current) {
		MemberTargetService.TargetKind kind = MemberTargetService.kindOf(role).orElseThrow();
		MonthRow row = current.months().get(month.getMonthValue() - 1);
		BigDecimal progress = kind == MemberTargetService.TargetKind.WON_VALUE ? row.wonValue()
				: BigDecimal.valueOf(row.leads());
		if (sellingBrandId == null) {
			return new TargetRow(kind, month, null, progress);
		}
		BigDecimal target;
		if (oneDesk) {
			target = memberTargets.current(sellingBrandId, scope.getFirst().getId(), month).orElse(null);
		}
		else if (kind == MemberTargetService.TargetKind.WON_VALUE) {
			target = gm.goalFor(month);
		}
		else {
			Map<UUID, BigDecimal> set = memberTargets.latestForMonth(sellingBrandId, month);
			List<BigDecimal> mine = scope.stream().map((member) -> set.get(member.getId()))
					.filter((amount) -> amount != null).toList();
			target = mine.isEmpty() ? null : mine.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		}
		return new TargetRow(kind, month, target, progress);
	}

	/** The arithmetic. Static and database-free so {@code PipelineJourneyServiceTest} can pin it. */
	static Aggregate aggregate(List<Deal> deals, int year, ZoneId zone, String source) {
		int[] leads = new int[12];
		int[] won = new int[12];
		BigDecimal[] leadValue = zeros();
		BigDecimal[] wonValue = zeros();
		Map<String, int[]> bySourceMonth = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		Map<String, BigDecimal> bySourceValue = new HashMap<>();
		Map<String, int[]> stageCounts = new LinkedHashMap<>();
		Map<String, BigDecimal> stageValues = new LinkedHashMap<>();

		for (Deal deal : deals) {
			String name = deal.source() == null || deal.source().isBlank() ? UNATTRIBUTED : deal.source().trim();
			boolean matches = source == null || source.isBlank() || name.equalsIgnoreCase(source.trim());
			BigDecimal amount = deal.amount() == null ? BigDecimal.ZERO : deal.amount();

			if (deal.createdAt() != null) {
				var created = deal.createdAt().atZone(zone);
				if (created.getYear() == year) {
					int m = created.getMonthValue() - 1;
					// Per-source series ignore the source filter: they are what the filter chooses from.
					bySourceMonth.computeIfAbsent(name, (key) -> new int[12])[m]++;
					bySourceValue.merge(name.toLowerCase(Locale.ROOT), amount, BigDecimal::add);
					if (matches) {
						leads[m]++;
						leadValue[m] = leadValue[m].add(amount);
					}
				}
			}
			boolean isWon = "won".equalsIgnoreCase(deal.status());
			if (matches && isWon && deal.statusChangedAt() != null) {
				var closed = deal.statusChangedAt().atZone(zone);
				if (closed.getYear() == year) {
					int m = closed.getMonthValue() - 1;
					won[m]++;
					wonValue[m] = wonValue[m].add(amount);
				}
			}
			boolean open = deal.status() == null || deal.status().isBlank() || "open".equalsIgnoreCase(deal.status());
			if (matches && open && deal.stageId() != null) {
				stageCounts.computeIfAbsent(deal.stageId(), (key) -> new int[1])[0]++;
				stageValues.merge(deal.stageId(), amount, BigDecimal::add);
			}
		}

		List<MonthRow> months = new ArrayList<>();
		for (int m = 0; m < 12; m++) {
			months.add(new MonthRow(m + 1, leads[m], leadValue[m], won[m], wonValue[m]));
		}
		List<SourceRow> sources = bySourceMonth.entrySet().stream().map((entry) -> {
			int total = java.util.Arrays.stream(entry.getValue()).sum();
			List<Integer> monthly = java.util.Arrays.stream(entry.getValue()).boxed().toList();
			return new SourceRow(entry.getKey(), total, bySourceValue.get(entry.getKey().toLowerCase(Locale.ROOT)), monthly);
		}).sorted(Comparator.comparingInt(SourceRow::leads).reversed().thenComparing(SourceRow::source)).toList();
		return new Aggregate(months, sources, stageCounts, stageValues);
	}

	private static BigDecimal[] zeros() {
		BigDecimal[] values = new BigDecimal[12];
		java.util.Arrays.fill(values, BigDecimal.ZERO);
		return values;
	}
}
