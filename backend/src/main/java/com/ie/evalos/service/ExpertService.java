package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertTier;
import com.ie.evalos.domain.FieldTag;
import com.ie.evalos.domain.LetterType;
import com.ie.evalos.domain.PerformanceFlag;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.ExpertLoadService.Load;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Expert Network Manager's roster: the screen that replaces their Google Sheet.
 *
 * <p>Every read starts from {@link ExpertRepository#findScoped}, so brand isolation is
 * decided where the rest of the system decides it (invariant 1) and another brand's
 * expert is <em>absent</em> rather than forbidden. The filters below are applied to
 * that already-scoped list rather than pushed into SQL: a brand's roster is tens of
 * rows, and a second query built its own way is how a screen starts disagreeing with
 * every other read about what the caller may see.
 *
 * <p><strong>{@code payment_detail} does not leave this class.</strong> There is one
 * write path ({@link #setPaymentDetail}) and no read path at all — not for the ENM who
 * typed it. What callers get is {@link Expert#hasPaymentDetail()}, which is what an ENM
 * actually needs to know (invariant 4).
 *
 * <p>Load figures come from {@link ExpertLoadService}, never from
 * {@code current_active_count}: that column has never been written and is a permanent
 * zero.
 */
@Service
public class ExpertService {

	/**
	 * Everything a person may set on an expert, from a form or from a sheet row.
	 *
	 * <p><strong>The validation rules live here, once.</strong> The controller binds
	 * this with {@code @Valid} and the sheet import runs the same constraints through a
	 * {@code Validator} to build its per-row report — so "a quality score is 1 to 10"
	 * cannot come to mean two different things on the two paths that write it.
	 *
	 * <p>What is deliberately absent: {@code brandId} (a row never changes brand, and a
	 * new row's brand is a separate, guarded argument), {@code paymentDetail} (its own
	 * write-only endpoint), the two dead case counters, {@code totalPaymentsPending}
	 * (Unit 16 maintains it), {@code avgResponseHours} and {@code performanceFlags}
	 * (Unit 12 computes what makes them meaningful), and {@code agreementStatus} /
	 * {@code paymentStatus} (Units 15 and 16 own those transitions — a roster edit that
	 * could flip "agreement signed" would be a way to claim a signature nobody gave).
	 */
	public record ExpertForm(
			@NotBlank @Size(max = 200) String fullName,
			@Email @Size(max = 200) String email,
			@Size(max = 50) String phone,
			@Size(max = 200) String title,
			@Size(max = 200) String institution,
			List<FieldTag> primaryFields,
			List<FieldTag> secondaryFields,
			List<LetterType> letterTypes,
			ExpertTier tier,
			Availability availability,
			// numeric(3,1) in the schema, and V7 documents the scale as 1–10.
			@DecimalMin("1.0") @DecimalMax("10.0") @Digits(integer = 2, fraction = 1) BigDecimal qualityScore,
			@DecimalMin("0.0") @Digits(integer = 10, fraction = 2) BigDecimal standardFee,
			@Size(max = 200) String recruitmentSource,
			LocalDate dateOnboarded,
			@Size(max = 4000) String notes) {
	}

	/**
	 * One roster row: the expert, the load the counters cannot tell us, and what they are
	 * owed — derived from the payout ledger, never {@code expert.total_payments_pending},
	 * which nothing has ever written (Unit 16).
	 */
	public record RosterEntry(Expert expert, Load load, BigDecimal pendingTotal) {
	}

	/** One page of the roster. {@code total} is the filtered count, not the brand's. */
	public record RosterPage(List<RosterEntry> entries, int page, int size, int total) {
	}

	/** The availability board's rows, in enum order so the board's columns are stable. */
	public record AvailabilityGroup(Availability availability, List<RosterEntry> experts) {
	}

	/**
	 * What the trail records. No {@code paymentDetail} member, and none may be added:
	 * the audit trail is readable by every role that can read the object it hangs off,
	 * and the one encrypted field in EvalOS is not part of any of them.
	 */
	public record ExpertSnapshot(
			UUID id,
			String fullName,
			String email,
			String phone,
			String title,
			String institution,
			List<FieldTag> primaryFields,
			List<FieldTag> secondaryFields,
			List<LetterType> letterTypes,
			ExpertTier tier,
			Availability availability,
			BigDecimal qualityScore,
			BigDecimal standardFee,
			String recruitmentSource,
			LocalDate dateOnboarded,
			boolean paymentDetailOnFile,
			String note) {

		public static ExpertSnapshot of(Expert expert) {
			return of(expert, null);
		}

		public static ExpertSnapshot of(Expert expert, String note) {
			return new ExpertSnapshot(expert.getId(), expert.getFullName(), expert.getEmail(), expert.getPhone(),
					expert.getTitle(), expert.getInstitution(), expert.getPrimaryFields(),
					expert.getSecondaryFields(), expert.getLetterTypes(), expert.getTier(),
					expert.getAvailability(), expert.getQualityScore(), expert.getStandardFee(),
					expert.getRecruitmentSource(), expert.getDateOnboarded(), expert.hasPaymentDetail(), note);
		}
	}

	static final String OBJECT_TYPE = "EXPERT";

	/** Name order, and an unnamed row sorts last rather than first — as in the picker. */
	private static final Comparator<Expert> BY_NAME =
			Comparator.comparing(Expert::getFullName, Comparator.nullsLast(String::compareToIgnoreCase));

	private final ExpertRepository experts;
	private final BrandRepository brands;
	private final ExpertLoadService loads;
	private final PayoutService payouts;
	private final OwnershipGuard ownership;
	private final AuditService audit;

	ExpertService(ExpertRepository experts, BrandRepository brands, ExpertLoadService loads, PayoutService payouts,
			OwnershipGuard ownership, AuditService audit) {
		this.experts = experts;
		this.brands = brands;
		this.loads = loads;
		this.payouts = payouts;
		this.ownership = ownership;
		this.audit = audit;
	}

	// --- reads ---------------------------------------------------------------

	/**
	 * The roster screen.
	 *
	 * @param brandId the GM's brand switcher. Narrowing only, and applied after the
	 *                scoped read exactly as on the production board: naming a brand the
	 *                caller cannot read yields an empty roster rather than that brand's
	 *                experts.
	 * @param search  matched against name and institution, case-insensitively. Those two
	 *                because they are what an ENM remembers about an expert; email is
	 *                not searched, so a roster read cannot be used to confirm whether an
	 *                address is on file in a brand.
	 */
	@Transactional(readOnly = true)
	public RosterPage roster(UUID brandId, String search, FieldTag fieldTag, LetterType letterType,
			Availability availability, ExpertTier tier, int page, int size) {
		List<Expert> matching = readable(brandId).stream()
				.filter(expert -> matchesSearch(expert, search))
				.filter(expert -> fieldTag == null
						|| expert.getPrimaryFields().contains(fieldTag)
						|| expert.getSecondaryFields().contains(fieldTag))
				.filter(expert -> letterType == null || expert.getLetterTypes().contains(letterType))
				.filter(expert -> availability == null || expert.availabilityOrInactive() == availability)
				.filter(expert -> tier == null || expert.getTier() == tier)
				.sorted(BY_NAME)
				.toList();

		List<Expert> pageOf = matching.stream().skip((long) page * size).limit(size).toList();
		return new RosterPage(withLoad(pageOf, pendingTotals(pageOf)), page, size, matching.size());
	}

	@Transactional(readOnly = true)
	public RosterEntry profile(UUID id) {
		Expert expert = read(id);
		BigDecimal pendingTotal = payouts.pendingByExpert(expert.getBrandId())
				.getOrDefault(expert.getId(), BigDecimal.ZERO);
		return new RosterEntry(expert, loads.forExpert(expert.getId()), pendingTotal);
	}

	/**
	 * Who is free, who is at capacity, who is on leave — with the load, because "at
	 * capacity" is a claim the case count either supports or contradicts, and the ENM
	 * needs to see which before a PM finds nobody to assign.
	 */
	@Transactional(readOnly = true)
	public List<AvailabilityGroup> availabilityBoard(UUID brandId) {
		List<Expert> all = readable(brandId);
		Map<Availability, List<Expert>> byAvailability = new EnumMap<>(Availability.class);
		for (Expert expert : all) {
			byAvailability.computeIfAbsent(expert.availabilityOrInactive(), key -> new ArrayList<>()).add(expert);
		}

		// Fetched once for the whole board, not once per column: pendingTotals runs the
		// brand-wide GROUP BY, and Availability has four values — four identical queries for
		// one board otherwise.
		Map<UUID, BigDecimal> pending = pendingTotals(all);
		return Arrays.stream(Availability.values())
				.map(availability -> new AvailabilityGroup(availability,
						withLoad(byAvailability.getOrDefault(availability, List.of()).stream()
								.sorted(BY_NAME).toList(), pending)))
				.toList();
	}

	// --- writes --------------------------------------------------------------

	/**
	 * Creates an expert in one brand.
	 *
	 * <p>{@code brandId} is one of the three endpoints in EvalOS where a request may name a
	 * brand — this one and the two sheet imports, which share the reasoning and
	 * {@link ExpertImportService#brandFor} — and in none of them is it a scope: a new row's
	 * brand has to be chosen by somebody, and a GM has no brand of their own to fall back on.
	 * {@code architecture.md} carries the policy and the full list. It is still not trusted —
	 * {@link OwnershipGuard} decides whether this caller may act in the brand named, so
	 * a Brand Manager naming another brand gets a 403 and only the cross-brand role can
	 * name anything. A brand-locked caller who names nothing gets their own.
	 */
	@Transactional
	public Expert create(UUID brandId, ExpertForm form) {
		Expert expert = new Expert(brandOf(brandId), form.fullName().trim());
		apply(expert, form);
		Expert saved = experts.save(expert);

		audit.recordEvent(OBJECT_TYPE, saved.getId(), AuditAction.CREATED, actor(), null,
				ExpertSnapshot.of(saved, "Expert added to the roster"));
		return saved;
	}

	@Transactional
	public Expert update(UUID id, ExpertForm form) {
		Expert expert = readForWrite(id);
		ExpertSnapshot before = ExpertSnapshot.of(expert);

		apply(expert, form);
		Expert saved = experts.save(expert);

		audit.recordEvent(OBJECT_TYPE, saved.getId(), AuditAction.UPDATED, actor(), before,
				ExpertSnapshot.of(saved, "Profile edited"));
		return saved;
	}

	/**
	 * Sets availability, which is the lever that decides whether Unit 08's picker can
	 * offer this expert at all: it lists {@code AVAILABLE} only, because
	 * {@code CaseLifecycleService.availableExpert} refuses anything else.
	 */
	@Transactional
	public Expert setAvailability(UUID id, Availability availability) {
		Expert expert = readForWrite(id);
		ExpertSnapshot before = ExpertSnapshot.of(expert);
		Availability was = expert.getAvailability();

		expert.setAvailability(availability);
		Expert saved = experts.save(expert);

		audit.recordEvent(OBJECT_TYPE, saved.getId(), AuditAction.UPDATED, actor(), before,
				ExpertSnapshot.of(saved, "Availability: %s → %s".formatted(was, availability)));
		return saved;
	}

	/**
	 * Records a performance concern against an expert, with the reason it was raised.
	 *
	 * <p>The first writer {@code performance_flags} has ever had. The column, its enum and its
	 * display all shipped in Unit 11 and nothing could set it, which is why the ENM — whose whole
	 * job is roster quality — had no way to record a judgement they are the person paid to make.
	 *
	 * <p><strong>Replaces the whole list rather than appending</strong>, because these are current
	 * concerns and not a history: the history is the audit trail, which keeps every previous set
	 * with its author and reason. An append-only column would grow a flag an expert resolved two
	 * years ago into a permanent mark.
	 *
	 * <p>Declines are deliberately <em>not</em> written here. {@code expert_case_offer} already
	 * records them as events, and a hand-set {@code DECLINED_CASES} flag would be a second,
	 * disagreeing answer to a question the ledger answers exactly.
	 */
	@Transactional
	public Expert setPerformanceFlags(UUID id, List<PerformanceFlag> flags, String reason) {
		Expert expert = readForWrite(id);
		ExpertSnapshot before = ExpertSnapshot.of(expert);
		List<PerformanceFlag> was = expert.getPerformanceFlags();

		expert.setPerformanceFlags(flags);
		Expert saved = experts.save(expert);

		audit.recordEvent(OBJECT_TYPE, saved.getId(), AuditAction.PERFORMANCE_FLAGGED, actor(), before,
				ExpertSnapshot.of(saved, "Performance flags: %s → %s — %s".formatted(was, flags, reason)));
		return saved;
	}

	/**
	 * Writes the one encrypted field in EvalOS. Write-only: there is no endpoint, DTO or
	 * service method that reads it back, so an ENM correcting an account number types the
	 * whole value again rather than editing what they were shown.
	 *
	 * <p>The audit snapshot records <em>that</em> it was set, never what to. The value
	 * itself is only ever handled by {@code PaymentDetailConverter} on its way to
	 * ciphertext.
	 */
	@Transactional
	public void setPaymentDetail(UUID id, String paymentDetail) {
		Expert expert = readForWrite(id);
		ExpertSnapshot before = ExpertSnapshot.of(expert);

		expert.setPaymentDetail(paymentDetail);
		experts.save(expert);

		audit.recordEvent(OBJECT_TYPE, expert.getId(), AuditAction.UPDATED, actor(), before,
				ExpertSnapshot.of(expert, "Payment detail set"));
	}

	// --- shared plumbing -----------------------------------------------------

	/**
	 * The brand a new row belongs in, proved to be one this caller may write to and one
	 * that exists.
	 *
	 * <p>The existence check is not decoration: an unknown UUID would otherwise reach the
	 * {@code brand_id} foreign key and come back as a 500 on what is a bad request.
	 */
	private UUID brandOf(UUID requested) {
		UUID brandId = requested != null ? requested : TenantContext.current().brandId();
		if (brandId == null) {
			throw new InvalidRequestException("Name the brand this expert belongs to");
		}
		ownership.assertCanAct(brandId);
		if (brands.findById(brandId).isEmpty()) {
			throw new InvalidRequestException("No such brand");
		}
		return brandId;
	}

	/** Applies the form to an expert. Called by the sheet import too. */
	void apply(Expert expert, ExpertForm form) {
		expert.setFullName(trimmed(form.fullName()));
		expert.setEmail(trimmed(form.email()));
		expert.setPhone(trimmed(form.phone()));
		expert.setTitle(trimmed(form.title()));
		expert.setInstitution(trimmed(form.institution()));
		expert.setPrimaryFields(form.primaryFields());
		expert.setSecondaryFields(form.secondaryFields());
		expert.setLetterTypes(form.letterTypes());
		expert.setTier(form.tier());
		// An expert with nothing said about availability is AVAILABLE, and this coerces rather
		// than passing the null through. Not a nicety: only an AVAILABLE expert is offered by
		// the assignment picker, so a null is an expert nobody can be given work — and the
		// likeliest source of one is a legacy sheet with no availability column at all, which
		// would import fifty unstaffable rows and report success. The comment here used to
		// claim this default while the code passed the null straight down.
		//
		// It applies to an edit too, so availability cannot be cleared back to "not set". That
		// is deliberate: "not set" is not a state an ENM means, it is the state a roster row
		// goes missing in.
		expert.setAvailability(form.availability() == null ? Availability.AVAILABLE : form.availability());
		expert.setQualityScore(form.qualityScore());
		expert.setStandardFee(form.standardFee());
		expert.setRecruitmentSource(trimmed(form.recruitmentSource()));
		expert.setDateOnboarded(form.dateOnboarded());
		expert.setNotes(trimmed(form.notes()));
	}

	/** One expert this caller may read, or a 403 that cannot distinguish "not yours" from "no such row". */
	Expert read(UUID id) {
		return experts.findScoped(TenantContext.current(), id)
				.orElseThrow(() -> new ForbiddenException("No expert " + id + " in your roster"));
	}

	/**
	 * The same read, plus the write-side check.
	 *
	 * <p>Both, not one: {@code findScoped} keeps another brand's row out of a read, and
	 * {@link OwnershipGuard} is what the rest of the system calls before a mutation. An
	 * expert carries no assignee axis, so for this entity the two agree — and they are
	 * both here so that stays true if the entity ever gains one.
	 */
	private Expert readForWrite(UUID id) {
		Expert expert = read(id);
		ownership.assertCanAct(expert.getBrandId());
		return expert;
	}

	/** Every expert this caller may read, optionally narrowed to one brand. */
	private List<Expert> readable(UUID brandId) {
		return experts.findScoped(TenantContext.current()).stream()
				.filter(expert -> brandId == null || brandId.equals(expert.getBrandId()))
				.toList();
	}

	private List<RosterEntry> withLoad(List<Expert> page, Map<UUID, BigDecimal> pending) {
		Map<UUID, Load> byExpert = loads.forExperts(page.stream().map(Expert::getId).toList());
		return page.stream()
				.map(expert -> new RosterEntry(expert, byExpert.get(expert.getId()),
						pending.getOrDefault(expert.getId(), BigDecimal.ZERO)))
				.toList();
	}

	/**
	 * What every expert on this page is owed, fetched once per page rather than once per
	 * row. One query per distinct brand on the page — a single query for every caller but
	 * the cross-brand GM, who still never pays for a row that never ran.
	 */
	private Map<UUID, BigDecimal> pendingTotals(List<Expert> page) {
		Map<UUID, BigDecimal> combined = new HashMap<>();
		page.stream().map(Expert::getBrandId).distinct()
				.forEach(brandId -> combined.putAll(payouts.pendingByExpert(brandId)));
		return combined;
	}

	private static boolean matchesSearch(Expert expert, String search) {
		if (search == null || search.isBlank()) {
			return true;
		}
		String needle = search.trim().toLowerCase();
		return contains(expert.getFullName(), needle) || contains(expert.getInstitution(), needle);
	}

	private static boolean contains(String field, String needle) {
		return field != null && field.toLowerCase().contains(needle);
	}

	private static String trimmed(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static UUID actor() {
		return TenantContext.current().memberId();
	}
}
