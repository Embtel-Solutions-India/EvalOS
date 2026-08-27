# Payout Ledger & Weekly Settlement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a case and an expert is owed money; on payout day somebody ticks a week's drafts, sends one transfer outside EvalOS, and records it once — settling every ticked draft together.

**Architecture:** Two tables, because money owed and money sent are two facts. A `payout_ledger` row is one delivered draft an expert is owed for, opened inside `deliverToClient`'s transaction and prefilled from `Expert.standard_fee`. A `payout_payment` row is one transfer that actually left; the ledger rows point at it via `payment_id`. A payment's amount must equal the sum of the rows it settles, and the attach is one conditional `UPDATE ... WHERE status = 'PENDING'` with an affected-count assertion, so two people settling overlapping selections cannot both win. EvalOS never moves money.

**Tech Stack:** Java 21 + Spring Boot (Spring Data JPA, Bean Validation, Spring Security method security), PostgreSQL + Flyway, React + Vite + TypeScript + Tailwind, axios, vitest, JUnit 5 + AssertJ + Mockito + MockMvc.

**Spec:** `context/specs/16-payout-ledger.md` (the ledger, unchanged) and `context/specs/16b-weekly-settlement.md` (the payment model, which supersedes 16's payment half). **Read both.** 16b is the delta and cites 16 for everything it does not restate.

## Global Constraints

Copied from `context/code-standards.md`, `.serena/memories/conventions.md` and `.serena/memories/suggested_commands.md`. Every task's requirements implicitly include this section.

- **Dev machine is Windows / PowerShell 5.1.** `&&` does not exist — chain with `cd backend; if ($?) { .\mvnw.cmd verify }`. Use `.\mvnw.cmd`, never `./mvnw`. There is no root-level runner: every command runs from inside `backend/` or `frontend/`.
- **Backend build:** `.\mvnw.cmd verify` from `backend/`. No Docker needed; DB-dependent tests self-skip when no Postgres is reachable, and run automatically when one is.
- **Frontend build:** `npm run build` from `frontend/` (this is `tsc -b && vite build` and is the only typecheck entrypoint). `npm run test` is vitest, **rules modules only**. `npm run lint` is oxlint.
- **Java:** tabs for indentation, package `com.ie.evalos`. `record` for DTOs, **constructor injection only** (no field `@Autowired`), no Lombok. Finders return `Optional<T>`. Enums, never loose strings. Entities and DTOs stay separate — a controller never accepts or returns a JPA entity. Controllers are thin: `@Valid` → authorize → call a service → return a DTO in `ApiResponse`. Business rules live in `service`.
- **TypeScript/TSX:** **no semicolons**, single quotes, 2-space indent. No formatter is installed — match the surrounding file. One default-exported component per file, named after the file. Colors come from CSS custom properties, never hex literals or Tailwind palette classes. Conditional class strings use array `.join(' ')` — no `clsx`.
- **Brand scoping is not optional.** Scoped reads go through `ScopedRepository.findScoped(ctx)` / `findScoped(ctx, id)`. A scoped read that does not is a defect. Scoped entities extend `ScopedEntity`; foreign keys are raw `UUID`s, never JPA associations.
- **Append-only audit.** `AuditService.recordEvent(objectType, objectId, action, actorId, before, after)` on every creation, settlement, edit and confirmation. Never pass an entity as a snapshot — pass a DTO or a `Map`.
- **Applied migrations are never edited** (invariant 9). `V8__payout_ledger.sql` and `V2__brand.sql` are not touched; changes go in a new migration.
- **EvalOS never moves money.** No rail, no bank API, no stored credential. `expert.payment_detail` is write-only and must not appear in any DTO in this unit — not blanked, not masked, **not a member** (invariant 4).
- **Commit messages are sentences, not conventional commits.** Match `git log`: "Drop the inbound webhook HMAC, and stop email outranking the GHL contact id". End every commit message with:
  ```
  Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
  ```
- **Branch:** work on `Development` (the current branch). Do not push unless asked.

---

## File Structure

**Backend — created**

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V28__payout_payment.sql` | The whole schema change: new table, `payment_id`, the three dropped columns, the two `brand` settings, the two constraints |
| `backend/src/main/java/com/ie/evalos/domain/PayoutPayment.java` | One transfer that left |
| `backend/src/main/java/com/ie/evalos/repository/PayoutPaymentRepository.java` | Brand-scoped reads for payments |
| `backend/src/main/java/com/ie/evalos/service/PayoutService.java` | Every rule: opening a row, correcting an amount, settling, confirming, the weekly grouping |
| `backend/src/main/java/com/ie/evalos/web/PayoutController.java` | `/api/payouts` — the ledger and the batch view |
| `backend/src/main/java/com/ie/evalos/web/PaymentController.java` | `/api/payments` — history, detail, edit, confirm |
| `backend/src/test/java/com/ie/evalos/service/PayoutServiceTest.java` | The seven settlement rules, the week boundary, the status guards |
| `backend/src/test/java/com/ie/evalos/web/PayoutControllerTest.java` | Routes, roles, envelopes, and that no DTO carries `paymentDetail` |

**Backend — modified**

| File | Change |
|---|---|
| `domain/PayoutLedger.java` | Drop `method`/`reference`/`paidDate`; add `paymentId`; add the accessors consumers need |
| `domain/Brand.java` | Add `currency`, `payoutTermDays` + getters |
| `domain/AuditAction.java` | Add `PAYOUT_SETTLED` |
| `repository/PayoutLedgerRepository.java` | The conditional settle `UPDATE`, the confirm cascade, the pending-sum projection |
| `service/CaseLifecycleService.java` | Open the payout row inside `deliverToClient`'s transaction |
| `service/ExpertService.java` | Derive `totalPaymentsPending` from the ledger rather than the dead column |
| `test/.../repository/LocalPostgresIntegrationTest.java` | The two concurrency proofs, DB-gated |

**Frontend — created**

| File | Responsibility |
|---|---|
| `frontend/src/features/payouts/payoutRules.ts` | Pure functions + types: week maths, selection sum, settle-ability. **No React, no axios** — this is what vitest covers |
| `frontend/src/features/payouts/payoutRules.test.ts` | vitest over the above |
| `frontend/src/features/payouts/payoutApi.ts` | Every call this feature makes |
| `frontend/src/features/payouts/PayoutBatch.tsx` | The week's pending drafts grouped by expert. The screen somebody works down |
| `frontend/src/features/payouts/PaymentForm.tsx` | The multi-row record-payment dialog |
| `frontend/src/features/payouts/ExpertPayouts.tsx` | One expert: pending drafts, then payment history |
| `frontend/src/features/payouts/PaymentDetail.tsx` | One payment and every draft it settled |

**Frontend — modified**

| File | Change |
|---|---|
| `frontend/src/App.tsx` | `SCREENS['/payouts']`, plus the two parameterised routes |
| `frontend/src/features/shell/navigation.ts` | `becomes` text, and the two parameterised paths in the reachable-but-unlisted table |

**Not touched.** `service/RefundService.java` — its voiding behaviour is already correct under this design. `service/ScopePredicate.java` — brand-only scoping already covers both entities. `frontend/src/features/case/caseApi.ts` and `Timeline.tsx` — `PAYOUT_SETTLED` is written against a payment, never a case, so it cannot reach the case timeline.

---

### Task 1: Schema — the payment table, the two brand settings, the two constraints

**Files:**
- Create: `backend/src/main/resources/db/migration/V28__payout_payment.sql`
- Modify: `backend/src/main/java/com/ie/evalos/domain/Brand.java`
- Modify: `backend/src/main/java/com/ie/evalos/domain/PayoutLedger.java`
- Create: `backend/src/main/java/com/ie/evalos/domain/PayoutPayment.java`
- Create: `backend/src/main/java/com/ie/evalos/repository/PayoutPaymentRepository.java`
- Test: `backend/src/test/java/com/ie/evalos/domain/DomainInvariantsTest.java` (existing; it already walks every entity)

**Interfaces:**
- Consumes: `ScopedEntity(UUID brandId)` — supplies `id`, `brandId`, `createdAt` and refuses at `@PrePersist` to persist a row with no brand. `ScopePredicate.Fields.brandOnly(String)`.
- Produces: `PayoutPayment` with constructor `PayoutPayment(UUID brandId, UUID expertId, BigDecimal amount, String currency, String method, String reference, Instant paidDate, String notes, UUID recordedBy)`; getters `getExpertId()`, `getAmount()`, `getCurrency()`, `getMethod()`, `getReference()`, `getPaidDate()`, `getNotes()`, `getConfirmedAt()`, `getRecordedBy()`; setters `setMethod(String)`, `setReference(String)`, `setNotes(String)`, `setConfirmedAt(Instant)`. `PayoutLedger.getPaymentId()` / `setPaymentId(UUID)`, `getAmount()`, `setAmount(BigDecimal)`, `getCurrency()`, `getExpertId()`, `getCaseId()`, `getDueDate()`, `getRecordedBy()`. `Brand.getCurrency()`, `Brand.getPayoutTermDays()`. `PayoutPaymentRepository extends ScopedRepository<PayoutPayment>`.

- [ ] **Step 1: Write the migration**

Create `backend/src/main/resources/db/migration/V28__payout_payment.sql`:

```sql
-- Unit 16b. Money owed and money sent are two facts, so they are two tables.
--
-- A payout_ledger row is one delivered draft an expert is owed for. A payout_payment
-- row is one transfer that actually left the bank, covering however many drafts it
-- covered. The expert charges per draft and is paid weekly, so the two counts do not
-- match and one table would have to lie about one of them.
CREATE TABLE payout_payment (
    id           uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id     uuid          NOT NULL REFERENCES brand (id),
    -- NOT NULL, unlike payout_ledger.expert_id: a row can theoretically open on a
    -- case with no expert, but a payment with no payee is not a thing to record.
    expert_id    uuid          NOT NULL REFERENCES expert (id),
    -- > 0, not >= 0: a payment of nothing is not a payment, and it would sum into
    -- every money-out total while looking like a settled week.
    amount       numeric(12,2) NOT NULL CHECK (amount > 0),
    currency     text          NOT NULL,
    method       text          NOT NULL,
    reference    text          NOT NULL,
    paid_date    timestamptz   NOT NULL,
    -- On the payment rather than the row: notes describe the transfer, not one draft.
    notes        text,
    confirmed_at timestamptz,
    recorded_by  uuid          NOT NULL REFERENCES team_member (id),
    created_at   timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_brand_expert ON payout_payment (brand_id, expert_id);
CREATE INDEX idx_payment_brand_paid ON payout_payment (brand_id, paid_date);

ALTER TABLE payout_ledger
    ADD COLUMN payment_id uuid REFERENCES payout_payment (id);

CREATE INDEX idx_payout_payment ON payout_ledger (payment_id);

-- Never written — Unit 16 was never built, so no data is lost. They move to
-- payout_payment, where one transfer carries one method/reference/date instead of N
-- copies of it. Dropped rather than left dead: these sit on a money table and read as
-- load-bearing, and a future reader who finds payout_ledger.reference would
-- reasonably write code that half-works.
ALTER TABLE payout_ledger
    DROP COLUMN method,
    DROP COLUMN reference,
    DROP COLUMN paid_date;

-- A negative payout is not a smaller payout, it is money flowing the wrong way, and it
-- would sum into every total while looking like a discount. A refund is VOIDED plus its
-- own row, never a negative amount. NULL stays legal: it means "not decided yet", which
-- is the point of the column being nullable.
ALTER TABLE payout_ledger
    ADD CONSTRAINT payout_amount_not_negative CHECK (amount IS NULL OR amount >= 0);

-- Currency is what somebody is actually paid in. An expert on a GBP agreement paid a
-- USD number is wrong twice, in the amount and in the record of what was owed, so the
-- gap must not be able to reach the ledger at all.
ALTER TABLE payout_ledger
    ALTER COLUMN currency SET NOT NULL;

-- deliverToClient's `deliveryDate == null` guard is a check-then-act and Case has no
-- @Version, so two concurrent deliveries can both read null and both open a row. The
-- index cannot race; the loser rolls back. Partial on VOIDED so a refunded case that
-- re-delivers is not blocked by the tombstone.
CREATE UNIQUE INDEX uq_payout_per_case
    ON payout_ledger (case_id) WHERE status <> 'VOIDED';

-- Spec 16 reads "the brand's configured currency" and "the configured payout term".
-- V2__brand.sql has neither. Backfilled once so the NOT NULL can land, then left with
-- NO column default: a brand added later must state its currency, because that is the
-- one guess in this unit that spends real money. payout_term_days keeps a default —
-- a wrong due date is a visible annoyance, not a wrong payment.
ALTER TABLE brand
    ADD COLUMN currency text,
    ADD COLUMN payout_term_days int NOT NULL DEFAULT 7;

UPDATE brand SET currency = 'USD' WHERE currency IS NULL;

ALTER TABLE brand
    ALTER COLUMN currency SET NOT NULL;
```

- [ ] **Step 2: Add the two `Brand` fields**

In `backend/src/main/java/com/ie/evalos/domain/Brand.java`, add beside `webhookEndpointToken` (tabs, matching the file):

```java
	/**
	 * What experts on this brand are paid in. No column default on purpose — a brand
	 * added without one is a configuration error, not a USD payout.
	 */
	@Column(name = "currency", nullable = false)
	private String currency;

	/** Days from delivery to a payout's due date. Drives the weekly batch view. */
	@Column(name = "payout_term_days", nullable = false)
	private int payoutTermDays = 7;
```

and the two getters beside the existing ones:

```java
	public String getCurrency() {
		return currency;
	}

	public int getPayoutTermDays() {
		return payoutTermDays;
	}
```

- [ ] **Step 3: Create `PayoutPayment`**

Create `backend/src/main/java/com/ie/evalos/domain/PayoutPayment.java`:

```java
package com.ie.evalos.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One transfer that left the bank, covering however many delivered drafts it covered.
 *
 * <p>EvalOS did not send it. Somebody sent it through Zelle or whatever else, outside
 * this system, and recorded it here — so {@code method} and {@code reference} are
 * whatever they wrote down, and there is no rail behind either.
 *
 * <p><b>{@code amount}, {@code paidDate} and {@code expertId} are {@code updatable =
 * false}.</b> Correcting any of them means the payment as recorded did not happen,
 * which is a void-and-re-record rather than an edit. {@code method}, {@code reference}
 * and {@code notes} stay editable while unconfirmed, because somebody types a
 * reference wrong and has to fix it.
 */
@Entity
@Table(name = "payout_payment")
public class PayoutPayment extends ScopedEntity {

	@Column(name = "expert_id", nullable = false, updatable = false)
	private UUID expertId;

	@Column(name = "amount", nullable = false, updatable = false)
	private BigDecimal amount;

	@Column(name = "currency", nullable = false, updatable = false)
	private String currency;

	@Column(name = "method", nullable = false)
	private String method;

	@Column(name = "reference", nullable = false)
	private String reference;

	@Column(name = "paid_date", nullable = false, updatable = false)
	private Instant paidDate;

	@Column(name = "notes")
	private String notes;

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	/** The staff member who recorded that the money went out. */
	@Column(name = "recorded_by", nullable = false, updatable = false)
	private UUID recordedBy;

	protected PayoutPayment() {
		// for JPA
	}

	public PayoutPayment(UUID brandId, UUID expertId, BigDecimal amount, String currency, String method,
			String reference, Instant paidDate, String notes, UUID recordedBy) {
		super(brandId);
		this.expertId = expertId;
		this.amount = amount;
		this.currency = currency;
		this.method = method;
		this.reference = reference;
		this.paidDate = paidDate;
		this.notes = notes;
		this.recordedBy = recordedBy;
	}

	public UUID getExpertId() {
		return expertId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public String getReference() {
		return reference;
	}

	public void setReference(String reference) {
		this.reference = reference;
	}

	public Instant getPaidDate() {
		return paidDate;
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

	public Instant getConfirmedAt() {
		return confirmedAt;
	}

	public void setConfirmedAt(Instant confirmedAt) {
		this.confirmedAt = confirmedAt;
	}

	public UUID getRecordedBy() {
		return recordedBy;
	}
}
```

- [ ] **Step 4: Update `PayoutLedger`**

In `backend/src/main/java/com/ie/evalos/domain/PayoutLedger.java`: **delete** the `method`, `reference` and `paidDate` fields entirely, and add `paymentId` plus the accessors later tasks need. The class ends up as:

```java
package com.ie.evalos.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One delivered draft an expert is owed for, opened when the case is delivered.
 *
 * <p><b>How it was paid lives on {@link PayoutPayment}, not here</b> (Unit 16b). The
 * expert charges per draft and is paid weekly, so one transfer settles several of
 * these rows and carries one method, one reference and one date. Three copies of that
 * string is three chances to fumble a digit and three dates that can disagree.
 */
@Entity
@Table(name = "payout_ledger")
public class PayoutLedger extends ScopedEntity {

	@Column(name = "case_id", updatable = false)
	private UUID caseId;

	@Column(name = "expert_id", updatable = false)
	private UUID expertId;

	/**
	 * Null means "not decided yet" — an expert with no {@code standard_fee} gets a row
	 * the form makes somebody fill in. A prefill of 0 would be a number somebody could
	 * settle without noticing.
	 */
	@Column(name = "amount")
	private BigDecimal amount;

	@Column(name = "currency", nullable = false)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private PayoutStatus status;

	@Column(name = "due_date")
	private Instant dueDate;

	/** The transfer that settled this row, or null while it is still PENDING. */
	@Column(name = "payment_id")
	private UUID paymentId;

	/** The staff member who last recorded something about this row. */
	@Column(name = "recorded_by")
	private UUID recordedBy;

	protected PayoutLedger() {
		// for JPA
	}

	public PayoutLedger(UUID brandId, UUID caseId, UUID expertId, BigDecimal amount, String currency,
			Instant dueDate) {
		super(brandId);
		this.caseId = caseId;
		this.expertId = expertId;
		this.amount = amount;
		this.currency = currency;
		this.dueDate = dueDate;
		this.status = PayoutStatus.PENDING;
	}

	public UUID getCaseId() {
		return caseId;
	}

	public UUID getExpertId() {
		return expertId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	public String getCurrency() {
		return currency;
	}

	public PayoutStatus getStatus() {
		return status;
	}

	/** Written by the refund path (Unit 04) to void a pending row, and by Unit 16b. */
	public void setStatus(PayoutStatus status) {
		this.status = status;
	}

	public Instant getDueDate() {
		return dueDate;
	}

	public UUID getPaymentId() {
		return paymentId;
	}

	public UUID getRecordedBy() {
		return recordedBy;
	}

	public void setRecordedBy(UUID recordedBy) {
		this.recordedBy = recordedBy;
	}
}
```

- [ ] **Step 5: Create `PayoutPaymentRepository`**

Create `backend/src/main/java/com/ie/evalos/repository/PayoutPaymentRepository.java`:

```java
package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.PayoutPayment;
import com.ie.evalos.service.ScopePredicate;

/**
 * Payments are brand-scoped only, exactly like the ledger rows they settle:
 * {@code recorded_by} names who sent the money, not who owns the row, so it is not an
 * assignee axis. No new scoping code — {@code brandOnly} already covers this.
 */
public interface PayoutPaymentRepository extends ScopedRepository<PayoutPayment> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	@Override
	default ScopePredicate.Fields scopeFields() {
		return SCOPE;
	}

	/** One expert's payment history, newest first. Call only with a scoped brand id. */
	List<PayoutPayment> findByBrandIdAndExpertIdOrderByPaidDateDesc(UUID brandId, UUID expertId);
}
```

- [ ] **Step 6: Run the build to verify the schema and entities agree**

```powershell
cd backend; if ($?) { .\mvnw.cmd verify }
```

Expected: BUILD SUCCESS. `ddl-auto: validate` runs in the DB-gated suite whenever Postgres is reachable and will fail if any column name here disagrees with an entity. If the suite prints `[db] ... skipped`, start Postgres and re-run — **do not proceed past this step on a skipped DB suite**, because every later task builds on this schema.

If it reports `TestEngine ... failed to discover tests`, run `.\mvnw.cmd clean verify` once (stale scaffold output).

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/resources/db/migration/V28__payout_payment.sql backend/src/main/java/com/ie/evalos/domain/ backend/src/main/java/com/ie/evalos/repository/PayoutPaymentRepository.java
git commit -m @'
Split money sent from money owed: payout_payment, and the two brand settings spec 16 assumed

One transfer settles several delivered drafts, so how it was paid moves off the
ledger row and onto its own table. brand gains currency (NOT NULL, no default)
and payout_term_days, both of which spec 16 read and V2 never had.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
'@
```

---

### Task 2: `PAYOUT_SETTLED`, and the pending sum stops reading a dead column

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/domain/AuditAction.java`
- Modify: `backend/src/main/java/com/ie/evalos/repository/PayoutLedgerRepository.java`
- Test: `backend/src/test/java/com/ie/evalos/service/PayoutServiceTest.java` (created in Task 4; this task adds no test of its own — the projection is exercised there)

**Interfaces:**
- Consumes: `PayoutStatus`, `PayoutLedger` from Task 1.
- Produces: `AuditAction.PAYOUT_SETTLED`. `PayoutLedgerRepository.pendingTotalsByExpert(UUID brandId)` returning `List<ExpertPendingTotal>` where `ExpertPendingTotal` is a projection interface with `UUID getExpertId()` and `BigDecimal getTotal()`. `PayoutLedgerRepository.attachToPayment(UUID paymentId, Collection<UUID> ids, UUID brandId, UUID actor)` returning `int`. `PayoutLedgerRepository.confirmForPayment(UUID paymentId)` returning `int`. `PayoutLedgerRepository.findByPaymentId(UUID paymentId)` returning `List<PayoutLedger>`.

- [ ] **Step 1: Add the audit action**

In `backend/src/main/java/com/ie/evalos/domain/AuditAction.java`, add after `NOTE_ADDED` (mind the comma on the line above):

```java
	,
	/**
	 * A transfer was recorded as sent, settling one or more payout rows (Unit 16b),
	 * written against the <strong>payment</strong>.
	 *
	 * <p>Its own action rather than {@code CREATED}, for the reason {@link #CHASED} and
	 * {@link #FLAGGED} have theirs: this is the row every "money out" question filters
	 * on, and a metric needs something to filter on. Confirming a payment and correcting
	 * a reference stay {@code UPDATED} — those are ordinary field changes on a record
	 * that already exists.
	 *
	 * <p>Never reaches the case timeline: the object acted on is a payment, not a case.
	 */
	PAYOUT_SETTLED
```

- [ ] **Step 2: Add the four repository methods**

In `backend/src/main/java/com/ie/evalos/repository/PayoutLedgerRepository.java`, add the imports and the methods below. Keep the existing `SCOPE`, `scopeFields()` and `findByCaseIdAndStatus` exactly as they are — `RefundService` depends on the last one.

```java
	/** One expert's pending total. Derived; {@code expert.total_payments_pending} stays dead. */
	interface ExpertPendingTotal {

		UUID getExpertId();

		BigDecimal getTotal();
	}

	/**
	 * What every expert on a brand is owed, in one query rather than one per row.
	 *
	 * <p>{@code Expert.total_payments_pending} is {@code NOT NULL DEFAULT 0} and nothing
	 * has ever written it. A running total maintained by hand has to be adjusted on
	 * create, on settle, on void and on every amount correction — four chances to drift
	 * on a figure about money. Unit 11 set this rule for the same situation.
	 */
	@Query("""
			select p.expertId as expertId, sum(p.amount) as total
			  from PayoutLedger p
			 where p.brandId = :brandId
			   and p.status = com.ie.evalos.domain.PayoutStatus.PENDING
			   and p.amount is not null
			 group by p.expertId
			""")
	List<ExpertPendingTotal> pendingTotalsByExpert(@Param("brandId") UUID brandId);

	/**
	 * Attach rows to a payment and mark them PAID — <b>one conditional statement, not a
	 * read followed by saves.</b>
	 *
	 * <p>Checking each row is {@code PENDING} and then writing it is a check-then-act
	 * that two concurrent settlements can both win, the same shape
	 * {@code uq_payout_per_case} exists for. Here the guard covers a <em>set</em> of rows,
	 * which a unique index has nothing to say about, so the precondition rides in the
	 * {@code WHERE} clause and the caller asserts the affected count. The database
	 * decides once and cannot decide twice.
	 *
	 * @return how many rows were actually taken; anything less than the requested count
	 *         means another transaction won one, and the caller must roll the whole
	 *         settlement back
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update PayoutLedger p
			   set p.paymentId = :paymentId,
			       p.status = com.ie.evalos.domain.PayoutStatus.PAID,
			       p.recordedBy = :actor
			 where p.id in :ids
			   and p.brandId = :brandId
			   and p.status = com.ie.evalos.domain.PayoutStatus.PENDING
			""")
	int attachToPayment(@Param("paymentId") UUID paymentId, @Param("ids") Collection<UUID> ids,
			@Param("brandId") UUID brandId, @Param("actor") UUID actor);

	/** One transfer, one acknowledgement: confirming a payment confirms everything it settled. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update PayoutLedger p
			   set p.status = com.ie.evalos.domain.PayoutStatus.CONFIRMED
			 where p.paymentId = :paymentId
			   and p.status = com.ie.evalos.domain.PayoutStatus.PAID
			""")
	int confirmForPayment(@Param("paymentId") UUID paymentId);

	/** The drafts one payment settled. Call only with a payment id from a scoped read. */
	List<PayoutLedger> findByPaymentId(UUID paymentId);
```

Add these imports at the top of the file:

```java
import java.math.BigDecimal;
import java.util.Collection;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
```

- [ ] **Step 3: Build**

```powershell
cd backend; if ($?) { .\mvnw.cmd verify }
```

Expected: BUILD SUCCESS. A JPQL syntax error surfaces here as a context-load failure in the existing slice tests, not at runtime.

- [ ] **Step 4: Commit**

```powershell
git add backend/src/main/java/com/ie/evalos/domain/AuditAction.java backend/src/main/java/com/ie/evalos/repository/PayoutLedgerRepository.java
git commit -m @'
Give settlement its own audit action, and the queries the ledger needs

attachToPayment is one conditional UPDATE with an affected-count contract: the
guard covers a set of rows, which a unique index cannot express, so the
precondition rides in the WHERE clause.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
'@
```

---

### Task 3: Delivering a case opens the payout row

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/service/CaseLifecycleService.java:622-628`
- Create: `backend/src/main/java/com/ie/evalos/service/PayoutService.java` (the opening half only; settlement lands in Task 4)
- Test: `backend/src/test/java/com/ie/evalos/service/PayoutServiceTest.java`

**Interfaces:**
- Consumes: `Case.getExpertId()`, `Case.getBrandId()`, `Case.getDeliveryDate()`; `Brand.getCurrency()`, `Brand.getPayoutTermDays()`; `Expert.getStandardFee()`; `PayoutLedger(UUID brandId, UUID caseId, UUID expertId, BigDecimal amount, String currency, Instant dueDate)`.
- Produces: `PayoutService.openForDelivery(Case delivered)` returning `Optional<PayoutLedger>` — empty when the case has no expert.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/ie/evalos/service/PayoutServiceTest.java`:

```java
package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.PayoutPaymentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The rules that decide what an expert is owed and when it may be recorded as sent.
 *
 * <p>Plain unit tests over mocked repositories: none of this needs a database, and the
 * two things that genuinely do — the partial unique index and two concurrent
 * settlements — are proved in {@code LocalPostgresIntegrationTest} instead.
 */
class PayoutServiceTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID EXPERT_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

	private static final UUID CASE_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000001");

	private PayoutLedgerRepository payouts;

	private PayoutPaymentRepository payments;

	private ExpertRepository experts;

	private BrandRepository brands;

	private AuditService audit;

	private PayoutService service;

	@BeforeEach
	void setUp() {
		payouts = mock(PayoutLedgerRepository.class);
		payments = mock(PayoutPaymentRepository.class);
		experts = mock(ExpertRepository.class);
		brands = mock(BrandRepository.class);
		audit = mock(AuditService.class);
		service = new PayoutService(payouts, payments, experts, brands, audit);

		given(payouts.save(any(PayoutLedger.class))).willAnswer(call -> call.getArgument(0));
	}

	@Test
	void deliveryOpensOnePendingRowPrefilledFromTheStandardFee() {
		givenBrand("USD", 7);
		givenExpert(new BigDecimal("350.00"));
		Case delivered = deliveredCase(EXPERT_ID);

		service.openForDelivery(delivered);

		ArgumentCaptor<PayoutLedger> saved = ArgumentCaptor.forClass(PayoutLedger.class);
		verify(payouts).save(saved.capture());
		assertThat(saved.getValue().getStatus()).isEqualTo(PayoutStatus.PENDING);
		assertThat(saved.getValue().getAmount()).isEqualByComparingTo("350.00");
		assertThat(saved.getValue().getCurrency()).isEqualTo("USD");
		assertThat(saved.getValue().getExpertId()).isEqualTo(EXPERT_ID);
		assertThat(saved.getValue().getCaseId()).isEqualTo(CASE_ID);
		// recorded_by is null: nobody has recorded anything yet.
		assertThat(saved.getValue().getRecordedBy()).isNull();
	}

	@Test
	void anExpertWithNoStandardFeeGetsARowWithNoAmount() {
		givenBrand("USD", 7);
		givenExpert(null);

		service.openForDelivery(deliveredCase(EXPERT_ID));

		ArgumentCaptor<PayoutLedger> saved = ArgumentCaptor.forClass(PayoutLedger.class);
		verify(payouts).save(saved.capture());
		// Null, never zero: a prefill of 0 is a number somebody could settle without noticing.
		assertThat(saved.getValue().getAmount()).isNull();
	}

	@Test
	void theDueDateIsDeliveryPlusTheBrandsPayoutTerm() {
		givenBrand("USD", 14);
		givenExpert(new BigDecimal("350.00"));
		Case delivered = deliveredCase(EXPERT_ID);

		service.openForDelivery(delivered);

		ArgumentCaptor<PayoutLedger> saved = ArgumentCaptor.forClass(PayoutLedger.class);
		verify(payouts).save(saved.capture());
		assertThat(saved.getValue().getDueDate())
				.isEqualTo(delivered.getDeliveryDate().plus(14, ChronoUnit.DAYS));
	}

	@Test
	void aCaseDeliveredWithNoExpertOpensNoRow() {
		givenBrand("USD", 7);

		Optional<PayoutLedger> opened = service.openForDelivery(deliveredCase(null));

		assertThat(opened).isEmpty();
		verify(payouts, never()).save(any());
	}

	private void givenBrand(String currency, int termDays) {
		Brand brand = mock(Brand.class);
		given(brand.getCurrency()).willReturn(currency);
		given(brand.getPayoutTermDays()).willReturn(termDays);
		given(brands.findById(BRAND_IE)).willReturn(Optional.of(brand));
	}

	private void givenExpert(BigDecimal standardFee) {
		Expert expert = mock(Expert.class);
		given(expert.getStandardFee()).willReturn(standardFee);
		given(experts.findById(EXPERT_ID)).willReturn(Optional.of(expert));
	}

	private static Case deliveredCase(UUID expertId) {
		Case subject = mock(Case.class);
		given(subject.getId()).willReturn(CASE_ID);
		given(subject.getBrandId()).willReturn(BRAND_IE);
		given(subject.getExpertId()).willReturn(expertId);
		given(subject.getDeliveryDate()).willReturn(Instant.parse("2026-08-26T18:00:00Z"));
		return subject;
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

```powershell
cd backend; if ($?) { .\mvnw.cmd test "-Dtest=PayoutServiceTest" }
```

Expected: compilation failure — `PayoutService` does not exist.

- [ ] **Step 3: Write `PayoutService`'s opening half**

Create `backend/src/main/java/com/ie/evalos/service/PayoutService.java`:

```java
package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.PayoutPaymentRepository;

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

	private final AuditService audit;

	PayoutService(PayoutLedgerRepository payouts, PayoutPaymentRepository payments, ExpertRepository experts,
			BrandRepository brands, AuditService audit) {
		this.payouts = payouts;
		this.payments = payments;
		this.experts = experts;
		this.brands = brands;
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

		BigDecimal standardFee = experts.findById(expertId).map(Expert::getStandardFee).orElse(null);
		Instant dueDate = delivered.getDeliveryDate().plus(brand.getPayoutTermDays(), ChronoUnit.DAYS);

		PayoutLedger row = payouts.save(new PayoutLedger(delivered.getBrandId(), delivered.getId(), expertId,
				standardFee, brand.getCurrency(), dueDate));

		audit.recordEvent("PAYOUT", row.getId(), AuditAction.CREATED, null,
				null, Map.of("caseId", delivered.getId(), "expertId", expertId, "status", "PENDING"));
		return Optional.of(row);
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

```powershell
cd backend; if ($?) { .\mvnw.cmd test "-Dtest=PayoutServiceTest" }
```

Expected: 4 tests, all PASS.

- [ ] **Step 5: Wire it into `deliverToClient`**

In `backend/src/main/java/com/ie/evalos/service/CaseLifecycleService.java`, add the field and constructor parameter for `PayoutService`, then change `deliverToClient` (currently at line 622) to:

```java
	@Transactional
	public Case deliverToClient(UUID caseId) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.DELIVER_TO_CLIENT);
		requireState(subject.getDeliveryDate() == null, "the case has already been delivered");

		Case delivered = apply(subject, to, Action.DELIVER_TO_CLIENT, null, c -> c.setDeliveryDate(Instant.now()));

		// Same transaction on purpose: a delivery that rolls back leaves no payout row,
		// and a payout-row failure rolls the delivery back. The deliveryDate guard above
		// is a check-then-act with no @Version behind it, so uq_payout_per_case is what
		// actually stops two concurrent deliveries opening two rows — the loser's insert
		// fails and takes its whole delivery with it.
		if (payouts.openForDelivery(delivered).isEmpty()) {
			notifyNoExpertOnDelivery(delivered);
		}
		return delivered;
	}
```

Add the reporting helper beside it. Follow whatever `NotificationService` / event-publishing call the surrounding methods already use in this class — read two neighbouring transition methods and mirror them exactly rather than inventing a call:

```java
	/**
	 * A case delivered with no expert assigned gets no payout row, and that is not
	 * silent. It should be impossible — FINAL_DELIVERY is only reachable through
	 * EXPERT_SIGNING — which is why it must be reported if it happens.
	 */
	private void notifyNoExpertOnDelivery(Case delivered) {
		// Mirror the publish/notify call used by the neighbouring transitions in this class.
	}
```

- [ ] **Step 6: Run the full backend suite**

```powershell
cd backend; if ($?) { .\mvnw.cmd verify }
```

Expected: BUILD SUCCESS. `CaseLifecycleServiceTest` constructs `CaseLifecycleService` directly — it will need the new constructor argument, so add a mocked `PayoutService` there. If any existing test now fails on an unexpected `payouts.save`, that test is delivering a case; give it a stubbed `PayoutService` rather than weakening the assertion.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/ie/evalos/service/ backend/src/test/java/com/ie/evalos/service/
git commit -m @'
Delivering a case opens the payout row, in the delivering transaction

Delivered and owed are one fact. A prefill comes from the expert's standard fee
and stays null when there isn't one, so the form has to make somebody decide.
A delivery with no expert opens nothing and says so.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
'@
```

---

### Task 4: Settlement — the seven rules and the race that closes them

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/service/PayoutService.java`
- Test: `backend/src/test/java/com/ie/evalos/service/PayoutServiceTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–3; `TenantContext.current()` returning `TenantContext(UUID memberId, Role role, UUID brandId, UUID teamId)`; `ScopedRepository.findScoped(ctx, id)` returning `Optional<T>`; `InvalidRequestException(String)` → 400; `IllegalTransitionException(String)` → 409; `ForbiddenException(String)` → 403.
- Produces: `PayoutService.SettleForm` (record), `PayoutService.settle(SettleForm)` returning `UUID` (the new payment's id), `PayoutService.MAY_RECORD` (the role set).

- [ ] **Step 1: Write the failing tests**

Append to `backend/src/test/java/com/ie/evalos/service/PayoutServiceTest.java` (add the imports each new symbol needs):

```java
	@Test
	void settlingThreeDraftsCreatesOnePaymentAndTakesAllThree() {
		givenEnmCaller();
		PayoutLedger a = pending("350.00");
		PayoutLedger b = pending("350.00");
		PayoutLedger c = pending("400.00");
		givenScoped(a, b, c);
		given(payments.save(any(PayoutPayment.class))).willAnswer(call -> call.getArgument(0));
		given(payouts.attachToPayment(any(), any(), any(), any())).willReturn(3);

		service.settle(form(List.of(a.getId(), b.getId(), c.getId()), "1100.00"));

		ArgumentCaptor<PayoutPayment> saved = ArgumentCaptor.forClass(PayoutPayment.class);
		verify(payments).save(saved.capture());
		assertThat(saved.getValue().getAmount()).isEqualByComparingTo("1100.00");
		assertThat(saved.getValue().getExpertId()).isEqualTo(EXPERT_ID);
		assertThat(saved.getValue().getCurrency()).isEqualTo("USD");
		assertThat(saved.getValue().getReference()).isEqualTo("ZELLE-08262026-001");
	}

	@Test
	void aPaymentThatIsNotTheSumOfItsDraftsIsRefused() {
		givenEnmCaller();
		PayoutLedger a = pending("350.00");
		PayoutLedger b = pending("350.00");
		givenScoped(a, b);

		assertThatThrownBy(() -> service.settle(form(List.of(a.getId(), b.getId()), "800.00")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("700.00");

		// And nothing is left behind: a payment whose amount is not what it settled is a
		// ledger that disagrees with the bank silently.
		verify(payments, never()).save(any());
	}

	@Test
	void scaleDoesNotDecideWhetherTheSumMatches() {
		// 350.0 + 350 == 700.00 by value. BigDecimal.equals says otherwise, which is why
		// the check is compareTo — a settlement refused on trailing zeroes is unfixable
		// from the UI.
		givenEnmCaller();
		PayoutLedger a = pending("350.0");
		PayoutLedger b = pending("350");
		givenScoped(a, b);
		given(payments.save(any(PayoutPayment.class))).willAnswer(call -> call.getArgument(0));
		given(payouts.attachToPayment(any(), any(), any(), any())).willReturn(2);

		service.settle(form(List.of(a.getId(), b.getId()), "700.00"));

		verify(payments).save(any());
	}

	@Test
	void oneTransferPaysOneExpert() {
		givenEnmCaller();
		PayoutLedger mine = pending("350.00");
		PayoutLedger theirs = pending("350.00", UUID.randomUUID());
		givenScoped(mine, theirs);

		assertThatThrownBy(() -> service.settle(form(List.of(mine.getId(), theirs.getId()), "700.00")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("one expert");
		verify(payments, never()).save(any());
	}

	@Test
	void aDraftWithNoAmountCannotBeSettled() {
		givenEnmCaller();
		PayoutLedger undecided = pending(null);
		givenScoped(undecided);

		assertThatThrownBy(() -> service.settle(form(List.of(undecided.getId()), "350.00")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("no amount");
	}

	@Test
	void anAlreadySettledDraftCannotBeSettledAgain() {
		givenEnmCaller();
		PayoutLedger already = pending("350.00");
		already.setStatus(PayoutStatus.PAID);
		givenScoped(already);

		assertThatThrownBy(() -> service.settle(form(List.of(already.getId()), "350.00")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("PAID");
	}

	@Test
	void aDraftOutsideTheCallersScopeIsNotFound() {
		givenEnmCaller();
		UUID stranger = UUID.randomUUID();
		given(payouts.findScoped(any(), eq(stranger))).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.settle(form(List.of(stranger), "350.00")))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void losingTheRaceForOneRowRollsTheWholeSettlementBack() {
		givenEnmCaller();
		PayoutLedger a = pending("350.00");
		PayoutLedger b = pending("350.00");
		givenScoped(a, b);
		given(payments.save(any(PayoutPayment.class))).willAnswer(call -> call.getArgument(0));
		// Someone else took one between the read and the write.
		given(payouts.attachToPayment(any(), any(), any(), any())).willReturn(1);

		assertThatThrownBy(() -> service.settle(form(List.of(a.getId(), b.getId()), "700.00")))
				.isInstanceOf(IllegalTransitionException.class)
				.hasMessageContaining("nothing was recorded");
	}

	@Test
	void aCaseManagerMayNotRecordThatMoneyWentOut() {
		givenCaller(Role.CASE_MANAGER);

		assertThatThrownBy(() -> service.settle(form(List.of(UUID.randomUUID()), "350.00")))
				.isInstanceOf(ForbiddenException.class);
		verify(payments, never()).save(any());
	}
```

and these helpers at the bottom of the class:

```java
	private PayoutLedger pending(String amount) {
		return pending(amount, EXPERT_ID);
	}

	private PayoutLedger pending(String amount, UUID expertId) {
		PayoutLedger row = new PayoutLedger(BRAND_IE, UUID.randomUUID(), expertId,
				amount == null ? null : new BigDecimal(amount), "USD", Instant.parse("2026-09-02T00:00:00Z"));
		ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
		return row;
	}

	private void givenScoped(PayoutLedger... rows) {
		for (PayoutLedger row : rows) {
			given(payouts.findScoped(any(), eq(row.getId()))).willReturn(Optional.of(row));
		}
	}

	private PayoutService.SettleForm form(List<UUID> ids, String amount) {
		return new PayoutService.SettleForm(EXPERT_ID, ids, new BigDecimal(amount), "Zelle",
				"ZELLE-08262026-001", Instant.parse("2026-08-26T18:00:00Z"), "Weekly expert payout");
	}

	private void givenEnmCaller() {
		givenCaller(Role.EXPERT_NETWORK_MANAGER);
	}

	private void givenCaller(Role role) {
		TenantContext ctx = new TenantContext(ACTOR_ID, role, BRAND_IE, null);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(new StaffPrincipal(
						ACTOR_ID, "someone@evalos.local", "", role, BRAND_IE, null), null, List.of()));
		assertThat(TenantContext.current()).isEqualTo(ctx);
	}
```

> **Note on `givenCaller`:** `TenantContext.current()` reads the Spring `SecurityContextHolder`. Read `backend/src/test/java/com/ie/evalos/service/ExpertServiceTest.java` and copy **exactly** how it establishes a caller — it already solves this, and the `StaffPrincipal` constructor signature there is authoritative. Add an `@AfterEach` that calls `SecurityContextHolder.clearContext()` so callers do not leak between tests. Declare `ACTOR_ID` beside the other constants.

- [ ] **Step 2: Run to verify it fails**

```powershell
cd backend; if ($?) { .\mvnw.cmd test "-Dtest=PayoutServiceTest" }
```

Expected: compilation failure — `PayoutService.SettleForm` and `settle` do not exist.

- [ ] **Step 3: Implement settlement**

Add to `PayoutService`:

```java
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
	 */
	static final Set<Role> MAY_RECORD = EnumSet.of(Role.GM, Role.BRAND_MANAGER, Role.EXPERT_NETWORK_MANAGER);

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

	private static void requireMayRecord(TenantContext ctx) {
		if (!MAY_RECORD.contains(ctx.role())) {
			throw new ForbiddenException("Only the GM, a Brand Manager or the ENM may record a payout");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
```

Add the imports: `java.util.EnumSet`, `java.util.List`, `java.util.Set`, `com.ie.evalos.common.ForbiddenException`, `com.ie.evalos.common.InvalidRequestException`, `com.ie.evalos.domain.IllegalTransitionException`, `com.ie.evalos.domain.PayoutPayment`, `com.ie.evalos.domain.PayoutStatus`, `com.ie.evalos.domain.Role`, `com.ie.evalos.security.TenantContext`, and the `jakarta.validation.constraints` annotations used above.

- [ ] **Step 4: Run to verify it passes**

```powershell
cd backend; if ($?) { .\mvnw.cmd test "-Dtest=PayoutServiceTest" }
```

Expected: 13 tests, all PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/ie/evalos/service/PayoutService.java backend/src/test/java/com/ie/evalos/service/PayoutServiceTest.java
git commit -m @'
Settle a week of drafts with one payment, and refuse anything that would let the ledger drift

A payment's amount must equal the sum of what it settles, compared by value rather
than scale. The attach is one conditional UPDATE with an affected-count assertion,
so losing a row to a concurrent settlement rolls the payment back with it.

The ENM may record payouts: they send the transfer, so they record it. Guarded in
the service as well as the route, because it is a money path.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
'@
```

---

### Task 5: Reads, corrections and confirmation

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/service/PayoutService.java`
- Modify: `backend/src/main/java/com/ie/evalos/service/ExpertService.java`
- Test: `backend/src/test/java/com/ie/evalos/service/PayoutServiceTest.java`

**Interfaces:**
- Consumes: everything above; `BusinessCalendar.ZONE` (`ZoneId`, `America/Los_Angeles`).
- Produces: `PayoutService.LedgerRow`, `PayoutService.ExpertGroup`, `PayoutService.BatchView`, `PayoutService.PaymentRow`, `PayoutService.PaymentDetailView`, `PayoutService.PaymentEditForm`; methods `batch(LocalDate weekOf)`, `history(UUID expertId)`, `payment(UUID paymentId)`, `correctAmount(UUID payoutId, BigDecimal amount)`, `editPayment(UUID paymentId, PaymentEditForm form)`, `confirm(UUID paymentId)`, `pendingByExpert(UUID brandId)`, and the static `weekStart(Instant)`.

- [ ] **Step 1: Write the failing tests**

Append to `PayoutServiceTest`:

```java
	@Test
	void aWeekRunsMondayToSundayInTheBusinessZone() {
		// 2026-08-24 is a Monday. 07:00Z on that day is 00:00 Pacific — the first instant
		// of the week, and it must land in this week rather than the one before.
		assertThat(PayoutService.weekStart(Instant.parse("2026-08-24T07:00:00Z")))
				.isEqualTo(LocalDate.of(2026, 8, 24));
		// One second earlier is still Sunday in California.
		assertThat(PayoutService.weekStart(Instant.parse("2026-08-24T06:59:59Z")))
				.isEqualTo(LocalDate.of(2026, 8, 17));
		// Sunday evening Pacific is late Monday UTC, and belongs to the week that is ending.
		assertThat(PayoutService.weekStart(Instant.parse("2026-08-31T03:00:00Z")))
				.isEqualTo(LocalDate.of(2026, 8, 24));
	}

	@Test
	void anAmountMayBeCorrectedWhilePending() {
		givenEnmCaller();
		PayoutLedger row = pending("350.00");
		givenScoped(row);

		service.correctAmount(row.getId(), new BigDecimal("400.00"));

		assertThat(row.getAmount()).isEqualByComparingTo("400.00");
		assertThat(row.getRecordedBy()).isEqualTo(ACTOR_ID);
	}

	@Test
	void aSettledAmountIsFrozen() {
		givenEnmCaller();
		PayoutLedger row = pending("350.00");
		row.setStatus(PayoutStatus.PAID);
		givenScoped(row);

		assertThatThrownBy(() -> service.correctAmount(row.getId(), new BigDecimal("400.00")))
				.isInstanceOf(IllegalTransitionException.class);
		// Its amount is part of a payment's sum; changing it would break that sum after the fact.
		assertThat(row.getAmount()).isEqualByComparingTo("350.00");
	}

	@Test
	void aNegativeAmountIsRefused() {
		givenEnmCaller();
		PayoutLedger row = pending("350.00");
		givenScoped(row);

		assertThatThrownBy(() -> service.correctAmount(row.getId(), new BigDecimal("-1.00")))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void confirmingAPaymentConfirmsEveryDraftItSettled() {
		givenEnmCaller();
		PayoutPayment payment = paidPayment();
		given(payments.findScoped(any(), eq(payment.getId()))).willReturn(Optional.of(payment));
		given(payouts.confirmForPayment(payment.getId())).willReturn(3);

		service.confirm(payment.getId());

		assertThat(payment.getConfirmedAt()).isNotNull();
		verify(payouts).confirmForPayment(payment.getId());
	}

	@Test
	void aConfirmedPaymentIsTerminal() {
		givenEnmCaller();
		PayoutPayment payment = paidPayment();
		payment.setConfirmedAt(Instant.parse("2026-08-27T10:00:00Z"));
		given(payments.findScoped(any(), eq(payment.getId()))).willReturn(Optional.of(payment));

		assertThatThrownBy(() -> service.confirm(payment.getId()))
				.isInstanceOf(IllegalTransitionException.class);
	}

	@Test
	void aReferenceIsCorrectableUntilTheExpertConfirms() {
		givenEnmCaller();
		PayoutPayment payment = paidPayment();
		given(payments.findScoped(any(), eq(payment.getId()))).willReturn(Optional.of(payment));

		service.editPayment(payment.getId(),
				new PayoutService.PaymentEditForm("Zelle", "ZELLE-08262026-002", "corrected"));

		assertThat(payment.getReference()).isEqualTo("ZELLE-08262026-002");
	}

	@Test
	void aConfirmedPaymentIsFrozen() {
		givenEnmCaller();
		PayoutPayment payment = paidPayment();
		payment.setConfirmedAt(Instant.parse("2026-08-27T10:00:00Z"));
		given(payments.findScoped(any(), eq(payment.getId()))).willReturn(Optional.of(payment));

		assertThatThrownBy(() -> service.editPayment(payment.getId(),
				new PayoutService.PaymentEditForm("Zelle", "ZELLE-X", null)))
				.isInstanceOf(IllegalTransitionException.class);
	}

	private PayoutPayment paidPayment() {
		PayoutPayment payment = new PayoutPayment(BRAND_IE, EXPERT_ID, new BigDecimal("1100.00"), "USD",
				"Zelle", "ZELLE-08262026-001", Instant.parse("2026-08-26T18:00:00Z"), null, ACTOR_ID);
		ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
		return payment;
	}
```

- [ ] **Step 2: Run to verify it fails**

```powershell
cd backend; if ($?) { .\mvnw.cmd test "-Dtest=PayoutServiceTest" }
```

Expected: compilation failure — `weekStart`, `correctAmount`, `confirm`, `editPayment` and `PaymentEditForm` do not exist.

- [ ] **Step 3: Implement the reads, the correction and the confirmation**

Add to `PayoutService`:

```java
	/** One draft an expert is owed for, as a screen sees it. */
	public record LedgerRow(UUID id, UUID caseId, String caseCode, UUID expertId, String expertName,
			BigDecimal amount, String currency, PayoutStatus status, Instant dueDate, boolean overdue,
			UUID paymentId) {
	}

	/** One expert's drafts in one week, with what they add up to. */
	public record ExpertGroup(UUID expertId, String expertName, List<LedgerRow> drafts, BigDecimal subtotal,
			String currency) {
	}

	/** A week on the batch screen. */
	public record BatchView(LocalDate weekStart, LocalDate weekEnd, List<ExpertGroup> groups, BigDecimal due,
			BigDecimal paid, BigDecimal overdue) {
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
		audit.recordEvent("PAYOUT", payoutId, AuditAction.UPDATED, ctx.memberId(),
				Map.of("amount", String.valueOf(before)), Map.of("amount", amount));
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

	/** What each expert on a brand is owed. Derived — {@code total_payments_pending} stays dead. */
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
```

Add imports: `java.time.DayOfWeek`, `java.time.LocalDate`, `java.time.temporal.TemporalAdjusters`, `java.util.stream.Collectors`.

> **`batch`, `history` and `payment` (the read projections):** build these three from `payouts.findScoped(ctx)` / `payments.findScoped(ctx)`, grouping with `weekStart`. They need expert and case names, so batch the lookups — **one query for the whole page, never one per row** (`mem:backend/persistence`). Model them on `CaseBoardService`, which already does exactly this shape of grouped, name-resolved projection. `overdue` on a `LedgerRow` is computed here as `status == PENDING && dueDate.isBefore(Instant.now())` — it is never a `PayoutStatus` value.

- [ ] **Step 4: Run to verify it passes**

```powershell
cd backend; if ($?) { .\mvnw.cmd test "-Dtest=PayoutServiceTest" }
```

Expected: 21 tests, all PASS.

- [ ] **Step 5: Point the roster's pending total at the derived sum**

In `backend/src/main/java/com/ie/evalos/service/ExpertService.java`, find where `RosterEntry` is built and replace any read of `expert.getTotalPaymentsPending()` with a lookup into `payoutService.pendingByExpert(brandId)`, defaulting to `BigDecimal.ZERO`. Fetch the map **once per page**, before the row loop — not per row. Leave the column itself alone: it stays `0` in the database and unread, exactly like `current_active_count`.

- [ ] **Step 6: Full suite**

```powershell
cd backend; if ($?) { .\mvnw.cmd verify }
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/ie/evalos/service/
git commit -m @'
Weekly grouping, amount corrections and confirmation, plus a pending total that is derived

A week is Monday to Sunday in America/Los_Angeles and half-open, so a delivery on a
boundary instant lands in one week rather than two. Overdue is computed, never a
fifth status. Confirmation is set on the payment and cascades: one transfer, one
acknowledgement.

The roster's pending total now comes from the ledger; expert.total_payments_pending
stays dead beside current_active_count.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
'@
```

---

### Task 6: The endpoints

**Files:**
- Create: `backend/src/main/java/com/ie/evalos/web/PayoutController.java`
- Create: `backend/src/main/java/com/ie/evalos/web/PaymentController.java`
- Test: `backend/src/test/java/com/ie/evalos/web/PayoutControllerTest.java`

**Interfaces:**
- Consumes: every `PayoutService` method and record from Tasks 3–5; `ApiResponse.ok(T)`.
- Produces: the nine routes in the table below. Response bodies are always `ApiResponse<T>`.

| Method | Path | Roles |
|---|---|---|
| GET | `/api/payouts` | GM · BM · ENM |
| GET | `/api/payouts/{id}` | GM · BM · ENM |
| GET | `/api/payouts/batch?weekOf=2026-08-24` | GM · BM · ENM |
| PATCH | `/api/payouts/{id}` | GM · BM · ENM |
| POST | `/api/payouts/settle` | GM · BM · ENM |
| GET | `/api/payments?expertId=` | GM · BM · ENM |
| GET | `/api/payments/{id}` | GM · BM · ENM |
| PATCH | `/api/payments/{id}` | GM · BM · ENM |
| POST | `/api/payments/{id}/confirm` | GM · BM · ENM |

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/ie/evalos/web/PayoutControllerTest.java`, modelled on `ExpertControllerTest` — copy its `@WebMvcTest` / `@Import` / `@TestPropertySource` header and its token-minting helper verbatim, then:

```java
	@Test
	void theEnmMayRecordThatMoneyWentOut() throws Exception {
		mockMvc.perform(post("/api/payouts/settle")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER))
				.contentType(MediaType.APPLICATION_JSON)
				.content(SETTLE_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));
	}

	@ParameterizedTest
	@MethodSource("rolesWithNoBusinessHere")
	void everyOtherRoleIsRefusedEverywhere(Role role) throws Exception {
		for (MockHttpServletRequestBuilder request : everyRoute(role)) {
			mockMvc.perform(request).andExpect(status().isForbidden());
		}
	}

	static List<Role> rolesWithNoBusinessHere() {
		return List.of(Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR, Role.CASE_MANAGER);
	}

	@Test
	void settlingRequiresAMethodAReferenceAnAmountAndADate() throws Exception {
		mockMvc.perform(post("/api/payouts/settle")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"expertId":"cccccccc-0000-0000-0000-000000000001",
						 "payoutIds":["dddddddd-0000-0000-0000-000000000001"]}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false));
	}

	@Test
	void noRouteEverSerializesThePaymentDetail() throws Exception {
		// The unit's invariant-4 criterion, written as a test: walk every route with a
		// service returning fully-populated data and grep each body. A DTO that grows a
		// member, or a mapper that starts copying one, fails here — which a per-field
		// assertion on one endpoint would not.
		for (MockHttpServletRequestBuilder request : everyRoute(Role.EXPERT_NETWORK_MANAGER)) {
			String body = mockMvc.perform(request).andReturn().getResponse().getContentAsString();
			assertThat(body).doesNotContain("paymentDetail").doesNotContain(SECRET);
		}
	}

	@Test
	void aRefusedSettlementCarriesTheServersReason() throws Exception {
		given(payoutService.settle(any())).willThrow(new InvalidRequestException(
				"The payment is 800.00 but the drafts it settles come to 700.00"));

		mockMvc.perform(post("/api/payouts/settle")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER))
				.contentType(MediaType.APPLICATION_JSON)
				.content(SETTLE_BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.message").value(
						"The payment is 800.00 but the drafts it settles come to 700.00"));
	}
```

Declare `SETTLE_BODY` as a valid settle payload, `SECRET` as an expert payment detail string, and `everyRoute(Role)` as a helper returning a `List<MockHttpServletRequestBuilder>` covering all nine routes with that role's bearer token.

- [ ] **Step 2: Run to verify it fails**

```powershell
cd backend; if ($?) { .\mvnw.cmd test "-Dtest=PayoutControllerTest" }
```

Expected: compilation failure — neither controller exists.

- [ ] **Step 3: Write the controllers**

Create `backend/src/main/java/com/ie/evalos/web/PayoutController.java`:

```java
package com.ie.evalos.web;

// imports as required

/**
 * The payout ledger: what each delivered draft owes an expert, and the week somebody
 * works down on payout day.
 *
 * <p><strong>No DTO here declares {@code paymentDetail}</strong> — not blanked, not
 * masked, <em>not a member</em> (invariant 4). An ENM who needs an expert's bank
 * details looks nowhere, because there is no read path anywhere in EvalOS.
 *
 * <p>Thin, like every controller: validate, authorize, call {@link PayoutService},
 * return a DTO in {@link ApiResponse}. Every rule lives in the service, which re-checks
 * the role itself — {@code @PreAuthorize} guards a route, the service guards the
 * operation.
 */
@RestController
@RequestMapping("/api/payouts")
public class PayoutController {

	/**
	 * Who works the ledger. Reads and writes are the same three roles, so this is one
	 * constant rather than two: the ENM sends the transfer and records it, and the GM and
	 * Brand Manager keep the oversight they have everywhere else. A PM, a Coordinator and
	 * a Case Manager are absent — none of them pays anybody.
	 */
	private static final String PAYOUTS = "hasAnyRole('GM', 'BRAND_MANAGER', 'EXPERT_NETWORK_MANAGER')";

	private final PayoutService payouts;

	PayoutController(PayoutService payouts) {
		this.payouts = payouts;
	}

	@GetMapping("/batch")
	@PreAuthorize(PAYOUTS)
	public ApiResponse<PayoutService.BatchView> batch(
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate weekOf) {
		return ApiResponse.ok(payouts.batch(weekOf));
	}

	@PostMapping("/settle")
	@PreAuthorize(PAYOUTS)
	public ApiResponse<Map<String, UUID>> settle(@Valid @RequestBody PayoutService.SettleForm form) {
		return ApiResponse.ok(Map.of("paymentId", payouts.settle(form)));
	}

	// GET "", GET "/{id}", PATCH "/{id}" follow the same shape.
}
```

Create `PaymentController.java` for `/api/payments` with the same `PAYOUTS` constant (declare it there too rather than sharing a constant across controllers — each controller states its own gate, as `ExpertController` does), covering `GET ""`, `GET "/{id}"`, `PATCH "/{id}"` and `POST "/{id}/confirm"`.

- [ ] **Step 4: Run to verify it passes**

```powershell
cd backend; if ($?) { .\mvnw.cmd test "-Dtest=PayoutControllerTest" }
```

Expected: all PASS.

- [ ] **Step 5: Full suite**

```powershell
cd backend; if ($?) { .\mvnw.cmd verify }
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/ie/evalos/web/ backend/src/test/java/com/ie/evalos/web/PayoutControllerTest.java
git commit -m @'
Put the ledger and its payments behind nine routes, gated on the three roles that pay people

Reads and writes are the same three roles now that the ENM records payouts, so the
gate is one constant. No DTO here declares paymentDetail, and a test walks every
route to keep it that way.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
'@
```

---

### Task 7: The two concurrency proofs, against real Postgres

**Files:**
- Modify: `backend/src/test/java/com/ie/evalos/repository/LocalPostgresIntegrationTest.java`

**Interfaces:**
- Consumes: `V28`'s `uq_payout_per_case`; `PayoutLedgerRepository.attachToPayment`.
- Produces: nothing consumed elsewhere.

These are the two properties no mock can prove, and both are acceptance criteria.

- [ ] **Step 1: Write the failing tests**

Add to `LocalPostgresIntegrationTest`, following the file's existing DB-gate pattern exactly:

```java
	@Test
	void twoConcurrentDeliveriesOfOneCaseOpenOnePayoutRow() {
		// The deliveryDate guard is a check-then-act and Case has no @Version, so both
		// callers can read null. uq_payout_per_case is what actually decides.
		// Insert two payout rows for one case_id from two transactions; the second must
		// fail on the unique index, and the first must survive.
	}

	@Test
	void twoSettlementsOverlappingByOneDraftLeaveOnePaymentAndNoOrphanedRows() {
		// Open three PENDING rows. Run attachToPayment twice with overlapping id sets.
		// Assert: the two calls' affected counts sum to at most 3, the overlapping row
		// carries exactly one payment_id, and no row is PAID with a payment_id that was
		// rolled back.
	}
```

Write the bodies against the harness this file already uses — read the existing tests in it for how a transaction is opened and how the gate is expressed. Do not add a new test framework or a Docker dependency; this suite runs against the local Postgres and self-skips when there is none.

- [ ] **Step 2: Run the DB suite**

```powershell
cd backend; if ($?) { .\mvnw.cmd test "-Devalos.db.test=true" "-Dtest=LocalPostgresIntegrationTest" }
```

Quote each `-D…` — unquoted, PowerShell splits it and Maven reports `Unknown lifecycle phase ".db.test=true"`.

Expected: FAIL first (the tests are empty/failing), then PASS once written. If it prints `[db] ... skipped`, the probe could not connect — start Postgres. **A skipped run is not a pass**, and these two tests are the whole reason the indexes exist.

- [ ] **Step 3: Commit**

```powershell
git add backend/src/test/java/com/ie/evalos/repository/LocalPostgresIntegrationTest.java
git commit -m @'
Prove the two races in real SQL: one payout per delivery, one payment per draft

Neither property can be shown with mocks. Both are the reason the unique index and
the conditional UPDATE exist, so both are asserted against Postgres rather than
argued for in a comment.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
'@
```

---

### Task 8: The frontend rules module

**Files:**
- Create: `frontend/src/features/payouts/payoutRules.ts`
- Create: `frontend/src/features/payouts/payoutRules.test.ts`

**Interfaces:**
- Consumes: nothing — this module imports no React and no axios, which is what makes it the part vitest covers.
- Produces: types `LedgerRow`, `ExpertGroup`, `BatchView`, `PaymentRow`, `PaymentDetailView`, `SettleRequest`; functions `sumSelected(drafts, selectedIds)`, `weekLabel(weekStart, weekEnd)`, `settleBlocker(drafts, selectedIds)`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/features/payouts/payoutRules.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { settleBlocker, sumSelected, weekLabel } from './payoutRules'
import type { LedgerRow } from './payoutRules'

const draft = (id: string, amount: number | null): LedgerRow => ({
  id,
  caseId: 'case-' + id,
  caseCode: 'IE-00' + id,
  expertId: 'expert-1',
  expertName: 'Dr. Smith',
  amount,
  currency: 'USD',
  status: 'PENDING',
  dueDate: '2026-09-02T00:00:00Z',
  overdue: false,
  paymentId: null,
})

describe('sumSelected', () => {
  it('adds only what is ticked', () => {
    const drafts = [draft('1', 350), draft('2', 350), draft('3', 400)]
    expect(sumSelected(drafts, new Set(['1', '3']))).toBe(750)
  })

  it('is zero when nothing is ticked', () => {
    expect(sumSelected([draft('1', 350)], new Set())).toBe(0)
  })

  it('adds in cents so the total is not a floating-point near-miss', () => {
    // 0.1 + 0.2 is 0.30000000000000004 in float. The server compares the posted
    // amount against its own sum exactly, so a near-miss here is a refused settlement
    // the user cannot fix from the screen.
    const drafts = [draft('1', 0.1), draft('2', 0.2)]
    expect(sumSelected(drafts, new Set(['1', '2']))).toBe(0.3)
  })

  it('treats an undecided amount as zero rather than NaN', () => {
    expect(sumSelected([draft('1', null), draft('2', 350)], new Set(['1', '2']))).toBe(350)
  })
})

describe('settleBlocker', () => {
  it('passes a clean selection', () => {
    expect(settleBlocker([draft('1', 350)], new Set(['1']))).toBeNull()
  })

  it('blocks an empty selection', () => {
    expect(settleBlocker([draft('1', 350)], new Set())).toMatch(/at least one/i)
  })

  it('blocks a draft with no amount, naming it', () => {
    expect(settleBlocker([draft('1', null)], new Set(['1']))).toMatch(/IE-001/)
  })
})

describe('weekLabel', () => {
  it('reads as a range a person would say out loud', () => {
    expect(weekLabel('2026-08-24', '2026-08-30')).toBe('Aug 24 – Aug 30, 2026')
  })
})
```

- [ ] **Step 2: Run to verify it fails**

```powershell
cd frontend; if ($?) { npm run test }
```

Expected: FAIL — `./payoutRules` cannot be resolved.

- [ ] **Step 3: Write the rules module**

Create `frontend/src/features/payouts/payoutRules.ts`:

```ts
/**
 * The payout feature's types and its pure decisions.
 *
 * No React and no axios in here on purpose: this is the part `npm run test` covers,
 * and the money arithmetic is exactly the part worth covering.
 */

export type PayoutStatus = 'PENDING' | 'PAID' | 'CONFIRMED' | 'VOIDED'

export type LedgerRow = {
  id: string
  caseId: string
  caseCode: string
  expertId: string
  expertName: string
  /** Null means nobody has decided yet. It is not zero, and it cannot be settled. */
  amount: number | null
  currency: string
  status: PayoutStatus
  dueDate: string
  /** Derived server-side as PENDING past its due date. Never a status value. */
  overdue: boolean
  paymentId: string | null
}

export type ExpertGroup = {
  expertId: string
  expertName: string
  drafts: LedgerRow[]
  subtotal: number
  currency: string
}

export type BatchView = {
  weekStart: string
  weekEnd: string
  groups: ExpertGroup[]
  due: number
  paid: number
  overdue: number
}

export type PaymentRow = {
  id: string
  expertId: string
  expertName: string
  amount: number
  currency: string
  method: string
  reference: string
  paidDate: string
  draftCount: number
  confirmed: boolean
}

export type PaymentDetailView = {
  payment: PaymentRow
  notes: string | null
  recordedByName: string
  drafts: LedgerRow[]
}

export type SettleRequest = {
  expertId: string
  payoutIds: string[]
  amount: number
  method: string
  reference: string
  paidDate: string
  notes: string
}

/**
 * What the ticked drafts come to.
 *
 * Summed in cents and divided back, not added as floats. The server compares the
 * posted amount against its own sum and refuses anything that is not exact, so a
 * 0.30000000000000004 here is a settlement the user cannot fix from the screen.
 */
export function sumSelected(drafts: LedgerRow[], selectedIds: Set<string>): number {
  const cents = drafts
    .filter((d) => selectedIds.has(d.id))
    .reduce((total, d) => total + Math.round((d.amount ?? 0) * 100), 0)
  return cents / 100
}

/** Why this selection cannot be settled yet, or null when it can. */
export function settleBlocker(drafts: LedgerRow[], selectedIds: Set<string>): string | null {
  const selected = drafts.filter((d) => selectedIds.has(d.id))
  if (selected.length === 0) return 'Tick at least one draft to record a payment.'

  const undecided = selected.find((d) => d.amount === null)
  if (undecided) return `${undecided.caseCode} has no amount yet. Set one before settling it.`

  const notPending = selected.find((d) => d.status !== 'PENDING')
  if (notPending) return `${notPending.caseCode} is already ${notPending.status.toLowerCase()}.`

  return null
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** "Aug 24 – Aug 30, 2026". Both dates are plain ISO days, so no timezone is involved. */
export function weekLabel(weekStart: string, weekEnd: string): string {
  const [, sm, sd] = weekStart.split('-')
  const [ey, em, ed] = weekEnd.split('-')
  return `${MONTHS[Number(sm) - 1]} ${Number(sd)} – ${MONTHS[Number(em) - 1]} ${Number(ed)}, ${ey}`
}
```

- [ ] **Step 4: Run to verify it passes**

```powershell
cd frontend; if ($?) { npm run test }
```

Expected: all PASS.

- [ ] **Step 5: Typecheck and commit**

```powershell
cd frontend; if ($?) { npm run build }
```

```powershell
git add frontend/src/features/payouts/
git commit -m @'
Payout rules, with the money summed in cents

The server refuses a payment whose amount is not exactly the sum of what it settles,
so a floating-point near-miss on the screen is a settlement nobody can complete.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
'@
```

---

### Task 9: The API client and the batch screen

**Files:**
- Create: `frontend/src/features/payouts/payoutApi.ts`
- Create: `frontend/src/features/payouts/PayoutBatch.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/features/shell/navigation.ts`

**Interfaces:**
- Consumes: `api`, `unwrap` from `../../lib/api`; `formatMoney` from `../../lib/money`; every type from `payoutRules`.
- Produces: `fetchBatch(weekOf, brandId, signal)`, `settle(request)`, `correctAmount(payoutId, amount)`, `fetchPayments(expertId, brandId, signal)`, `fetchPayment(id, signal)`, `editPayment(id, form)`, `confirmPayment(id)`. Default-exported `PayoutBatch`.

- [ ] **Step 1: Write the API client**

Create `frontend/src/features/payouts/payoutApi.ts`, following `expertApi.ts` exactly for shape, `unwrap` usage, `brandId` handling and doc-comment style. Every read takes an optional `AbortSignal`.

- [ ] **Step 2: Write the batch screen**

Create `frontend/src/features/payouts/PayoutBatch.tsx`. Requirements, all load-bearing:

- Async state is a **discriminated union**, not booleans — follow `pages/Dashboard.tsx`.
- The fetch effect uses an `AbortController` and bails on `signal.aborted` in `catch`; `StrictMode` double-invokes effects in dev.
- A week picker (native `<input type="date">`, no picker library) defaulting to the current week.
- Header totals: due, paid, remaining, overdue — `formatMoney` from `lib/money`, tabular figures on every money column.
- Groups by expert, each with a subtotal and a **Record payment** button that opens `PaymentForm` for that expert's drafts.
- Overdue rows get the RAG treatment from `context/ui-context.md`. Read that file for the token names; do not invent colours and do not use hex literals.
- **Actions are gated on role, in the same component** — no second read-only screen. Read the role from the session and hide the write controls for anyone outside `GM`/`BRAND_MANAGER`/`EXPERT_NETWORK_MANAGER`. This mirrors the server's gate; a button the server refuses is worse than no button.

- [ ] **Step 3: Wire the route**

In `frontend/src/App.tsx`, add to `SCREENS`:

```tsx
  '/payouts': <PayoutBatch />,
```

In `frontend/src/features/shell/navigation.ts`, update the existing `/payouts` entry's `becomes` — the `roles` array is **already** `['GM', 'BRAND_MANAGER', 'EXPERT_NETWORK_MANAGER']` and needs no change:

```ts
    becomes: 'Weekly payout batch + payment history',
```

Then add `/payouts/experts/:expertId` and `/payouts/payments/:paymentId` to the reachable-but-unlisted table beside `/cases/:id`, with the same three roles — they take a parameter, so they get no nav entry.

- [ ] **Step 4: Build**

```powershell
cd frontend; if ($?) { npm run build }
```

Expected: clean `tsc -b` and a successful Vite build.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/features/payouts/ frontend/src/App.tsx frontend/src/features/shell/navigation.ts
git commit -m @'
Give the payouts nav entry a screen: the week, grouped by expert

One component with its actions gated on role rather than a second read-only screen.
The nav roles were already right; only the placeholder text changed.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
'@
```

---

### Task 10: The record-payment dialog

**Files:**
- Create: `frontend/src/features/payouts/PaymentForm.tsx`
- Modify: `frontend/src/features/payouts/PayoutBatch.tsx` (open it from the group action)

**Interfaces:**
- Consumes: `sumSelected`, `settleBlocker`, `SettleRequest`, `LedgerRow` from `payoutRules`; `settle` from `payoutApi`.
- Produces: default-exported `PaymentForm` taking `{ expertId, expertName, drafts, onRecorded, onCancel }`.

- [ ] **Step 1: Write the dialog**

Create `frontend/src/features/payouts/PaymentForm.tsx`. The behaviour that matters:

- Lists the expert's pending drafts with **checkboxes, all ticked by default**.
- The amount field shows `sumSelected(drafts, selected)` and is **read-only**. Rule 7 on the server makes any other number a refusal, and a field the server will always reject is worse than no field. Unticking a draft changes the total in front of the user — that is the whole point of settling by selection.
- Method is a native `<input list="payout-methods">` datalist, suggesting values already used, not enumerating a closed set. EvalOS has no rail and must not pretend to.
- Reference and paid date are required; notes are optional and free text.
- The submit button is disabled while `settleBlocker(...)` returns non-null, and the blocker's message is shown inline.
- **A server refusal is shown, not swallowed.** `lib/api`'s interceptor already lifts the server's reason onto `error.message`, so render that string directly — it is where "the payment is 800.00 but the drafts come to 700.00" reaches the user.
- No `window.confirm`, no `alert`.

- [ ] **Step 2: Build**

```powershell
cd frontend; if ($?) { npm run build; if ($?) { npm run lint } }
```

Expected: both clean.

- [ ] **Step 3: Drive it in a browser**

Start Postgres, then:

```powershell
cd backend; if ($?) { .\mvnw.cmd spring-boot:run }
```

and in a second terminal `cd frontend; npm run dev`. Sign in as `gm@evalos.local` / `DevPassw0rd!`.

To get a real delivered case, fire Handoff A and drive the case to delivery — the payload and its traps are in `.serena/memories/suggested_commands.md` (`event_type` and `event_id` are the gateway's fields and are easy to miss; use a fresh email, GHL id and opportunity id each time). Then settle its draft on `/payouts` and confirm the row flips and the payment appears.

- [ ] **Step 4: Commit**

```powershell
git add frontend/src/features/payouts/
git commit -m @'
Record one payment against several drafts, with the amount as the live sum

The amount field is read-only because the server refuses anything that is not the
exact sum, and a field the server will always reject is worse than no field.
Method is a datalist: suggested, not enumerated, because there is no rail.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
'@
```

---

### Task 11: The expert page, the payment detail, and the docs

**Files:**
- Create: `frontend/src/features/payouts/ExpertPayouts.tsx`
- Create: `frontend/src/features/payouts/PaymentDetail.tsx`
- Modify: `context/progress-tracker.md`
- Modify: `.serena/memories/backend/persistence.md`
- Modify: `.serena/memories/backend/core.md`

**Interfaces:**
- Consumes: `fetchPayments`, `fetchPayment`, `confirmPayment`, `editPayment` from `payoutApi`.
- Produces: default-exported `ExpertPayouts` (route `/payouts/experts/:expertId`) and `PaymentDetail` (route `/payouts/payments/:paymentId`).

- [ ] **Step 1: Write the two screens**

`ExpertPayouts.tsx` — one expert: their pending drafts (with the inline amount correction), then their payment history. Reached from the batch screen and from the roster.

`PaymentDetail.tsx` — amount, week, method, reference, paid date, who recorded it, notes, confirmed state, and **every draft it settled**, each linking to `/cases/:id`. The confirm action is here, gated on role, and disabled once confirmed.

Both follow the same discriminated-union + `AbortController` pattern as `PayoutBatch`.

- [ ] **Step 2: Build and test**

```powershell
cd frontend; if ($?) { npm run build; if ($?) { npm run test; if ($?) { npm run lint } } }
```

Expected: all clean.

- [ ] **Step 3: Run the whole backend suite one more time**

```powershell
cd backend; if ($?) { .\mvnw.cmd verify }
```

Expected: BUILD SUCCESS, with the DB suite **running** rather than skipping.

- [ ] **Step 4: Update the tracker**

The `2026-08-27 — Unit 16b specced` entry in `context/progress-tracker.md` currently says "No code yet". Edit that entry — do not append a second one beside it — to record what was built, what the DB-gated tests proved, and the final test counts.

- [ ] **Step 5: Update the Serena memories**

- `.serena/memories/backend/persistence.md`: the conditional-`UPDATE` note added when the spec was written says "specced in 16b ... not yet built". **Edit that sentence** to say it is built and name `PayoutLedgerRepository.attachToPayment` — a memory that disagrees with the code is worse than no memory. Add `PayoutPayment` to the entity list at line 4, and add `uq_payout_per_case` to the worked-examples list beside `V15`/`V16`.
- `.serena/memories/backend/core.md`: add `PayoutService` to the service list with a one-line description, and link it from wherever the money paths are listed.

Both edits replace what is now stale rather than sitting beside it.

- [ ] **Step 6: Commit**

```powershell
git add frontend/src/features/payouts/ context/progress-tracker.md .serena/memories/
git commit -m @'
The expert page and the payment detail, and the docs catch up with the code

Every payout screen now exists: the week, the dialog, one expert, one payment. The
tracker entry that said "no code yet" says what was built, and the persistence
memory's "specced, not built" note names the method that implements it.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
'@
```

---

## Self-Review

**Spec coverage.** Walked `16b-weekly-settlement.md` section by section:

| Spec section | Task |
|---|---|
| `payout_payment` table, `payment_id`, dropped columns, brand settings, `uq_payout_per_case`, `ScopedEntity` | 1 |
| `PAYOUT_SETTLED`, derived `total_payments_pending` | 2, 5 |
| Row opened on delivery, prefill, null amount, no-expert report | 3 |
| The seven settlement rules, the conditional `UPDATE`, the race | 4 |
| Status progression, correctable vs frozen, confirm cascade, "overdue" derived, weeks | 5 |
| Nine endpoints, ENM widening, service-level guard | 6 |
| Both concurrency acceptance criteria | 7 |
| Four screens, role-gated single component, nav | 8, 9, 10, 11 |
| Docs + memory | 11 |

Two spec lines have **no task, deliberately**, and both are out of scope in 16b itself: the expert-portal payout status line (waits on Unit 15, which is blocked on the Google service account) and the ENM dashboard tiles (Unit 17's).

**Placeholder scan.** Four steps intentionally describe shape rather than paste code — Task 3 step 5's notification call, Task 5 step 3's three read projections, Task 7's two DB test bodies, and Tasks 9–11's screens. Each names the **exact existing file to copy the pattern from** (`CaseLifecycleService`'s neighbouring transitions, `CaseBoardService`, `LocalPostgresIntegrationTest`'s own gate, `pages/Dashboard.tsx`, `expertApi.ts`), because inventing a call shape against code I have not read line-by-line would be worse than pointing at the authority. No step says "add appropriate error handling" or "write tests for the above".

**Type consistency.** `SettleForm` is the server record and `SettleRequest` the TS type — deliberately different names by layer, so a grep for either lands on one side of the wire. `PaymentEditForm` is the one name shared both ways, because it is the same three fields. `attachToPayment(paymentId, ids, brandId, actor)` returns `int` in Task 2 and is asserted against `rows.size()` in Task 4. `weekStart(Instant) → LocalDate` in Task 5 is used by `batch` in the same task. `LedgerRow`, `ExpertGroup`, `BatchView`, `PaymentRow`, `PaymentDetailView` have the same member names on both sides of the wire.

**One thing found while planning and worth flagging:** the `/payouts` nav entry **already** carries all three roles, so the spec's "gains GM and Brand Manager" was already true. Only `becomes` changes. Task 9 says so rather than making a no-op edit.
