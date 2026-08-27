package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutPayment;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.PayoutPaymentRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an expert is owed, and what was actually sent.
 *
 * <p><b>EvalOS never moves money.</b> Nothing here talks to a bank, a card processor or
 * a disbursement rail, and no credential is stored. This is a ledger: a record that
 * money was owed and later paid, and every rule below follows from that.
 */
@Service
public class PayoutService {

	private final PayoutLedgerRepository payouts;

	private final PayoutPaymentRepository payments;

	private final ExpertRepository experts;

	private final BrandRepository brands;

	private final CaseRepository cases;

	private final TeamMemberRepository teamMembers;

	private final AuditService audit;

	PayoutService(PayoutLedgerRepository payouts, PayoutPaymentRepository payments, ExpertRepository experts,
			BrandRepository brands, CaseRepository cases, TeamMemberRepository teamMembers, AuditService audit) {
		this.payouts = payouts;
		this.payments = payments;
		this.experts = experts;
		this.brands = brands;
		this.cases = cases;
		this.teamMembers = teamMembers;
		this.audit = audit;
	}

	/**
	 * Open the row a delivered case owes its expert.
	 *
	 * <p>Called from inside {@code deliverToClient}'s transaction, deliberately:
	 * delivered and owed are one fact, and a delivery that committed without its payout
	 * row would be a case nobody gets paid for.
	 *
	 * @return the row, or empty when the case has no expert — which the caller reports
	 *         rather than swallows
	 */
	@Transactional
	public Optional<PayoutLedger> openForDelivery(Case delivered) {
		UUID expertId = delivered.getExpertId();
		if (expertId == null) {
			// A case can only reach FINAL_DELIVERY through EXPERT_SIGNING, so this should
			// be impossible — which is exactly why it is reported rather than swallowed.
			return Optional.empty();
		}

		Brand brand = brands.findById(delivered.getBrandId())
				.orElseThrow(() -> new IllegalStateException(
						"Case " + delivered.getId() + " names a brand that does not exist"));
		if (brand.getCurrency() == null) {
			// Guessing USD is the one guess in this unit that spends real money.
			throw new IllegalStateException(
					"Brand " + delivered.getBrandId() + " has no configured currency; no payout can be opened");
		}

		// Scoped to the case's brand, not the caller's: this runs inside deliverToClient,
		// which has no TenantContext axis that means "the brand this expert must belong
		// to" the way a GM's cross-brand context would not. An expert id from another
		// brand — should be impossible, since no expert is shared across brands — is
		// simply absent here, the same way an out-of-scope row is absent everywhere else.
		BigDecimal standardFee = experts.findByIdAndBrandId(expertId, delivered.getBrandId())
				.map(Expert::getStandardFee).orElse(null);
		Instant dueDate = delivered.getDeliveryDate().plus(brand.getPayoutTermDays(), ChronoUnit.DAYS);

		PayoutLedger row = payouts.save(new PayoutLedger(delivered.getBrandId(), delivered.getId(), expertId,
				standardFee, brand.getCurrency(), dueDate));

		audit.recordEvent("PAYOUT", row.getId(), AuditAction.CREATED, null,
				null, Map.of("caseId", delivered.getId(), "expertId", expertId, "status", "PENDING"));
		return Optional.of(row);
	}

	/**
	 * Who may record that money went out.
	 *
	 * <p><b>The ENM is here by decision, taken 2026-08-27.</b> Spec 16 restricted this to
	 * the GM and Brand Manager and said the widening was the business's call rather than
	 * a spec's. It was taken: the ENM sends the transfer, so the ENM records it.
	 *
	 * <p>Checked here as well as at the endpoint. {@code @PreAuthorize} guards one route;
	 * this guards the operation, so a later caller — a job, a webhook handler, another
	 * service — cannot reach it as anyone else. Same precedent as {@code RefundService}.
	 *
	 * <p><b>The single authority for who may record a payout.</b> Public so
	 * {@code PayoutControllerTest} — which lives in {@code com.ie.evalos.web} and cannot
	 * see a package-private member here — can read it directly and assert the
	 * {@code @PreAuthorize} role lists on {@code PayoutController} and
	 * {@code PaymentController} name exactly this set, rather than a second, hand-typed
	 * copy of it drifting out of step. {@code static final} over an immutable
	 * {@link Set#of}, so widening visibility exposes no mutation.
	 */
	public static final Set<Role> MAY_RECORD = Set.of(Role.GM, Role.BRAND_MANAGER, Role.EXPERT_NETWORK_MANAGER);

	/** One transfer, as the person who sent it describes it. */
	public record SettleForm(
			@NotNull UUID expertId,
			@NotEmpty List<UUID> payoutIds,
			@NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
			@NotBlank @Size(max = 100) String method,
			@NotBlank @Size(max = 200) String reference,
			@NotNull Instant paidDate,
			@Size(max = 2000) String notes) {
	}

	/**
	 * Record one transfer that settles several delivered drafts.
	 *
	 * @return the new payment's id
	 */
	@Transactional
	public UUID settle(SettleForm form) {
		TenantContext ctx = TenantContext.current();
		requireMayRecord(ctx);

		if (form.payoutIds().isEmpty()) {
			// @NotEmpty only fires under @Valid on an HTTP route (Task 6). This is called
			// directly by more than routes, so it is re-checked here — same reasoning as
			// requireMayRecord above.
			throw new InvalidRequestException("A payment must settle at least one draft");
		}

		List<UUID> ids = form.payoutIds().stream().distinct().toList();
		if (ids.size() != form.payoutIds().size()) {
			throw new InvalidRequestException("A draft was named twice in one payment");
		}

		// One scoped read per id rather than a new bulk finder: the scope is what makes an
		// id in a request body trustworthy, and `ids` is a week of one expert's drafts —
		// single digits. Adding a scoped-in finder would be new scoping code for no gain.
		List<PayoutLedger> rows = ids.stream()
				.map(id -> payouts.findScoped(ctx, id)
						.orElseThrow(() -> new InvalidRequestException("No such draft: " + id)))
				.toList();

		for (PayoutLedger row : rows) {
			if (!form.expertId().equals(row.getExpertId())) {
				throw new InvalidRequestException(
						"One transfer pays one expert; draft " + row.getId() + " is owed to somebody else");
			}
			if (row.getStatus() != PayoutStatus.PENDING) {
				throw new InvalidRequestException("Draft " + row.getId() + " is already " + row.getStatus());
			}
			if (row.getAmount() == null) {
				throw new InvalidRequestException(
						"Draft " + row.getId() + " has no amount yet; decide it before settling");
			}
		}

		// A GM is cross-brand, so "in the caller's scope" does not by itself mean "in one
		// brand". Same expert already implies same brand — an expert belongs to exactly one
		// — so this is belt and braces on a money path, which is where belt and braces belongs.
		UUID brandId = rows.get(0).getBrandId();
		String currency = rows.get(0).getCurrency();
		boolean mixed = rows.stream()
				.anyMatch(r -> !brandId.equals(r.getBrandId()) || !currency.equals(r.getCurrency()));
		if (mixed) {
			throw new InvalidRequestException("Every draft in one payment must share a brand and a currency");
		}

		BigDecimal owed = rows.stream().map(PayoutLedger::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
		// compareTo, not equals: BigDecimal.equals is scale-sensitive, so 700.0 and 700.00
		// would be "different" and refuse a settlement nobody could fix from the screen.
		if (owed.compareTo(form.amount()) != 0) {
			throw new InvalidRequestException("The payment is " + form.amount()
					+ " but the drafts it settles come to " + owed + ". Correct the draft amounts first.");
		}

		PayoutPayment payment = payments.save(new PayoutPayment(brandId, form.expertId(), form.amount(),
				currency, form.method().trim(), form.reference().trim(), form.paidDate(),
				blankToNull(form.notes()), ctx.memberId()));

		int attached = payouts.attachToPayment(payment.getId(), ids, brandId, ctx.memberId());
		if (attached != rows.size()) {
			// Rolls back the payment insert too, which is the point: a payment that settled
			// fewer drafts than it claims is exactly the silent disagreement rule 7 exists
			// to prevent.
			throw new IllegalTransitionException("Another settlement took " + (rows.size() - attached)
					+ " of these drafts; nothing was recorded");
		}

		audit.recordEvent("PAYOUT_PAYMENT", payment.getId(), AuditAction.PAYOUT_SETTLED, ctx.memberId(),
				null, Map.of("expertId", form.expertId(), "amount", form.amount(), "currency", currency,
						"method", payment.getMethod(), "reference", payment.getReference(),
						"draftCount", rows.size(), "payoutIds", ids));
		return payment.getId();
	}

	/** One draft an expert is owed for, as a screen sees it. */
	public record LedgerRow(UUID id, UUID caseId, String caseCode, UUID expertId, String expertName,
			BigDecimal amount, String currency, PayoutStatus status, Instant dueDate, boolean overdue,
			UUID paymentId) {
	}

	/** One expert's drafts in one week, with what they add up to. */
	public record ExpertGroup(UUID expertId, String expertName, List<LedgerRow> drafts, BigDecimal subtotal,
			String currency) {
	}

	/**
	 * A week on the batch screen. {@code currency} is null only when the week has no rows
	 * — every non-empty window is guaranteed single-currency, {@link #batch} refuses a
	 * mixed one rather than add across currencies.
	 */
	public record BatchView(LocalDate weekStart, LocalDate weekEnd, List<ExpertGroup> groups, BigDecimal due,
			BigDecimal paid, BigDecimal overdue, String currency) {
	}

	/** One transfer in a history list. */
	public record PaymentRow(UUID id, UUID expertId, String expertName, BigDecimal amount, String currency,
			String method, String reference, Instant paidDate, int draftCount, boolean confirmed) {
	}

	/** One transfer and everything it settled. */
	public record PaymentDetailView(PaymentRow payment, String notes, String recordedByName,
			List<LedgerRow> drafts) {
	}

	/** What stays correctable on a payment until the expert confirms it. */
	public record PaymentEditForm(
			@NotBlank @Size(max = 100) String method,
			@NotBlank @Size(max = 200) String reference,
			@Size(max = 2000) String notes) {
	}

	/**
	 * The Monday of the week an instant falls in, in the business's own zone.
	 *
	 * <p>Both halves matter. {@link BusinessCalendar#ZONE} because payout day is the
	 * business's day, and a UTC boundary puts a Sunday-afternoon delivery in next week for
	 * a California ENM. Monday-start because that is the week the batch screen is worked
	 * down. The window this anchors is <b>half-open</b> — {@code [monday, next monday)} —
	 * so an instant exactly on a boundary belongs to one week and cannot be paid twice.
	 */
	public static LocalDate weekStart(Instant instant) {
		return instant.atZone(BusinessCalendar.ZONE).toLocalDate()
				.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	/**
	 * The batch screen: one week's {@code PENDING} drafts, grouped by expert.
	 *
	 * <p>A row's week is the week of its {@code due_date}, not its delivery date — the
	 * batch screen answers "what is owed by when". A row belongs to this week exactly
	 * when {@link #weekStart} of its {@code due_date} equals {@link #weekStart} of
	 * {@code weekOf} (or of now, when {@code weekOf} is null) — the same function the
	 * pinned boundary tests exercise, not a second, parallel window computation. That is
	 * deliberate: {@code PmMetricsService} once filtered a window with an inclusive bound
	 * that put a boundary instant in two adjacent windows, which here would mean a draft
	 * payable twice.
	 *
	 * <p>{@code due} and {@code overdue} total the {@code PENDING} rows in the window (the
	 * groups below are exactly those rows). {@code paid} totals the window's rows that
	 * actually went out ({@code PAID} or {@code CONFIRMED}) — a {@code VOIDED} row is
	 * neither owed nor sent and counts toward nothing. A window spanning more than one
	 * currency (only reachable by a cross-brand GM) is refused rather than summed wrong,
	 * the same way {@link #settle} refuses a mixed-currency payment.
	 */
	@Transactional(readOnly = true)
	public BatchView batch(LocalDate weekOf) {
		TenantContext ctx = TenantContext.current();
		LocalDate anchor = weekOf != null ? weekOf : LocalDate.now(BusinessCalendar.ZONE);
		LocalDate start = weekStart(anchor.atStartOfDay(BusinessCalendar.ZONE).toInstant());
		LocalDate end = start.plusDays(6);

		List<PayoutLedger> inWeek = payouts.findScoped(ctx).stream()
				.filter(row -> row.getDueDate() != null && weekStart(row.getDueDate()).equals(start))
				.toList();

		String currency = inWeek.isEmpty() ? null : inWeek.get(0).getCurrency();
		boolean mixed = inWeek.stream().anyMatch(row -> !Objects.equals(currency, row.getCurrency()));
		if (mixed) {
			throw new InvalidRequestException("This week spans more than one currency; view one brand at a time");
		}

		// One query each for every name on the page, never one per row — CaseBoardService's shape.
		Map<UUID, String> expertNames = expertNames(inWeek.stream().map(PayoutLedger::getExpertId).toList());
		Map<UUID, String> caseCodes = caseCodes(inWeek.stream().map(PayoutLedger::getCaseId).toList());

		List<PayoutLedger> pending =
				inWeek.stream().filter(row -> row.getStatus() == PayoutStatus.PENDING).toList();

		// One clock for the whole call: toLedgerRow's per-row `overdue` and this method's
		// `overdue` total must agree on what "now" is, or a row can read not-overdue while
		// its amount is already counted in the overdue sum.
		Instant now = Instant.now();

		List<ExpertGroup> groups = pending.stream()
				.collect(Collectors.groupingBy(PayoutLedger::getExpertId))
				.entrySet().stream()
				.map(entry -> {
					List<LedgerRow> drafts = entry.getValue().stream()
							.map(row -> toLedgerRow(row, caseCodes, expertNames, now)).toList();
					BigDecimal subtotal = sum(drafts.stream().map(LedgerRow::amount));
					return new ExpertGroup(entry.getKey(), expertNames.get(entry.getKey()), drafts, subtotal,
							entry.getValue().get(0).getCurrency());
				})
				.sorted(Comparator.comparing(ExpertGroup::expertName, Comparator.nullsLast(String::compareToIgnoreCase)))
				.toList();

		BigDecimal due = sum(pending.stream().map(PayoutLedger::getAmount));
		BigDecimal overdue = sum(pending.stream()
				.filter(row -> row.getDueDate() != null && row.getDueDate().isBefore(now))
				.map(PayoutLedger::getAmount));
		BigDecimal paid = sum(inWeek.stream()
				.filter(row -> row.getStatus() == PayoutStatus.PAID || row.getStatus() == PayoutStatus.CONFIRMED)
				.map(PayoutLedger::getAmount));

		return new BatchView(start, end, groups, due, paid, overdue, currency);
	}

	/** One expert's payment history, newest first, with how many drafts each transfer settled. */
	@Transactional(readOnly = true)
	public List<PaymentRow> history(UUID expertId) {
		TenantContext ctx = TenantContext.current();
		List<PayoutPayment> mine = payments.findScoped(ctx).stream()
				.filter(payment -> expertId.equals(payment.getExpertId()))
				.sorted(Comparator.comparing(PayoutPayment::getPaidDate).reversed())
				.toList();

		// One query for the whole page's draft counts, not one findByPaymentId per payment.
		Map<UUID, Long> draftCounts = payouts.findScoped(ctx).stream()
				.filter(row -> expertId.equals(row.getExpertId()) && row.getPaymentId() != null)
				.collect(Collectors.groupingBy(PayoutLedger::getPaymentId, Collectors.counting()));

		Map<UUID, String> expertNames = expertNames(List.of(expertId));

		return mine.stream()
				.map(payment -> new PaymentRow(payment.getId(), payment.getExpertId(),
						expertNames.get(payment.getExpertId()), payment.getAmount(), payment.getCurrency(),
						payment.getMethod(), payment.getReference(), payment.getPaidDate(),
						draftCounts.getOrDefault(payment.getId(), 0L).intValue(), payment.getConfirmedAt() != null))
				.toList();
	}

	/** One transfer and every draft it settled. */
	@Transactional(readOnly = true)
	public PaymentDetailView payment(UUID paymentId) {
		TenantContext ctx = TenantContext.current();
		PayoutPayment found = payments.findScoped(ctx, paymentId)
				.orElseThrow(() -> new InvalidRequestException("No such payment: " + paymentId));

		// findByPaymentId is a single call for the one payment this screen shows, not a
		// per-row read — safe with a paymentId that just came back from a scoped read.
		List<PayoutLedger> settled = payouts.findByPaymentId(paymentId);

		Map<UUID, String> expertNames = expertNames(List.of(found.getExpertId()));
		Map<UUID, String> caseCodes = caseCodes(settled.stream().map(PayoutLedger::getCaseId).toList());
		Map<UUID, String> recordedByNames = teamMemberNames(List.of(found.getRecordedBy()));

		Instant now = Instant.now();
		List<LedgerRow> drafts =
				settled.stream().map(row -> toLedgerRow(row, caseCodes, expertNames, now)).toList();
		PaymentRow row = new PaymentRow(found.getId(), found.getExpertId(), expertNames.get(found.getExpertId()),
				found.getAmount(), found.getCurrency(), found.getMethod(), found.getReference(),
				found.getPaidDate(), drafts.size(), found.getConfirmedAt() != null);

		return new PaymentDetailView(row, found.getNotes(), recordedByNames.get(found.getRecordedBy()), drafts);
	}

	/**
	 * The flat, filterable ledger — spec 16b's {@code GET /api/payouts}: every row in the
	 * caller's scope, narrowed by whichever filters are non-null/true. Unlike {@link #batch}
	 * this is not grouped and not week-bound by default, so it is what answers "this
	 * expert's pending drafts across weeks" — the read Task 11's expert-detail screen
	 * needs and that no other method on this service provides.
	 *
	 * @param status      only rows in this status, or every status when null
	 * @param expertId    only this expert's rows, or every expert when null
	 * @param weekOf      only rows whose {@code due_date} falls in the week containing this
	 *                    date (same half-open window {@link #weekStart} defines), or every
	 *                    week when null
	 * @param overdueOnly when true, keep only {@code PENDING} rows whose due date has
	 *                    passed — the same test {@link #batch} totals under {@code overdue}
	 */
	@Transactional(readOnly = true)
	public List<LedgerRow> list(PayoutStatus status, UUID expertId, LocalDate weekOf, boolean overdueOnly) {
		TenantContext ctx = TenantContext.current();
		Instant now = Instant.now();
		LocalDate targetWeek = weekOf != null ? weekStart(weekOf.atStartOfDay(BusinessCalendar.ZONE).toInstant()) : null;

		List<PayoutLedger> rows = payouts.findScoped(ctx).stream()
				.filter(row -> status == null || row.getStatus() == status)
				.filter(row -> expertId == null || expertId.equals(row.getExpertId()))
				.filter(row -> targetWeek == null
						|| (row.getDueDate() != null && weekStart(row.getDueDate()).equals(targetWeek)))
				.filter(row -> !overdueOnly || (row.getStatus() == PayoutStatus.PENDING && row.getDueDate() != null
						&& row.getDueDate().isBefore(now)))
				.toList();

		// One query each for the filtered set's names, not one per row.
		Map<UUID, String> expertNames = expertNames(rows.stream().map(PayoutLedger::getExpertId).toList());
		Map<UUID, String> caseCodes = caseCodes(rows.stream().map(PayoutLedger::getCaseId).toList());

		return rows.stream().map(row -> toLedgerRow(row, caseCodes, expertNames, now)).toList();
	}

	/**
	 * One payout row, on its own — the same projection {@link #batch} builds for a whole
	 * week, for the single-draft read (Task 6's {@code GET /api/payouts/{id}}) and for
	 * handing the refreshed row back after {@link #correctAmount}.
	 */
	@Transactional(readOnly = true)
	public LedgerRow payout(UUID payoutId) {
		TenantContext ctx = TenantContext.current();
		PayoutLedger row = payouts.findScoped(ctx, payoutId)
				.orElseThrow(() -> new InvalidRequestException("No such draft: " + payoutId));
		Map<UUID, String> expertNames = expertNames(List.of(row.getExpertId()));
		Map<UUID, String> caseCodes = caseCodes(List.of(row.getCaseId()));
		return toLedgerRow(row, caseCodes, expertNames, Instant.now());
	}

	/**
	 * Correct what a draft is worth, before anything settles it.
	 *
	 * <p>Frozen once settled: the amount is part of a payment's sum, and changing it would
	 * break that sum after the fact. The fix for a wrong settled amount is a
	 * void-and-re-record, not an edit.
	 */
	@Transactional
	public void correctAmount(UUID payoutId, BigDecimal amount) {
		TenantContext ctx = TenantContext.current();
		requireMayRecord(ctx);
		if (amount == null || amount.signum() < 0) {
			throw new InvalidRequestException("A payout amount cannot be negative");
		}

		PayoutLedger row = payouts.findScoped(ctx, payoutId)
				.orElseThrow(() -> new InvalidRequestException("No such draft: " + payoutId));
		if (row.getStatus() != PayoutStatus.PENDING) {
			throw new IllegalTransitionException("Draft " + payoutId + " is " + row.getStatus()
					+ " and its amount is part of a payment");
		}

		BigDecimal before = row.getAmount();
		row.setAmount(amount);
		row.setRecordedBy(ctx.memberId());
		payouts.save(row);
		// HashMap, not Map.of: `before` is a BigDecimal that can legitimately be null (an
		// expert with no standard fee opens with no amount), and Map.of rejects a null
		// value outright. Both sides carry the same BigDecimal type — a String on one side
		// and a BigDecimal on the other would make "350.00" and 350.00 look like a
		// disagreement to anything diffing the append-only record.
		Map<String, Object> beforeSnapshot = new HashMap<>();
		beforeSnapshot.put("amount", before);
		audit.recordEvent("PAYOUT", payoutId, AuditAction.UPDATED, ctx.memberId(),
				beforeSnapshot, Map.of("amount", amount));
	}

	/** Correct how a transfer was described. Frozen once the expert has confirmed it. */
	@Transactional
	public void editPayment(UUID paymentId, PaymentEditForm form) {
		TenantContext ctx = TenantContext.current();
		requireMayRecord(ctx);

		PayoutPayment payment = loadUnconfirmed(ctx, paymentId, "edited");
		Map<String, Object> before = Map.of("method", payment.getMethod(), "reference", payment.getReference());
		payment.setMethod(form.method().trim());
		payment.setReference(form.reference().trim());
		payment.setNotes(blankToNull(form.notes()));
		payments.save(payment);
		audit.recordEvent("PAYOUT_PAYMENT", paymentId, AuditAction.UPDATED, ctx.memberId(), before,
				Map.of("method", payment.getMethod(), "reference", payment.getReference()));
	}

	/**
	 * The expert acknowledged the transfer.
	 *
	 * <p>Set on the payment and cascaded, because one transfer gets one acknowledgement.
	 * There is no route that confirms a single draft.
	 */
	@Transactional
	public void confirm(UUID paymentId) {
		TenantContext ctx = TenantContext.current();
		requireMayRecord(ctx);

		PayoutPayment payment = loadUnconfirmed(ctx, paymentId, "confirmed twice");
		payment.setConfirmedAt(Instant.now());
		payments.save(payment);
		int confirmed = payouts.confirmForPayment(paymentId);
		audit.recordEvent("PAYOUT_PAYMENT", paymentId, AuditAction.UPDATED, ctx.memberId(),
				Map.of("confirmed", false), Map.of("confirmed", true, "draftCount", confirmed));
	}

	/**
	 * What each expert on a brand is owed. Derived — {@code total_payments_pending} stays dead.
	 *
	 * <p>Trusts {@code brandId} outright: no {@code TenantContext} consultation, the same
	 * convention {@link PayoutLedgerRepository#findByPaymentId} carries. Call only with a
	 * brand id that came from a scoped read — both current callers ({@code batch}'s own
	 * scoping aside, {@code ExpertService}) derive it from an already-scoped {@code Expert}
	 * or the caller's own {@code TenantContext}, never from a request parameter.
	 */
	public Map<UUID, BigDecimal> pendingByExpert(UUID brandId) {
		return payouts.pendingTotalsByExpert(brandId).stream()
				.collect(Collectors.toMap(PayoutLedgerRepository.ExpertPendingTotal::getExpertId,
						PayoutLedgerRepository.ExpertPendingTotal::getTotal));
	}

	private PayoutPayment loadUnconfirmed(TenantContext ctx, UUID paymentId, String what) {
		PayoutPayment payment = payments.findScoped(ctx, paymentId)
				.orElseThrow(() -> new InvalidRequestException("No such payment: " + paymentId));
		if (payment.getConfirmedAt() != null) {
			throw new IllegalTransitionException("A confirmed payment cannot be " + what);
		}
		return payment;
	}

	private LedgerRow toLedgerRow(PayoutLedger row, Map<UUID, String> caseCodes, Map<UUID, String> expertNames,
			Instant now) {
		boolean overdue = row.getStatus() == PayoutStatus.PENDING && row.getDueDate() != null
				&& row.getDueDate().isBefore(now);
		return new LedgerRow(row.getId(), row.getCaseId(), caseCodes.get(row.getCaseId()), row.getExpertId(),
				expertNames.get(row.getExpertId()), row.getAmount(), row.getCurrency(), row.getStatus(),
				row.getDueDate(), overdue, row.getPaymentId());
	}

	private static BigDecimal sum(Stream<BigDecimal> amounts) {
		return amounts.filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/** One query for every expert name a page needs, not one per row (mem:backend/persistence). */
	private Map<UUID, String> expertNames(List<UUID> expertIds) {
		List<UUID> ids = expertIds.stream().filter(Objects::nonNull).distinct().toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		// The null-name filter is load-bearing, not defensive (CaseBoardService's own
		// pattern): full_name carries no NOT NULL at the schema level, and Collectors.toMap
		// throws on a null value rather than answering null.
		return experts.findAllById(ids).stream()
				.filter(expert -> expert.getFullName() != null)
				.collect(Collectors.toMap(Expert::getId, Expert::getFullName, (first, second) -> first));
	}

	/** One query for every case code a page needs, not one per row (mem:backend/persistence). */
	private Map<UUID, String> caseCodes(List<UUID> caseIds) {
		List<UUID> ids = caseIds.stream().filter(Objects::nonNull).distinct().toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		// case_code carries no NOT NULL either; same null-filter reasoning as expertNames.
		return cases.findAllById(ids).stream()
				.filter(subject -> subject.getCaseCode() != null)
				.collect(Collectors.toMap(Case::getId, Case::getCaseCode, (first, second) -> first));
	}

	/** One query for every recorder name a page needs, not one per row (mem:backend/persistence). */
	private Map<UUID, String> teamMemberNames(List<UUID> memberIds) {
		List<UUID> ids = memberIds.stream().filter(Objects::nonNull).distinct().toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		return teamMembers.findAllById(ids).stream()
				.collect(Collectors.toMap(TeamMember::getId, TeamMember::getDisplayName, (first, second) -> first));
	}

	private static void requireMayRecord(TenantContext ctx) {
		if (!MAY_RECORD.contains(ctx.role())) {
			throw new ForbiddenException("Only the GM, a Brand Manager or the ENM may record a payout");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
