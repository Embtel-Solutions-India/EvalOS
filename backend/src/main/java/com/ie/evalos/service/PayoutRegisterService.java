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

	/**
	 * Every field optional; {@code from} / {@code to} bound the offer date, inclusive. {@code brandId}
	 * is the shell's brand switcher: it narrows within the caller's scope and can never widen it.
	 */
	public record Filter(RegisterStatus status, UUID expertId, LocalDate from, LocalDate to, String q,
			UUID brandId) {
		public static Filter none() {
			return new Filter(null, null, null, null, null, null);
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
		return allRows(filter.brandId()).stream().filter(row -> matches(row, filter)).toList();
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
	public List<ExpertTotals> experts(UUID brandId) {
		Map<List<Object>, List<RegisterRow>> byExpert = allRows(brandId).stream()
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

	/** Per currency: the four tiles for offers made in the window, and the attention list regardless of it. */
	@Transactional(readOnly = true)
	public List<Overview> overview(LocalDate from, LocalDate to, UUID brandId) {
		Instant now = Instant.now();
		Instant nudge = now.minus(CONFIRM_NUDGE_DAYS, ChronoUnit.DAYS);
		// The tiles answer for the period; the attention list does not — an old overdue payout is still
		// overdue today, and "nothing needs attention" must be true, not true-for-this-month.
		List<RegisterRow> everything = allRows(brandId);
		Map<String, List<RegisterRow>> byCurrency = everything.stream()
				.filter(row -> matches(row, new Filter(null, null, from, to, null, null)))
				.collect(Collectors.groupingBy(r -> Objects.toString(r.currency(), "")));
		Map<String, List<RegisterRow>> attentionByCurrency = everything.stream()
				.filter(r -> (r.status() == RegisterStatus.PENDING && r.dueDate() != null && r.dueDate().isBefore(now))
						|| (r.status() == RegisterStatus.PROCESSING && r.sentAt() != null && r.sentAt().isBefore(nudge)))
				.collect(Collectors.groupingBy(r -> Objects.toString(r.currency(), "")));
		attentionByCurrency.keySet().forEach(currency -> byCurrency.putIfAbsent(currency, List.of()));
		return byCurrency.entrySet().stream().map(e -> {
			List<RegisterRow> rows = e.getValue();
			List<RegisterRow> attention = attentionByCurrency.getOrDefault(e.getKey(), List.of());
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

	/** {@code superseded}: an acceptance that is no longer the live one — see {@link #allRows}. */
	private record Pair(ExpertCaseOffer offer, PayoutLedger payout, boolean superseded) {

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

	private List<RegisterRow> allRows(UUID brandId) {
		TenantContext ctx = TenantContext.current();
		// A voided payout still pairs, so the acceptance it belonged to is dropped with it (a refund)
		// rather than resurfacing as committed money. A live payout wins over a voided one.
		Map<List<UUID>, PayoutLedger> payoutByCaseAndExpert = new HashMap<>();
		payouts.findScoped(ctx).forEach(p -> payoutByCaseAndExpert.merge(List.of(p.getCaseId(), p.getExpertId()), p,
				(a, b) -> a.getStatus() == PayoutStatus.VOIDED ? b : a));

		List<ExpertCaseOffer> offerRows = new ArrayList<>(offers.findScoped(ctx));
		// Newest first, so on a retaken case the latest acceptance is the one that takes the payout.
		offerRows.sort(Comparator.comparing(ExpertCaseOffer::getOfferedAt,
				Comparator.nullsLast(Comparator.reverseOrder())));
		Map<UUID, Case> caseById = byId(cases.findAllById(offerRows.stream().map(ExpertCaseOffer::getCaseId)
				.distinct().toList()), Case::getId, Function.identity());

		List<Pair> pairs = new ArrayList<>();
		java.util.Set<List<UUID>> accepted = new java.util.HashSet<>();
		for (ExpertCaseOffer o : offerRows) {
			List<UUID> key = List.of(o.getCaseId(), o.getExpertId());
			if (o.getOutcome() != OfferOutcome.ACCEPTED) {
				if (o.getFee() != null) {
					pairs.add(new Pair(o, null, false));
				}
				continue;
			}
			// **An acceptance is committed money only while it is the live one.** An older acceptance
			// by the same expert (a retake), or one whose expert the case no longer names (declined or
			// timed out after accepting, then reassigned), reads as superseded. A case not in the scoped
			// load is taken on trust rather than guessed about.
			Case subject = caseById.get(o.getCaseId());
			boolean replaced = subject != null && !o.getExpertId().equals(subject.getExpertId());
			if (!accepted.add(key) || replaced) {
				if (o.getFee() != null) {
					pairs.add(new Pair(o, null, true));
				}
				continue;
			}
			PayoutLedger p = payoutByCaseAndExpert.remove(key);
			if (p != null && p.getStatus() == PayoutStatus.VOIDED) {
				continue; // refunded: nothing is owed, nothing is committed
			}
			if (o.getFee() != null || p != null) {
				pairs.add(new Pair(o, p, false));
			}
		}
		payoutByCaseAndExpert.values().stream().filter(p -> p.getStatus() != PayoutStatus.VOIDED)
				.forEach(p -> pairs.add(new Pair(null, p, false)));
		if (brandId != null) {
			pairs.removeIf(x -> !brandId.equals(x.brandId())); // after the scope: narrows only
		}

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
			RegisterStatus status = x.superseded() ? RegisterStatus.SUPERSEDED
					: status(o == null ? null : o.getOutcome(), p == null ? null : p.getStatus());
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
