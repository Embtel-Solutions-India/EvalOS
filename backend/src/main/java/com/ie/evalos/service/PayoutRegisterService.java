package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ie.evalos.common.NotFoundException;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutPayment;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.PayoutPaymentRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Payouts module's reads (Unit 65): every case's fee and where it stands, who is owed what,
 * and the attention list. Read-only; writes stay in {@link PayoutService} and {@link OfferFeeService}.
 *
 * <p>One row per offer that has a fee <b>or</b> a non-voided payout: an accepted offer pairs with
 * its case's payout for the same expert, and a payout with no accepted offer (pre-V79, or a
 * staff-recorded signature) still appears, so nothing owed is missing.
 *
 * <p>ponytail: loads the brand's offers and payouts and joins in memory, as {@link PayoutService}
 * already does; push the join into SQL if a brand passes ~10k offers.
 */
@Service
public class PayoutRegisterService {

	/** A transfer the expert has not confirmed after this long is on the attention list. */
	public static final int CONFIRM_NUDGE_DAYS = 7;

	public enum RegisterStatus {
		OFFERED, ACCEPTED, DECLINED, TIMED_OUT, SUPERSEDED, PENDING, PROCESSING, PAID
	}

	/** One case's fee and where it stands. {@code offerId} is null for a payout with no offer on record. */
	public record RegisterRow(UUID offerId, UUID payoutId, UUID caseId, String caseCode, UUID expertId,
			String expertName, BigDecimal amount, String currency, RegisterStatus status, String feeSetByName,
			Instant feeSetAt, Instant offeredAt, Instant dueDate, Instant sentAt, Instant confirmedAt, boolean done) {
	}

	/** Every field optional; {@code from} / {@code to} bound the offer date, inclusive. */
	public record Filter(RegisterStatus status, UUID expertId, LocalDate from, LocalDate to, String q) {
		public static Filter none() {
			return new Filter(null, null, null, null, null);
		}
	}

	public record ExpertTotals(UUID expertId, String expertName, String currency, BigDecimal committed,
			BigDecimal pending, BigDecimal processing, BigDecimal paid, Instant oldestPendingDue) {
	}

	public record Tile(int count, BigDecimal amount) {
	}

	/** One currency's position. A GM across brands gets one per currency, never a sum across them. */
	public record Overview(String currency, Tile committed, Tile pending, Tile processing, Tile paid,
			List<RegisterRow> attention) {
	}

	private final ExpertCaseOfferRepository offers;
	private final PayoutLedgerRepository payouts;
	private final PayoutPaymentRepository payments;
	private final CaseRepository cases;
	private final ExpertRepository experts;
	private final BrandRepository brands;
	private final TeamMemberRepository members;
	private final OfferLog log;

	PayoutRegisterService(ExpertCaseOfferRepository offers, PayoutLedgerRepository payouts,
			PayoutPaymentRepository payments, CaseRepository cases, ExpertRepository experts, BrandRepository brands,
			TeamMemberRepository members, OfferLog log) {
		this.offers = offers;
		this.payouts = payouts;
		this.payments = payments;
		this.cases = cases;
		this.experts = experts;
		this.brands = brands;
		this.members = members;
		this.log = log;
	}

	/** The offer's answer until a payout exists, then the payout's state in the business's words. */
	static RegisterStatus status(OfferOutcome outcome, PayoutStatus payout) {
		if (payout != null) {
			return switch (payout) {
				case PENDING -> RegisterStatus.PENDING;
				case PAID -> RegisterStatus.PROCESSING;
				case CONFIRMED -> RegisterStatus.PAID;
				case VOIDED -> throw new IllegalArgumentException("a voided payout is not registered");
			};
		}
		return RegisterStatus.valueOf(outcome.name());
	}

	@Transactional(readOnly = true)
	public List<RegisterRow> rows(Filter filter) {
		return allRows().stream().filter(row -> matches(row, filter)).toList();
	}

	/** The register exactly as filtered, as CSV — quoted and formula-safe through {@code csvField}. */
	@Transactional(readOnly = true)
	public String exportCsv(Filter filter) {
		StringBuilder csv = new StringBuilder(
				"case,expert,amount,currency,status,fee_set_by,offered_at,due_date,sent_at,confirmed_at,done\r\n");
		for (RegisterRow r : rows(filter)) {
			csv.append(String.join(",", PayoutService.csvField(r.caseCode()), PayoutService.csvField(r.expertName()),
					r.amount() == null ? "" : r.amount().toPlainString(), Objects.toString(r.currency(), ""),
					r.status().name(), PayoutService.csvField(r.feeSetByName()), day(r.offeredAt()), day(r.dueDate()),
					day(r.sentAt()), day(r.confirmedAt()), r.done() ? "yes" : "no")).append("\r\n");
		}
		return csv.toString();
	}

	/** Who is owed how much, per expert and currency; most pending first. */
	@Transactional(readOnly = true)
	public List<ExpertTotals> experts() {
		Map<List<Object>, List<RegisterRow>> byExpert = allRows().stream()
				.collect(Collectors.groupingBy(r -> List.of(r.expertId(), Objects.toString(r.currency(), ""))));
		return byExpert.values().stream().map(rows -> {
			RegisterRow first = rows.getFirst();
			Instant oldest = rows.stream().filter(r -> r.status() == RegisterStatus.PENDING)
					.map(RegisterRow::dueDate).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
			return new ExpertTotals(first.expertId(), first.expertName(), first.currency(),
					sum(rows, RegisterStatus.ACCEPTED), sum(rows, RegisterStatus.PENDING),
					sum(rows, RegisterStatus.PROCESSING), sum(rows, RegisterStatus.PAID), oldest);
		}).sorted(Comparator.comparing(ExpertTotals::pending).reversed()).toList();
	}

	/** The four tiles and the attention list, per currency, for offers made in the window. */
	@Transactional(readOnly = true)
	public List<Overview> overview(LocalDate from, LocalDate to) {
		Instant now = Instant.now();
		Instant nudge = now.minus(CONFIRM_NUDGE_DAYS, ChronoUnit.DAYS);
		Map<String, List<RegisterRow>> byCurrency = rows(new Filter(null, null, from, to, null)).stream()
				.collect(Collectors.groupingBy(r -> Objects.toString(r.currency(), "")));
		return byCurrency.entrySet().stream().map(e -> {
			List<RegisterRow> rows = e.getValue();
			List<RegisterRow> attention = rows.stream()
					.filter(r -> (r.status() == RegisterStatus.PENDING && r.dueDate() != null && r.dueDate().isBefore(now))
							|| (r.status() == RegisterStatus.PROCESSING && r.sentAt() != null && r.sentAt().isBefore(nudge)))
					.toList();
			return new Overview(e.getKey(), tile(rows, RegisterStatus.ACCEPTED), tile(rows, RegisterStatus.PENDING),
					tile(rows, RegisterStatus.PROCESSING), tile(rows, RegisterStatus.PAID), attention);
		}).toList();
	}

	/** One offer's log: the offer, and once delivered its payout and payment. */
	@Transactional(readOnly = true)
	public List<OfferLog.Entry> history(UUID offerId) {
		TenantContext ctx = TenantContext.current();
		ExpertCaseOffer offer = offers.findScoped(ctx, offerId)
				.orElseThrow(() -> new NotFoundException("No such offer: " + offerId));
		PayoutLedger payout = offer.getOutcome() != OfferOutcome.ACCEPTED ? null
				: payouts.findScoped(ctx).stream()
						.filter(p -> p.getCaseId().equals(offer.getCaseId()) && p.getExpertId().equals(offer.getExpertId())
								&& p.getStatus() != PayoutStatus.VOIDED)
						.findFirst().orElse(null);
		return log.forOffer(offer, payout);
	}

	// --- building the rows ------------------------------------------------------

	private record Pair(ExpertCaseOffer offer, PayoutLedger payout) {

		UUID caseId() {
			return offer != null ? offer.getCaseId() : payout.getCaseId();
		}

		UUID expertId() {
			return offer != null ? offer.getExpertId() : payout.getExpertId();
		}

		UUID brandId() {
			return offer != null ? offer.getBrandId() : payout.getBrandId();
		}
	}

	private List<RegisterRow> allRows() {
		TenantContext ctx = TenantContext.current();
		Map<List<UUID>, PayoutLedger> payoutByCaseAndExpert = new HashMap<>();
		payouts.findScoped(ctx).stream().filter(p -> p.getStatus() != PayoutStatus.VOIDED)
				.forEach(p -> payoutByCaseAndExpert.put(List.of(p.getCaseId(), p.getExpertId()), p));

		List<Pair> pairs = new ArrayList<>();
		for (ExpertCaseOffer o : offers.findScoped(ctx)) {
			PayoutLedger p = o.getOutcome() == OfferOutcome.ACCEPTED
					? payoutByCaseAndExpert.remove(List.of(o.getCaseId(), o.getExpertId())) : null;
			if (o.getFee() != null || p != null) {
				pairs.add(new Pair(o, p));
			}
		}
		payoutByCaseAndExpert.values().forEach(p -> pairs.add(new Pair(null, p)));

		// One query per lookup for the whole set, never one per row. Every id came off a scoped row.
		Map<UUID, String> caseCodes = byId(cases.findAllById(ids(pairs, Pair::caseId)), Case::getId, Case::getCaseCode);
		Map<UUID, String> expertNames = byId(experts.findAllById(ids(pairs, Pair::expertId)), Expert::getId,
				Expert::getFullName);
		Map<UUID, String> setBy = byId(members.findAllById(ids(pairs, x -> x.offer() == null ? null
				: x.offer().getFeeSetBy())), TeamMember::getId, TeamMember::getDisplayName);
		Map<UUID, PayoutPayment> paymentById = byId(payments.findAllById(ids(pairs, x -> x.payout() == null ? null
				: x.payout().getPaymentId())), PayoutPayment::getId, Function.identity());
		Map<UUID, String> currencies = byId(brands.findAllById(ids(pairs, Pair::brandId)), Brand::getId,
				Brand::getCurrency);

		return pairs.stream().map(x -> {
			ExpertCaseOffer o = x.offer();
			PayoutLedger p = x.payout();
			PayoutPayment pay = p == null || p.getPaymentId() == null ? null : paymentById.get(p.getPaymentId());
			RegisterStatus status = status(o == null ? null : o.getOutcome(), p == null ? null : p.getStatus());
			// What is paid is the payout's amount once one exists; before that, the offered fee.
			BigDecimal amount = p != null && p.getAmount() != null ? p.getAmount() : o == null ? null : o.getFee();
			return new RegisterRow(o == null ? null : o.getId(), p == null ? null : p.getId(), x.caseId(),
					caseCodes.get(x.caseId()), x.expertId(), expertNames.get(x.expertId()), amount,
					p != null ? p.getCurrency() : currencies.get(x.brandId()), status,
					o == null || o.getFeeSetBy() == null ? null : setBy.get(o.getFeeSetBy()),
					o == null ? null : o.getFeeSetAt(), o != null ? o.getOfferedAt() : p.getCreatedAt(),
					p == null ? null : p.getDueDate(), pay == null ? null : pay.getPaidDate(),
					pay == null ? null : pay.getConfirmedAt(), status == RegisterStatus.PAID);
		}).sorted(Comparator.comparing(RegisterRow::offeredAt, Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
	}

	private static boolean matches(RegisterRow r, Filter f) {
		LocalDate day = r.offeredAt() == null ? null : r.offeredAt().atZone(BusinessCalendar.ZONE).toLocalDate();
		String q = f.q() == null ? "" : f.q().trim().toLowerCase();
		return (f.status() == null || r.status() == f.status())
				&& (f.expertId() == null || f.expertId().equals(r.expertId()))
				&& (f.from() == null || (day != null && !day.isBefore(f.from())))
				&& (f.to() == null || (day != null && !day.isAfter(f.to())))
				&& (q.isEmpty() || contains(r.caseCode(), q) || contains(r.expertName(), q));
	}

	private static boolean contains(String value, String q) {
		return value != null && value.toLowerCase().contains(q);
	}

	private static <T> List<UUID> ids(List<T> rows, Function<T, UUID> id) {
		return rows.stream().map(id).filter(Objects::nonNull).distinct().toList();
	}

	private static <E, V> Map<UUID, V> byId(List<E> rows, Function<E, UUID> id, Function<E, V> value) {
		Map<UUID, V> map = new HashMap<>();
		rows.forEach(e -> {
			V v = value.apply(e);
			if (v != null) {
				map.putIfAbsent(id.apply(e), v);
			}
		});
		return map;
	}

	private static BigDecimal sum(List<RegisterRow> rows, RegisterStatus status) {
		return rows.stream().filter(r -> r.status() == status && r.amount() != null).map(RegisterRow::amount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static Tile tile(List<RegisterRow> rows, RegisterStatus status) {
		return new Tile((int) rows.stream().filter(r -> r.status() == status).count(), sum(rows, status));
	}

	private static String day(Instant at) {
		return at == null ? "" : at.atZone(BusinessCalendar.ZONE).toLocalDate().toString();
	}
}
