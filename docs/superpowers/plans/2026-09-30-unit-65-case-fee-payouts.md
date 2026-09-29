# Unit 65 — Case fee on the offer + Payouts module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every expert offer carries the fee the case pays, the expert sees and accepts it, delivery pays exactly that, and the GM / BM / ENM work payouts in a module of four screens with a per-offer log.

**Architecture:** One nullable column trio on `expert_case_offer` (`fee`, `fee_set_by`, `fee_set_at`); pricing is a pure static rule (`OfferFees.price`) called by the three offer-creating transitions in `CaseLifecycleService`; editing and reading the current offer is a small `OfferFeeService`; the register / totals / overview are a read-only `PayoutRegisterService` that joins the brand's offers and non-voided payouts in memory, following `PayoutService`'s existing `findScoped(ctx)`-then-stream pattern. History is read from the existing append-only `audit_event` (`OfferLog`). No new table, no change to `payout_ledger` / `payout_payment` / `PayoutStatus`.

**Tech Stack:** Java 21, Spring Boot, JPA, Flyway, Postgres 16, JUnit 5 + Mockito + AssertJ (backend); React 19 + Vite + TS, vitest, oxlint (staff SPA `frontend/`, portals `client-expert/`).

**Spec:** `context/specs/65-case-fee-and-payouts-module.md` — read it first; this plan argues from it.

## Global Constraints

- Every scoped read goes through `findScoped(ctx…)` / `ScopedRepository`; a query without brand scoping is a bug (CLAUDE.md).
- `audit_event` is append-only; every fee set / edit / offer outcome / missing-amount set writes one row with actor, time, before → after.
- Payout roles are exactly `PayoutService.MAY_RECORD` = GM, BRAND_MANAGER, EXPERT_NETWORK_MANAGER. Fee setters are GM, PROJECT_MANAGER, PROJECT_COORDINATOR, EXPERT_NETWORK_MANAGER. A CASE_MANAGER may only offer at the base fee.
- Fee: `numeric(12,2)`, `>= 0`; request validation `@DecimalMin("0") @Digits(integer = 10, fraction = 2)`.
- Status words shown to people: Offered, Accepted, Declined, Timed out, Superseded, Pending, Processing, Paid. Stored `PAID` shows as **Processing**, `CONFIRMED` as **Paid** (unchanged from Unit 63).
- `CONFIRM_NUDGE_DAYS = 7`.
- Refusal messages (verbatim): *"Set a fee for this case — this expert has no standard fee."* · *"Ask a PM, PC or ENM to set the fee for this case."* · *"A case manager offers at the expert's standard fee."* · *"The fee for this case changed — review it."* · *"The fee for this case is not set yet."*
- No `payment_detail` in any new DTO (D28).
- No new npm or Maven dependency. Charts, if any, inline SVG.
- Migrations: main tree `V79`, seed trees `V914` (local) and `V953` (testprod). Never a hand-run script.
- Commit trailer on every commit: `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`

## Review Focus

1. **Double-click Accept after the fee was accepted** — the second request finds no open offer; it must return the accepted case (idempotent), not 409. Test in Task 5.
2. **A PC editing the fee on a case not assigned to them** — the scoped load must answer 404, not edit. Test in Task 3.
3. **A GM (cross-brand) opening the Overview** — two brands with different currencies must yield one overview per currency, never a sum across currencies. Test in Task 6.
4. **A payout with no matching accepted offer** (pre-V79 data, staff-signed case) — must still appear in the register with the payout's amount. Test in Task 6.
5. **Register CSV with an expert named `=HYPERLINK(...)`** — goes through `PayoutService.csvField`, so it is neutralised. Test in Task 6.

---

## File Structure

**Backend — create**
- `backend/src/main/resources/db/migration/V79__offer_fee.sql` — columns + backfill
- `backend/src/main/resources/db/seed-local/V914__seed_local_offer_fee.sql`, `backend/src/main/resources/db/seed-testprod/V953__seed_testprod_offer_fee.sql` — same backfill for seeded offers
- `backend/src/main/java/com/ie/evalos/service/OfferFees.java` — the pure pricing rule
- `backend/src/main/java/com/ie/evalos/service/OfferLog.java` — audit rows → log entries for one offer
- `backend/src/main/java/com/ie/evalos/service/OfferFeeService.java` — current offer read + edit fee
- `backend/src/main/java/com/ie/evalos/web/OfferFeeController.java` — `/api/cases/{id}/expert/offer`
- `backend/src/main/java/com/ie/evalos/service/PayoutRegisterService.java` — register, totals, overview, history, CSV
- `backend/src/main/java/com/ie/evalos/web/PayoutRegisterController.java` — `/api/payouts/cases|experts|overview`
- Tests: `OfferFeesTest`, `OfferFeeServiceTest`, `OfferLogTest`, `PayoutRegisterServiceTest`, `OfferFeeControllerTest`, `PayoutRegisterControllerTest`

**Backend — modify**
- `domain/ExpertCaseOffer.java` — fee fields + `setFee`
- `service/CaseLifecycleService.java` — price the three offers, audit every offer create / resolve
- `web/CaseController.java` — `fee` on `AssignCmRequest`, `ExpertRequest`, new `RetakeRequest`
- `service/PayoutService.java` — accepted fee at delivery; `correctAmount` → `setMissingAmount`
- `web/PayoutController.java` — PATCH calls `setMissingAmount`
- `service/ExpertPortalService.java`, `web/ExpertPortalController.java` — fee guard on accept; fee on summary + view
- Tests: `CaseLifecycleServiceTest`, `CaseControllerTest`, `PayoutServiceTest`, `PayoutControllerTest`, `ExpertPortalServiceTest`, `LocalPostgresIntegrationTest`

**Staff SPA — create**
- `frontend/src/features/payouts/registerRules.ts` (+ `registerRules.test.ts`) — register types, labels, `describeChange`, `registerParams`
- `frontend/src/features/payouts/registerApi.ts` — the new reads
- `frontend/src/features/payouts/PayoutsOverview.tsx`, `PayoutRegister.tsx`, `ExpertBalances.tsx`, `OfferLogPanel.tsx`

**Staff SPA — modify**
- `features/shell/navigation.ts` (+ test), `App.tsx`, `features/payouts/payoutApi.ts`, `features/payouts/ExpertPayouts.tsx`, `features/payouts/PayoutBatch.tsx` (drops the summary, which moves to Overview), `features/board/boardRules.ts`, `features/case/ExpertCard.tsx`

**Expert portal — modify**
- `client-expert/expert/src/lib/expertCase.ts` (+ test), `services/expertPortalService.ts`, `components/Answers.tsx`, `pages/NewCases.tsx`, `pages/portal/ExpertCasePortal.tsx`

---

### Task 1: Schema and the offer entity

**Files:**
- Create: `backend/src/main/resources/db/migration/V79__offer_fee.sql`
- Create: `backend/src/main/resources/db/seed-local/V914__seed_local_offer_fee.sql`
- Create: `backend/src/main/resources/db/seed-testprod/V953__seed_testprod_offer_fee.sql`
- Modify: `backend/src/main/java/com/ie/evalos/domain/ExpertCaseOffer.java`
- Test: `backend/src/test/java/com/ie/evalos/domain/ExpertCaseOfferTest.java` (create), `backend/src/test/java/com/ie/evalos/repository/LocalPostgresIntegrationTest.java`

**Interfaces:**
- Produces: `ExpertCaseOffer.getFee(): BigDecimal`, `getFeeSetBy(): UUID`, `getFeeSetAt(): Instant`, `setFee(BigDecimal fee, UUID actor): void` (throws `IllegalTransitionException` unless `OFFERED`).

- [ ] **Step 1: Write the failing entity test**

```java
package com.ie.evalos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ExpertCaseOfferTest {

	private final UUID actor = UUID.randomUUID();

	@Test
	void theFeeCanBeSetWhileTheOfferIsOpen() {
		ExpertCaseOffer offer = new ExpertCaseOffer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		offer.setFee(new BigDecimal("350.00"), actor);
		assertThat(offer.getFee()).isEqualByComparingTo("350.00");
		assertThat(offer.getFeeSetBy()).isEqualTo(actor);
		assertThat(offer.getFeeSetAt()).isNotNull();
	}

	@Test
	void theFeeIsFrozenOnceTheOfferIsAnswered() {
		for (OfferOutcome outcome : new OfferOutcome[] { OfferOutcome.ACCEPTED, OfferOutcome.DECLINED,
				OfferOutcome.TIMED_OUT, OfferOutcome.SUPERSEDED }) {
			ExpertCaseOffer offer = new ExpertCaseOffer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
			offer.resolve(outcome, null);
			assertThatThrownBy(() -> offer.setFee(BigDecimal.TEN, actor))
					.isInstanceOf(IllegalTransitionException.class);
		}
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=ExpertCaseOfferTest`
Expected: compilation FAIL — `setFee` / `getFee` not defined.

- [ ] **Step 3: Add the fields to `ExpertCaseOffer`**

After `declineReason`, add:

```java
	/**
	 * What this case pays the expert, in the brand's currency (Unit 65). Nullable only for offers
	 * closed before V79 and for an open one whose expert had no standard fee at migration time;
	 * every offer made since is priced by {@code OfferFees.price}.
	 */
	@Column(name = "fee")
	private BigDecimal fee;

	/** Who set the current {@link #fee}; null when the migration did. The history is in audit_event. */
	@Column(name = "fee_set_by")
	private UUID feeSetBy;

	@Column(name = "fee_set_at")
	private Instant feeSetAt;
```

And the methods (after `getDeclineReason`):

```java
	/**
	 * Sets the amount, only while nobody has answered (Unit 65 rule 3): an accepted fee is the
	 * agreed price, and a declined or superseded offer has nothing left to price.
	 */
	public void setFee(BigDecimal fee, UUID actor) {
		if (outcome != OfferOutcome.OFFERED) {
			throw new IllegalTransitionException("This offer is " + outcome + " and its fee is final");
		}
		this.fee = fee;
		this.feeSetBy = actor;
		this.feeSetAt = Instant.now();
	}

	public BigDecimal getFee() {
		return fee;
	}

	public UUID getFeeSetBy() {
		return feeSetBy;
	}

	public Instant getFeeSetAt() {
		return feeSetAt;
	}
```

Add `import java.math.BigDecimal;`. Update the class javadoc's "one mutable field in fact" to "two mutable facts: the outcome (once) and the fee (while open)".

- [ ] **Step 4: Write the migrations**

`V79__offer_fee.sql`:

```sql
-- Unit 65 (D59 edited 2026-09-30): the fee a case pays its expert lives on the offer.
ALTER TABLE expert_case_offer
    ADD COLUMN fee        numeric(12,2) CHECK (fee >= 0),
    ADD COLUMN fee_set_by uuid REFERENCES team_member (id),
    ADD COLUMN fee_set_at timestamptz;

-- Open and accepted offers get the fee they would have been paid at delivery, so none is left
-- unpriced and today's payouts appear in the register.
UPDATE expert_case_offer o
   SET fee = e.standard_fee, fee_set_at = now()
  FROM expert e
 WHERE o.outcome IN ('OFFERED', 'ACCEPTED') AND e.id = o.expert_id;

-- An accepted offer that already has a payout takes the payout's amount: that is what was agreed
-- in practice (the standard fee, possibly corrected before this unit).
UPDATE expert_case_offer o
   SET fee = p.amount, fee_set_at = now()
  FROM payout_ledger p
 WHERE o.outcome = 'ACCEPTED' AND p.case_id = o.case_id AND p.expert_id = o.expert_id
   AND p.status <> 'VOIDED' AND p.amount IS NOT NULL;
```

`V914__seed_local_offer_fee.sql` and `V953__seed_testprod_offer_fee.sql` (identical bodies):

```sql
-- On a fresh database the seed inserts its offers after V79 ran; price them the same way.
UPDATE expert_case_offer o
   SET fee = e.standard_fee, fee_set_at = now()
  FROM expert e
 WHERE o.fee IS NULL AND o.outcome IN ('OFFERED', 'ACCEPTED') AND e.id = o.expert_id;

UPDATE expert_case_offer o
   SET fee = p.amount, fee_set_at = now()
  FROM payout_ledger p
 WHERE o.outcome = 'ACCEPTED' AND p.case_id = o.case_id AND p.expert_id = o.expert_id
   AND p.status <> 'VOIDED' AND p.amount IS NOT NULL;
```

- [ ] **Step 5: Pin the migration in `LocalPostgresIntegrationTest`**

Open the file, find how an existing test inserts an offer / expert (search `expert_case_offer`), and add a test in the same style:

```java
	@Test
	void v79PricesOpenAndAcceptedOffersAndAnAcceptedOfferTakesItsPayoutsAmount() {
		// Given the seed's offers (V905), after V79 + V914 every OFFERED or ACCEPTED offer whose
		// expert has a standard fee is priced, and none is negative.
		Integer unpriced = jdbc.queryForObject("""
				SELECT count(*) FROM expert_case_offer o JOIN expert e ON e.id = o.expert_id
				 WHERE o.outcome IN ('OFFERED', 'ACCEPTED') AND e.standard_fee IS NOT NULL AND o.fee IS NULL
				""", Integer.class);
		assertThat(unpriced).isZero();
		Integer mismatched = jdbc.queryForObject("""
				SELECT count(*) FROM expert_case_offer o JOIN payout_ledger p
				  ON p.case_id = o.case_id AND p.expert_id = o.expert_id
				 WHERE o.outcome = 'ACCEPTED' AND p.status <> 'VOIDED' AND p.amount IS NOT NULL
				   AND o.fee <> p.amount
				""", Integer.class);
		assertThat(mismatched).isZero();
	}
```

(Use the test class's existing `JdbcTemplate` field name; if it is not `jdbc`, rename to match.)

- [ ] **Step 6: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest=ExpertCaseOfferTest` → PASS.
Run: `cd backend && ./mvnw -q test -Dtest=LocalPostgresIntegrationTest` → PASS when a local Postgres is up (it is skipped otherwise; say so in the report if skipped).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db backend/src/main/java/com/ie/evalos/domain/ExpertCaseOffer.java backend/src/test
git commit -m "feat(unit-65): the offer carries the case fee (V79)"
```

---

### Task 2: Price every offer, audit every offer

**Files:**
- Create: `backend/src/main/java/com/ie/evalos/service/OfferFees.java`
- Create: `backend/src/test/java/com/ie/evalos/service/OfferFeesTest.java`
- Modify: `backend/src/main/java/com/ie/evalos/service/CaseLifecycleService.java` (`assignCaseManager` ~L266, `reassignExpert` ~L980, `retakeExpert` ~L996, `rematch`, `resolveOpenOffer` ~L1055, `signed`, `declined`, `expertTimedOut`, `expertAcceptedFromPortal`)
- Modify: `backend/src/main/java/com/ie/evalos/web/CaseController.java` (records L171, L315; handlers L599-603, L743-755)
- Test: `CaseLifecycleServiceTest`, `CaseControllerTest`

**Interfaces:**
- Consumes: `ExpertCaseOffer.setFee(BigDecimal, UUID)` (Task 1).
- Produces:
  - `OfferFees.price(Role role, BigDecimal requested, BigDecimal base): BigDecimal`
  - `CaseLifecycleService.assignCaseManager(UUID caseId, UUID cmId, UUID expertId, String rationale, FieldTag field, BigDecimal fee)`
  - `CaseLifecycleService.reassignExpert(UUID caseId, UUID expertId, String rationale, FieldTag field, BigDecimal fee)`
  - `CaseLifecycleService.retakeExpert(UUID caseId, BigDecimal fee)`
  - audit rows: `("OFFER", offerId, CREATED, actor, null, {caseId, expertId, fee})` on create; `("OFFER", offerId, UPDATED, actor|portal, {outcome: OFFERED}, {outcome, fee})` on resolve.

- [ ] **Step 1: Write the failing rule test**

```java
package com.ie.evalos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.Role;

import org.junit.jupiter.api.Test;

class OfferFeesTest {

	private static final BigDecimal STANDARD = new BigDecimal("350.00");

	@Test
	void blankMeansTheBaseFee() {
		assertThat(OfferFees.price(Role.PROJECT_MANAGER, null, STANDARD)).isEqualByComparingTo("350.00");
	}

	@Test
	void aFeeSetterMayNameAnyAmount() {
		assertThat(OfferFees.price(Role.EXPERT_NETWORK_MANAGER, new BigDecimal("500"), STANDARD))
				.isEqualByComparingTo("500");
		assertThat(OfferFees.price(Role.PROJECT_MANAGER, new BigDecimal("500"), null)).isEqualByComparingTo("500");
	}

	@Test
	void anOfferCannotBeMadeWithoutAFee() {
		assertThatThrownBy(() -> OfferFees.price(Role.PROJECT_MANAGER, null, null))
				.isInstanceOf(IllegalTransitionException.class)
				.hasMessage("Set a fee for this case — this expert has no standard fee.");
	}

	@Test
	void aCaseManagerOffersAtTheStandardFeeOnly() {
		assertThat(OfferFees.price(Role.CASE_MANAGER, null, STANDARD)).isEqualByComparingTo("350.00");
		assertThat(OfferFees.price(Role.CASE_MANAGER, new BigDecimal("350"), STANDARD)).isEqualByComparingTo("350");
		assertThatThrownBy(() -> OfferFees.price(Role.CASE_MANAGER, new BigDecimal("400"), STANDARD))
				.isInstanceOf(ForbiddenException.class)
				.hasMessage("A case manager offers at the expert's standard fee.");
		assertThatThrownBy(() -> OfferFees.price(Role.CASE_MANAGER, null, null))
				.isInstanceOf(IllegalTransitionException.class)
				.hasMessage("Ask a PM, PC or ENM to set the fee for this case.");
	}
}
```

- [ ] **Step 2: Run it — expect compilation FAIL (`OfferFees` missing)**

Run: `cd backend && ./mvnw -q test -Dtest=OfferFeesTest`

- [ ] **Step 3: Write `OfferFees`**

```java
package com.ie.evalos.service;

import java.math.BigDecimal;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.Role;

/**
 * What a new offer pays (Unit 65 rules 1–2). Pure, so the three transitions that open an offer
 * share one rule and a test can pin it without a case.
 *
 * <p>{@code base} is the expert's standard fee, or — on a retake — the fee of the offer the expert
 * declined. A blank request means the base. A Case Manager may only offer at the base.
 */
final class OfferFees {

	private OfferFees() {
	}

	static BigDecimal price(Role role, BigDecimal requested, BigDecimal base) {
		if (role == Role.CASE_MANAGER) {
			if (requested != null && (base == null || requested.compareTo(base) != 0)) {
				throw new ForbiddenException("A case manager offers at the expert's standard fee.");
			}
			if (base == null) {
				throw new IllegalTransitionException("Ask a PM, PC or ENM to set the fee for this case.");
			}
			return base;
		}
		BigDecimal fee = requested != null ? requested : base;
		if (fee == null) {
			throw new IllegalTransitionException("Set a fee for this case — this expert has no standard fee.");
		}
		return fee;
	}
}
```

(Check `ForbiddenException` and `IllegalTransitionException` have a `(String)` constructor — both are used that way in `PayoutService`.)

- [ ] **Step 4: Run `OfferFeesTest` → PASS**

- [ ] **Step 5: Write the failing lifecycle tests** (in `CaseLifecycleServiceTest`)

First update the helper `walkToDraftGeneration` and **every** existing call so the suite compiles: `assignCaseManager(..., null)` gets a trailing `null` fee argument, `reassignExpert(..., null)` likewise, `retakeExpert(CASE_ID)` → `retakeExpert(CASE_ID, null)`. The `expert(...)` helper must stub a standard fee so the walk still prices:

```java
	private static Expert expert(UUID id, Availability availability) {
		Expert value = mock(Expert.class);
		given(value.getId()).willReturn(id);
		given(value.getAvailability()).willReturn(availability);
		given(value.getStandardFee()).willReturn(new BigDecimal("350.00"));
		return value;
	}
```

Then add:

```java
	@Test
	void theOfferIsPricedAtTheStandardFeeWhenLeftBlankAndAudited() {
		walkToDraftGeneration();

		ExpertCaseOffer offer = savedOffers().getLast();
		assertEquals(0, new BigDecimal("350.00").compareTo(offer.getFee()));
		verify(audit).recordEvent(eq("OFFER"), any(), eq(AuditAction.CREATED), any(), isNull(),
				argThat(after -> after instanceof java.util.Map<?, ?> m && m.get("fee") != null));
	}

	@Test
	void theOfferCarriesTheFeeThePmNamed() {
		actAs(Role.BRAND_MANAGER);
		lifecycle.assignPm(CASE_ID, PM_ID);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.markDocsComplete(CASE_ID);
		actAs(Role.PROJECT_MANAGER);
		lifecycle.assignCaseManager(CASE_ID, CM_ID, EXPERT_ID, null, null, new BigDecimal("420.00"));

		assertEquals(0, new BigDecimal("420.00").compareTo(savedOffers().getLast().getFee()));
	}

	@Test
	void aRefusedPriceChangesNothing() {
		given(experts.findScoped(any(TenantContext.class), eq(EXPERT_ID)))
				.willReturn(Optional.of(expertWithoutFee(EXPERT_ID)));
		actAs(Role.BRAND_MANAGER);
		lifecycle.assignPm(CASE_ID, PM_ID);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.markDocsComplete(CASE_ID);
		Stage before = subject.getCurrentStage();
		actAs(Role.PROJECT_MANAGER);

		assertThrows(IllegalTransitionException.class,
				() -> lifecycle.assignCaseManager(CASE_ID, CM_ID, EXPERT_ID, null, null, null));
		assertEquals(before, subject.getCurrentStage());
		verify(offers, never()).save(any(ExpertCaseOffer.class));
	}

	@Test
	void aRetakeKeepsTheDeclinedOffersFee() {
		// Same walk as theExpertWhoDeclinedCanBeOfferedTheCaseAgain, with a named fee.
		actAs(Role.BRAND_MANAGER);
		lifecycle.assignPm(CASE_ID, PM_ID);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.markDocsComplete(CASE_ID);
		actAs(Role.PROJECT_MANAGER);
		lifecycle.assignCaseManager(CASE_ID, CM_ID, EXPERT_ID, null, null, new BigDecimal("420.00"));
		ExpertCaseOffer first = savedOffers().getLast();
		given(offers.findByCaseIdAndOutcome(CASE_ID, OfferOutcome.OFFERED)).willReturn(List.of(first));
		given(offers.findByCaseIdOrderByOfferedAtDesc(CASE_ID)).willReturn(List.of(first));
		actAs(Role.CASE_MANAGER);
		lifecycle.submitDraft(CASE_ID, WORD, PDF);
		actAs(Role.PROJECT_MANAGER);
		lifecycle.pmApproveDraft(CASE_ID, null);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.sendDraftToClient(CASE_ID);
		lifecycle.clientApproveDraft(CASE_ID);
		actAs(Role.CASE_MANAGER);
		lifecycle.sendToExpert(CASE_ID);
		actAs(Role.PROJECT_MANAGER);
		lifecycle.expertDeclined(CASE_ID, "busy");
		given(offers.findByCaseIdAndOutcome(CASE_ID, OfferOutcome.OFFERED)).willReturn(List.of());

		actAs(Role.EXPERT_NETWORK_MANAGER);
		lifecycle.retakeExpert(CASE_ID, null);

		assertEquals(0, new BigDecimal("420.00").compareTo(savedOffers().getLast().getFee()));
		verify(audit).recordEvent(eq("OFFER"), any(), eq(AuditAction.UPDATED), any(), any(),
				argThat(after -> after instanceof java.util.Map<?, ?> m
						&& OfferOutcome.DECLINED.equals(m.get("outcome"))));
	}

	private static Expert expertWithoutFee(UUID id) {
		Expert value = mock(Expert.class);
		given(value.getId()).willReturn(id);
		given(value.getAvailability()).willReturn(Availability.AVAILABLE);
		return value;
	}
```

Imports as needed: `org.mockito.ArgumentMatchers.argThat`, `isNull`, `org.mockito.Mockito.never`, `com.ie.evalos.domain.AuditAction`.

- [ ] **Step 6: Run — expect FAIL (signatures / no fee on offers)**

Run: `cd backend && ./mvnw -q test -Dtest=CaseLifecycleServiceTest`

- [ ] **Step 7: Implement in `CaseLifecycleService`**

a) Signatures and pricing. In `assignCaseManager` add parameter `BigDecimal fee`; right after `Expert expert = availableExpert(expertId);`:

```java
		// Priced before anything moves: a refused price must leave the case where it was.
		BigDecimal price = OfferFees.price(TenantContext.current().role(), fee, expert.getStandardFee());
```

and replace `offers.save(new ExpertCaseOffer(saved.getBrandId(), saved.getId(), expert.getId()));` with `openOffer(saved, expert.getId(), price);`.

In `reassignExpert` add `BigDecimal fee` and call `rematch(subject, replacement, expertRationale, fieldOfExpertise, null, OfferFees.price(TenantContext.current().role(), fee, replacement.getStandardFee()))`.

In `retakeExpert(UUID caseId, BigDecimal fee)`:

```java
		Case subject = load(caseId);
		requireState(subject.getExpertId() != null, "the case names no expert to offer it back to");
		Expert again = availableExpert(subject.getExpertId());
		// The retake is the same case to the same expert, so the base is what they were last offered.
		BigDecimal previous = offers.findByCaseIdOrderByOfferedAtDesc(subject.getId()).stream()
				.filter(o -> o.getExpertId().equals(again.getId()) && o.getFee() != null)
				.map(ExpertCaseOffer::getFee).findFirst().orElse(again.getStandardFee());
		return rematch(subject, again, null, null, "Offered again to the expert who declined (retake)",
				OfferFees.price(TenantContext.current().role(), fee, previous));
```

`rematch` gains a trailing `BigDecimal price` and ends with `openOffer(saved, replacement.getId(), price);` instead of `offers.save(new ExpertCaseOffer(...))`.

b) The two audit helpers (next to `resolveOpenOffer`):

```java
	/** Opens the priced offer and logs it (Unit 65 rule 8). */
	private void openOffer(Case saved, UUID expertId, BigDecimal price) {
		UUID actor = TenantContext.current().memberId();
		ExpertCaseOffer offer = new ExpertCaseOffer(saved.getBrandId(), saved.getId(), expertId);
		offer.setFee(price, actor);
		offers.save(offer);
		audit.recordEvent("OFFER", offer.getId(), AuditAction.CREATED, actor, null,
				Map.of("caseId", saved.getId(), "expertId", expertId, "fee", price));
	}

	/** Logs an offer's answer with the fee it was answered at — which amount was agreed. */
	private void auditResolved(ExpertCaseOffer offer, PortalAudience actor) {
		Map<String, Object> after = new HashMap<>();
		after.put("outcome", offer.getOutcome());
		after.put("fee", offer.getFee()); // HashMap: a pre-V79 offer's fee is null
		Map<String, Object> before = Map.of("outcome", OfferOutcome.OFFERED);
		if (actor == null) {
			audit.recordEvent("OFFER", offer.getId(), AuditAction.UPDATED,
					TenantContext.find().map(TenantContext::memberId).orElse(null), before, after);
		} else {
			audit.recordPortalEvent(offer.getBrandId(), actor, "OFFER", offer.getId(), AuditAction.UPDATED,
					before, after);
		}
	}
```

c) `resolveOpenOffer(Case subject, OfferOutcome resolution, String reason, PortalAudience actor)`: inside the `if (offer.resolve(...))` add `auditResolved(offer, actor);` after `offers.save(offer);`. Update the callers: `signed` passes its `actor`, `declined` passes its `actor`, `expertTimedOut` passes `null`, `rematch` passes `null`, `expertAcceptedFromPortal` passes `PortalAudience.EXPERT`.

Imports: `java.math.BigDecimal`, `java.util.HashMap`, `java.util.Map` (if not present), `com.ie.evalos.domain.AuditAction`.

- [ ] **Step 8: Wire the controller** (`CaseController`)

```java
	public record AssignCmRequest(@NotNull UUID cmId, @NotNull UUID expertId, String expertRationale,
			FieldTag fieldOfExpertise, @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal fee) {
	}

	public record ExpertRequest(@NotNull UUID expertId, String expertRationale, FieldTag fieldOfExpertise,
			@DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal fee) {
	}

	/** Unit 65: blank keeps the fee the expert declined at. */
	public record RetakeRequest(@DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal fee) {
	}
```

(Keep each record's existing annotations/javadoc; only append `fee`.) Handlers:

```java
		return summary(lifecycle.assignCaseManager(id, request.cmId(), request.expertId(), request.expertRationale(),
				request.fieldOfExpertise(), request.fee()));
```
```java
		return summary(lifecycle.reassignExpert(id, request.expertId(), request.expertRationale(),
				request.fieldOfExpertise(), request.fee()));
```
```java
	public ApiResponse<CaseSummary> retakeExpert(@PathVariable UUID id,
			@Valid @RequestBody(required = false) RetakeRequest request) {
		return summary(lifecycle.retakeExpert(id, request == null ? null : request.fee()));
	}
```

In `CaseControllerTest` update the two stubs at ~L178 / ~L188 to the new arity (one more `any()`), and any `retakeExpert(any())` stub to `retakeExpert(any(), any())`.

- [ ] **Step 9: Run** `cd backend && ./mvnw -q test -Dtest='CaseLifecycleServiceTest,CaseControllerTest,OfferFeesTest'` → PASS. Then `grep -rn "assignCaseManager(\|reassignExpert(\|retakeExpert(" backend/src` and fix any remaining caller.

- [ ] **Step 10: Commit**

```bash
git add backend/src
git commit -m "feat(unit-65): every offer is priced and every offer answer is logged with its fee"
```

---

### Task 3: Read and edit the current offer's fee

**Files:**
- Create: `backend/src/main/java/com/ie/evalos/service/OfferLog.java`
- Create: `backend/src/main/java/com/ie/evalos/service/OfferFeeService.java`
- Create: `backend/src/main/java/com/ie/evalos/web/OfferFeeController.java`
- Test: `backend/src/test/java/com/ie/evalos/service/OfferLogTest.java`, `OfferFeeServiceTest.java`, `backend/src/test/java/com/ie/evalos/web/OfferFeeControllerTest.java`

**Interfaces:**
- Consumes: `ExpertCaseOffer.setFee` (Task 1); audit rows written in Task 2.
- Produces:
  - `OfferLog.Entry(Instant at, String who, String what, String before, String after)` (before/after = the raw JSON snapshots)
  - `OfferLog.forOffer(ExpertCaseOffer offer, PayoutLedger payoutOrNull): List<Entry>` (oldest first)
  - `OfferFeeService.OfferView(UUID offerId, UUID expertId, BigDecimal fee, String currency, OfferOutcome outcome, String feeSetByName, Instant feeSetAt, List<OfferLog.Entry> log)`
  - `OfferFeeService.current(UUID caseId): Optional<OfferView>`; `editFee(UUID caseId, BigDecimal fee): OfferView`
  - `OfferFeeService.MAY_SET_FEE: Set<Role>`
  - `GET /api/cases/{id}/expert/offer` → `ApiResponse<OfferView>` (data null when no offer); `PATCH /api/cases/{id}/expert/offer/fee` body `{fee}`

- [ ] **Step 1: Write the failing `OfferLogTest`**

```java
package com.ie.evalos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.junit.jupiter.api.Test;

class OfferLogTest {

	private final AuditEventRepository events = mock(AuditEventRepository.class);
	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final OfferLog log = new OfferLog(events, members);

	@Test
	void theLogMergesTheOfferThePayoutAndThePaymentOldestFirstWithNames() {
		UUID offerId = UUID.randomUUID(), payoutId = UUID.randomUUID(), paymentId = UUID.randomUUID();
		UUID pm = UUID.randomUUID();
		ExpertCaseOffer offer = mock(ExpertCaseOffer.class);
		given(offer.getId()).willReturn(offerId);
		PayoutLedger payout = mock(PayoutLedger.class);
		given(payout.getId()).willReturn(payoutId);
		given(payout.getPaymentId()).willReturn(paymentId);
		AuditEvent created = event(AuditAction.CREATED, pm, ActorType.STAFF, null, "{\"fee\":350.00}", "2026-09-01T10:00:00Z");
		AuditEvent accepted = event(AuditAction.UPDATED, null, ActorType.EXPERT, "{\"outcome\":\"OFFERED\"}",
				"{\"outcome\":\"ACCEPTED\",\"fee\":350.00}", "2026-09-02T10:00:00Z");
		AuditEvent opened = event(AuditAction.CREATED, null, ActorType.SYSTEM, null, "{\"status\":\"PENDING\"}", "2026-09-10T10:00:00Z");
		AuditEvent settled = event(AuditAction.PAYOUT_SETTLED, pm, ActorType.STAFF, null, "{\"amount\":350.00}", "2026-09-12T10:00:00Z");
		given(events.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("OFFER", offerId)).willReturn(List.of(created, accepted));
		given(events.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("PAYOUT", payoutId)).willReturn(List.of(opened));
		given(events.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("PAYOUT_PAYMENT", paymentId)).willReturn(List.of(settled));
		TeamMember member = mock(TeamMember.class);
		given(member.getId()).willReturn(pm);
		given(member.getDisplayName()).willReturn("Priya PM");
		given(members.findAllById(org.mockito.ArgumentMatchers.anyIterable())).willReturn(List.of(member));

		List<OfferLog.Entry> entries = log.forOffer(offer, payout);

		assertThat(entries).extracting(OfferLog.Entry::what)
				.containsExactly("Fee set", "Offer accepted", "Payout opened", "Transfer recorded");
		assertThat(entries).extracting(OfferLog.Entry::who)
				.containsExactly("Priya PM", "Expert", "EvalOS", "Priya PM");
	}

	private static AuditEvent event(AuditAction action, UUID actor, ActorType type, String before, String after,
			String at) {
		AuditEvent e = mock(AuditEvent.class);
		given(e.getAction()).willReturn(action);
		given(e.getActorId()).willReturn(actor);
		given(e.getActorType()).willReturn(type);
		given(e.getBeforeSnapshot()).willReturn(before);
		given(e.getAfterSnapshot()).willReturn(after);
		given(e.getCreatedAt()).willReturn(Instant.parse(at));
		return e;
	}
}
```

(`ActorType` — confirm its package with `grep -rn "enum ActorType" backend/src/main`; adjust the import.)

- [ ] **Step 2: Run — expect compilation FAIL**

Run: `cd backend && ./mvnw -q test -Dtest=OfferLogTest`

- [ ] **Step 3: Write `OfferLog`**

```java
package com.ie.evalos.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.springframework.stereotype.Component;

/**
 * One offer's money history, read off {@code audit_event} (Unit 65 rule 8): the fee set and
 * changed, the answer, the payout opened, the transfer recorded, the expert's confirmation.
 *
 * <p>The ids come from already-scoped rows (the offer and its payout), which is the only way the
 * unscoped audit finder may be called.
 */
@Component
public class OfferLog {

	public record Entry(Instant at, String who, String what, String before, String after) {
	}

	private final AuditEventRepository events;
	private final TeamMemberRepository members;

	OfferLog(AuditEventRepository events, TeamMemberRepository members) {
		this.events = events;
		this.members = members;
	}

	List<Entry> forOffer(ExpertCaseOffer offer, PayoutLedger payout) {
		List<Map.Entry<String, AuditEvent>> rows = new ArrayList<>();
		events.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("OFFER", offer.getId())
				.forEach(e -> rows.add(Map.entry("OFFER", e)));
		if (payout != null) {
			events.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("PAYOUT", payout.getId())
					.forEach(e -> rows.add(Map.entry("PAYOUT", e)));
			if (payout.getPaymentId() != null) {
				events.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("PAYOUT_PAYMENT", payout.getPaymentId())
						.forEach(e -> rows.add(Map.entry("PAYOUT_PAYMENT", e)));
			}
		}
		rows.sort(Comparator.comparing(r -> r.getValue().getCreatedAt()));

		List<UUID> actorIds = rows.stream().map(r -> r.getValue().getActorId()).filter(Objects::nonNull)
				.distinct().toList();
		Map<UUID, String> names = actorIds.isEmpty() ? Map.of()
				: members.findAllById(actorIds).stream()
						.collect(Collectors.toMap(TeamMember::getId, TeamMember::getDisplayName, (a, b) -> a));

		return rows.stream().map(r -> {
			AuditEvent e = r.getValue();
			return new Entry(e.getCreatedAt(), who(e, names), what(r.getKey(), e), e.getBeforeSnapshot(),
					e.getAfterSnapshot());
		}).toList();
	}

	private static String who(AuditEvent e, Map<UUID, String> names) {
		if (e.getActorType() == ActorType.EXPERT) {
			return "Expert";
		}
		if (e.getActorId() == null) {
			return "EvalOS";
		}
		return names.getOrDefault(e.getActorId(), "A former team member");
	}

	private static String what(String type, AuditEvent e) {
		String after = e.getAfterSnapshot() == null ? "" : e.getAfterSnapshot();
		return switch (type) {
			case "OFFER" -> e.getAction() == AuditAction.CREATED ? "Fee set"
					: after.contains("\"outcome\":\"ACCEPTED\"") ? "Offer accepted"
					: after.contains("\"outcome\":\"DECLINED\"") ? "Offer declined"
					: after.contains("\"outcome\":\"TIMED_OUT\"") ? "Offer timed out"
					: after.contains("\"outcome\":\"SUPERSEDED\"") ? "Offer superseded"
					: "Fee changed";
			case "PAYOUT" -> e.getAction() == AuditAction.CREATED ? "Payout opened" : "Payout amount set";
			default -> e.getAction() == AuditAction.PAYOUT_SETTLED ? "Transfer recorded"
					: after.contains("\"confirmed\":true") ? "Expert confirmed receipt" : "Transfer details edited";
		};
	}
}
```

- [ ] **Step 4: Run `OfferLogTest` → PASS**

- [ ] **Step 5: Write the failing `OfferFeeServiceTest`**

```java
package com.ie.evalos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.NotFoundException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.security.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OfferFeeServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();

	private final CaseRepository cases = mock(CaseRepository.class);
	private final ExpertCaseOfferRepository offers = mock(ExpertCaseOfferRepository.class);
	private final PayoutLedgerRepository payouts = mock(PayoutLedgerRepository.class);
	private final BrandRepository brands = mock(BrandRepository.class);
	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final OfferLog log = mock(OfferLog.class);
	private final OfferFeeService service = new OfferFeeService(cases, offers, payouts, brands, members, audit, log);

	private ExpertCaseOffer open;

	@BeforeEach
	void aCaseWithAnOpenOffer() {
		Case subject = new Case(BRAND, "IE-2026-0001", Stage.CLIENT_APPROVAL);
		given(cases.findScoped(any(TenantContext.class), eq(CASE_ID))).willReturn(Optional.of(subject));
		open = new ExpertCaseOffer(BRAND, CASE_ID, UUID.randomUUID());
		open.setFee(new BigDecimal("350.00"), null);
		given(offers.findByCaseIdOrderByOfferedAtDesc(CASE_ID)).willReturn(List.of(open));
		Brand brand = mock(Brand.class);
		given(brand.getCurrency()).willReturn("USD");
		given(brands.findById(BRAND)).willReturn(Optional.of(brand));
		given(log.forOffer(any(), any())).willReturn(List.of());
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void theFeeCanBeEditedWhileTheOfferIsOpenAndIsAudited() {
		actAs(Role.PROJECT_COORDINATOR);

		OfferFeeService.OfferView view = service.editFee(CASE_ID, new BigDecimal("400.00"));

		assertThat(view.fee()).isEqualByComparingTo("400.00");
		verify(offers).save(open);
		verify(audit).recordEvent(eq("OFFER"), any(), eq(AuditAction.UPDATED), any(),
				eq(Map.of("fee", new BigDecimal("350.00"))), eq(Map.of("fee", new BigDecimal("400.00"))));
	}

	@Test
	void theFeeCannotBeEditedOnceAnswered() {
		actAs(Role.PROJECT_MANAGER);
		for (OfferOutcome outcome : new OfferOutcome[] { OfferOutcome.ACCEPTED, OfferOutcome.DECLINED,
				OfferOutcome.TIMED_OUT, OfferOutcome.SUPERSEDED }) {
			ExpertCaseOffer answered = new ExpertCaseOffer(BRAND, CASE_ID, UUID.randomUUID());
			answered.resolve(outcome, null);
			given(offers.findByCaseIdOrderByOfferedAtDesc(CASE_ID)).willReturn(List.of(answered));
			assertThatThrownBy(() -> service.editFee(CASE_ID, BigDecimal.TEN))
					.isInstanceOf(IllegalTransitionException.class);
		}
	}

	@Test
	void aCaseManagerOrBrandManagerCannotEditTheFee() {
		for (Role role : new Role[] { Role.CASE_MANAGER, Role.BRAND_MANAGER, Role.SALES }) {
			actAs(role);
			assertThatThrownBy(() -> service.editFee(CASE_ID, BigDecimal.TEN)).isInstanceOf(ForbiddenException.class);
		}
	}

	@Test
	void aCaseOutsideTheCallersScopeIsNotFound() {
		// A PC's scope is SELF: a case not assigned to them is absent from the scoped load.
		given(cases.findScoped(any(TenantContext.class), eq(CASE_ID))).willReturn(Optional.empty());
		actAs(Role.PROJECT_COORDINATOR);
		assertThatThrownBy(() -> service.editFee(CASE_ID, BigDecimal.TEN)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void aNegativeFeeIsRefused() {
		actAs(Role.PROJECT_MANAGER);
		assertThatThrownBy(() -> service.editFee(CASE_ID, new BigDecimal("-1")))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class);
	}

	@Test
	void theCurrentOfferIsTheLatestWithTheBrandsCurrency() {
		actAs(Role.CASE_MANAGER);
		OfferFeeService.OfferView view = service.current(CASE_ID).orElseThrow();
		assertThat(view.fee()).isEqualByComparingTo("350.00");
		assertThat(view.currency()).isEqualTo("USD");
		assertThat(view.outcome()).isEqualTo(OfferOutcome.OFFERED);
	}

	private void actAs(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), "s@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND, UUID.randomUUID(), null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}
}
```

(Confirm `StaffPrincipal`'s package with `grep -rn "record StaffPrincipal\|class StaffPrincipal" backend/src/main`; `CaseLifecycleServiceTest.actAs` uses the same constructor.)

- [ ] **Step 6: Run — expect compilation FAIL**

- [ ] **Step 7: Write `OfferFeeService`**

```java
package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.common.NotFoundException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The fee on a case's current offer (Unit 65): read it for the case page, change it while the
 * offer is open. The case is loaded scoped, so a PC sees and edits only cases assigned to them.
 */
@Service
public class OfferFeeService {

	/** Who may change an open offer's fee (spec 65 rule 2). The CM is deliberately absent. */
	public static final Set<Role> MAY_SET_FEE = Set.of(Role.GM, Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR,
			Role.EXPERT_NETWORK_MANAGER);

	public record OfferView(UUID offerId, UUID expertId, BigDecimal fee, String currency, OfferOutcome outcome,
			String feeSetByName, Instant feeSetAt, List<OfferLog.Entry> log) {
	}

	private final CaseRepository cases;
	private final ExpertCaseOfferRepository offers;
	private final PayoutLedgerRepository payouts;
	private final BrandRepository brands;
	private final TeamMemberRepository members;
	private final AuditService audit;
	private final OfferLog log;

	OfferFeeService(CaseRepository cases, ExpertCaseOfferRepository offers, PayoutLedgerRepository payouts,
			BrandRepository brands, TeamMemberRepository members, AuditService audit, OfferLog log) {
		this.cases = cases;
		this.offers = offers;
		this.payouts = payouts;
		this.brands = brands;
		this.members = members;
		this.audit = audit;
		this.log = log;
	}

	@Transactional(readOnly = true)
	public Optional<OfferView> current(UUID caseId) {
		Case subject = load(caseId);
		return latest(subject).map(offer -> view(subject, offer));
	}

	@Transactional
	public OfferView editFee(UUID caseId, BigDecimal fee) {
		TenantContext ctx = TenantContext.current();
		if (!MAY_SET_FEE.contains(ctx.role())) {
			throw new ForbiddenException("Only a GM, PM, PC or ENM sets a case's fee.");
		}
		if (fee == null || fee.signum() < 0) {
			throw new InvalidRequestException("A fee cannot be empty or negative");
		}
		Case subject = load(caseId);
		ExpertCaseOffer offer = latest(subject)
				.orElseThrow(() -> new NotFoundException("This case has no expert offer"));
		BigDecimal before = offer.getFee();
		offer.setFee(fee, ctx.memberId()); // throws 409 unless OFFERED
		offers.save(offer);
		Map<String, Object> beforeSnapshot = new java.util.HashMap<>();
		beforeSnapshot.put("fee", before);
		audit.recordEvent("OFFER", offer.getId(), AuditAction.UPDATED, ctx.memberId(), beforeSnapshot,
				Map.of("fee", fee));
		return view(subject, offer);
	}

	private Case load(UUID caseId) {
		return cases.findScoped(TenantContext.current(), caseId)
				.orElseThrow(() -> new NotFoundException("No such case: " + caseId));
	}

	/** The case id came off a scoped load — the only way the unscoped finder may be called. */
	private Optional<ExpertCaseOffer> latest(Case subject) {
		return offers.findByCaseIdOrderByOfferedAtDesc(subject.getId()).stream().findFirst();
	}

	private OfferView view(Case subject, ExpertCaseOffer offer) {
		String currency = brands.findById(subject.getBrandId()).map(Brand::getCurrency).orElse(null);
		String setBy = offer.getFeeSetBy() == null ? null
				: members.findById(offer.getFeeSetBy()).map(TeamMember::getDisplayName).orElse(null);
		PayoutLedger payout = offer.getOutcome() != OfferOutcome.ACCEPTED ? null
				: payouts.findByBrandIdAndExpertIdOrderByCreatedAtDesc(subject.getBrandId(), offer.getExpertId())
						.stream()
						.filter(p -> p.getCaseId().equals(subject.getId()) && p.getStatus() != PayoutStatus.VOIDED)
						.findFirst().orElse(null);
		return new OfferView(offer.getId(), offer.getExpertId(), offer.getFee(), currency, offer.getOutcome(), setBy,
				offer.getFeeSetAt(), log.forOffer(offer, payout));
	}
}
```

Note: the audit assertion in the test uses `eq(Map.of("fee", 350.00))` — a `HashMap` with the same entries is `equals` to a `Map.of`, so it matches.

- [ ] **Step 8: Run `OfferFeeServiceTest` → PASS**

- [ ] **Step 9: Write `OfferFeeController` + its role test**

```java
package com.ie.evalos.web;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.OfferFeeService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The fee on a case's current expert offer (Unit 65). Scope is the service's scoped case load. */
@RestController
@RequestMapping("/api/cases/{id}/expert/offer")
public class OfferFeeController {

	public record FeeRequest(@NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal fee) {
	}

	private final OfferFeeService fees;

	OfferFeeController(OfferFeeService fees) {
		this.fees = fees;
	}

	@GetMapping
	public ApiResponse<OfferFeeService.OfferView> current(@PathVariable UUID id) {
		return ApiResponse.ok(fees.current(id).orElse(null));
	}

	@PatchMapping("/fee")
	@PreAuthorize("hasAnyRole('GM', 'PROJECT_MANAGER', 'PROJECT_COORDINATOR', 'EXPERT_NETWORK_MANAGER')")
	public ApiResponse<OfferFeeService.OfferView> editFee(@PathVariable UUID id,
			@Valid @RequestBody FeeRequest request) {
		return ApiResponse.ok(fees.editFee(id, request.fee()));
	}
}
```

`OfferFeeControllerTest` — pin the gate to the service constant, the way `PayoutControllerTest` pins `MAY_RECORD` (open that test and copy its reflection helper):

```java
	@Test
	void theFeeGateNamesExactlyTheFeeSetters() throws Exception {
		String gate = OfferFeeController.class.getMethod("editFee", UUID.class, OfferFeeController.FeeRequest.class)
				.getAnnotation(PreAuthorize.class).value();
		for (Role role : Role.values()) {
			assertThat(gate.contains("'" + role.name() + "'"))
					.as(role.name()).isEqualTo(OfferFeeService.MAY_SET_FEE.contains(role));
		}
	}
```

- [ ] **Step 10: Run** `./mvnw -q test -Dtest='OfferLogTest,OfferFeeServiceTest,OfferFeeControllerTest'` → PASS

- [ ] **Step 11: Commit**

```bash
git add backend/src
git commit -m "feat(unit-65): read the case's current offer with its log, edit its fee while open"
```

---

### Task 4: Delivery pays the accepted fee; a missing amount can be set once

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/service/PayoutService.java` (constructor; `openForDelivery` ~L103; `correctAmount` ~L486 → `setMissingAmount`)
- Modify: `backend/src/main/java/com/ie/evalos/web/PayoutController.java` (`correctAmount` handler ~L120)
- Test: `PayoutServiceTest`, `PayoutControllerTest`

**Interfaces:**
- Produces: `PayoutService.setMissingAmount(UUID payoutId, BigDecimal amount): void`; `PayoutService` constructor gains `ExpertCaseOfferRepository offers` as the **last** parameter.

- [ ] **Step 1: Failing tests** (`PayoutServiceTest`: add `private ExpertCaseOfferRepository offers;`, `offers = mock(...)`, pass it last to the constructor)

```java
	@Test
	void deliveryOpensThePayoutAtTheAcceptedFee() {
		givenBrand("USD", 7);
		givenExpert(new BigDecimal("350.00"));
		ExpertCaseOffer accepted = new ExpertCaseOffer(BRAND_IE, CASE_ID, EXPERT_ID);
		accepted.setFee(new BigDecimal("420.00"), ACTOR_ID);
		accepted.resolve(OfferOutcome.ACCEPTED, null);
		given(offers.findByCaseIdOrderByOfferedAtDesc(CASE_ID)).willReturn(List.of(accepted));

		service.openForDelivery(deliveredCase(EXPERT_ID));

		ArgumentCaptor<PayoutLedger> saved = ArgumentCaptor.forClass(PayoutLedger.class);
		verify(payouts).save(saved.capture());
		assertThat(saved.getValue().getAmount()).isEqualByComparingTo("420.00");
	}

	@Test
	void anOfferAcceptedBeforeV79FallsBackToTheStandardFee() {
		givenBrand("USD", 7);
		givenExpert(new BigDecimal("350.00"));
		ExpertCaseOffer accepted = new ExpertCaseOffer(BRAND_IE, CASE_ID, EXPERT_ID);
		accepted.resolve(OfferOutcome.ACCEPTED, null); // no fee
		given(offers.findByCaseIdOrderByOfferedAtDesc(CASE_ID)).willReturn(List.of(accepted));

		service.openForDelivery(deliveredCase(EXPERT_ID));

		ArgumentCaptor<PayoutLedger> saved = ArgumentCaptor.forClass(PayoutLedger.class);
		verify(payouts).save(saved.capture());
		assertThat(saved.getValue().getAmount()).isEqualByComparingTo("350.00");
	}

	@Test
	void aMissingAmountCanBeSetOnceAndAnExistingOneNever() {
		actAsRecorder(); // use this test class's existing helper that signs in as ENM/GM; rename if different
		PayoutLedger missing = new PayoutLedger(BRAND_IE, CASE_ID, EXPERT_ID, null, "USD", Instant.now());
		given(payouts.findScoped(any(TenantContext.class), eq(missing.getId()))).willReturn(Optional.of(missing));

		service.setMissingAmount(missing.getId(), new BigDecimal("300.00"));
		assertThat(missing.getAmount()).isEqualByComparingTo("300.00");

		assertThatThrownBy(() -> service.setMissingAmount(missing.getId(), new BigDecimal("310.00")))
				.isInstanceOf(IllegalTransitionException.class);
	}
```

The existing `deliveryOpensOnePendingRowPrefilledFromTheStandardFee` and `anExpertWithNoStandardFeeGetsARowWithNoAmount` keep passing unchanged (the mock `offers` returns an empty list → fallback). Delete any existing test that asserts `correctAmount` may overwrite a non-null amount; rename remaining `correctAmount` calls to `setMissingAmount` over rows whose amount is null.

- [ ] **Step 2: Run — expect FAIL**

Run: `cd backend && ./mvnw -q test -Dtest=PayoutServiceTest`

- [ ] **Step 3: Implement**

Constructor: add `ExpertCaseOfferRepository offers` last, a `private final ExpertCaseOfferRepository offers;` field, assign it. Fix every `new PayoutService(` (grep `backend/src`) to pass one more mock.

In `openForDelivery`, replace the `standardFee` block with:

```java
		// Unit 65: the case pays what the expert accepted. An offer accepted before V79 has no fee,
		// and then the standard fee stands as before — which may itself be null (set once, below).
		BigDecimal acceptedFee = offers.findByCaseIdOrderByOfferedAtDesc(delivered.getId()).stream()
				.filter(o -> o.getOutcome() == OfferOutcome.ACCEPTED && expertId.equals(o.getExpertId()))
				.map(ExpertCaseOffer::getFee).filter(Objects::nonNull).findFirst().orElse(null);
		BigDecimal fee = acceptedFee != null ? acceptedFee
				: experts.findByIdAndBrandId(expertId, delivered.getBrandId()).map(Expert::getStandardFee).orElse(null);
```

and pass `fee` into `new PayoutLedger(...)`.

Rename `correctAmount` → `setMissingAmount`, update its javadoc to *"Fill in the amount of a pending payout that opened with none (Unit 65). An amount that exists is the agreed fee and is never changed here."*, and change the status guard to:

```java
		if (row.getStatus() != PayoutStatus.PENDING || row.getAmount() != null) {
			throw new IllegalTransitionException("Payout " + payoutId + " already has its amount; it is the agreed fee");
		}
```

`PayoutController`: the PATCH handler calls `payouts.setMissingAmount(...)`; rename the handler to `setMissingAmount` and its request record `CorrectAmountRequest` → `MissingAmountRequest`. Update javadoc lines referencing `correctAmount` (grep).

`PayoutControllerTest`: update references; add

```java
	@Test
	void settingAnAmountThatExistsIsAConflict() throws Exception {
		willThrow(new IllegalTransitionException("already has its amount")).given(payouts).setMissingAmount(any(), any());
		// perform PATCH /api/payouts/{id} as ENM with {"amount": 10} using this class's MockMvc helper
		// and expect status 409.
	}
```

(Write it with the class's existing MockMvc request helper — copy the shape of the neighbouring PATCH test.)

- [ ] **Step 4: Run** `./mvnw -q test -Dtest='PayoutServiceTest,PayoutControllerTest'` → PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(unit-65): delivery pays the accepted fee; only a missing payout amount can be set"
```

---

### Task 5: The expert sees the fee and accepts it

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/service/ExpertPortalService.java` (records `ExpertCaseView` L100, `ExpertCaseSummary` L131; `project` L243; `expertCases` ~L290; `accept` L367; constructor)
- Modify: `backend/src/main/java/com/ie/evalos/web/ExpertPortalController.java` (accept L134)
- Test: `backend/src/test/java/com/ie/evalos/service/ExpertPortalServiceTest.java`, `PartyScopedPortalAccessTest.java`

**Interfaces:**
- Produces: `ExpertCaseSummary` gains trailing `BigDecimal offeredFee, String currency`; `ExpertCaseView` gains trailing `BigDecimal offeredFee, String currency`; `ExpertPortalService.accept(PortalPrincipal, UUID caseId, BigDecimal fee)`; `POST /api/portal/expert/accept?caseId=&fee=`.

- [ ] **Step 1: Failing tests** in `ExpertPortalServiceTest` (use its existing principal/case fixtures; stub `offers.findByCaseIdAndOutcome(caseId, OFFERED)`):

```java
	@Test
	void acceptingAStaleFeeIsRefused() {
		ExpertCaseOffer open = new ExpertCaseOffer(BRAND, CASE_ID, EXPERT_ID);
		open.setFee(new BigDecimal("400.00"), null);
		given(offers.findByCaseIdAndOutcome(CASE_ID, OfferOutcome.OFFERED)).willReturn(List.of(open));

		assertThatThrownBy(() -> service.accept(principal, CASE_ID, new BigDecimal("350.00")))
				.isInstanceOf(IllegalTransitionException.class)
				.hasMessage("The fee for this case changed — review it.");
		verify(lifecycle, never()).expertAcceptedFromPortal(any());
	}

	@Test
	void acceptingWithNoFeeIsRefused() {
		ExpertCaseOffer open = new ExpertCaseOffer(BRAND, CASE_ID, EXPERT_ID);
		given(offers.findByCaseIdAndOutcome(CASE_ID, OfferOutcome.OFFERED)).willReturn(List.of(open));

		assertThatThrownBy(() -> service.accept(principal, CASE_ID, null))
				.isInstanceOf(IllegalTransitionException.class)
				.hasMessage("The fee for this case is not set yet.");
	}

	@Test
	void acceptingTheShownFeeAccepts() {
		ExpertCaseOffer open = new ExpertCaseOffer(BRAND, CASE_ID, EXPERT_ID);
		open.setFee(new BigDecimal("400.00"), null);
		given(offers.findByCaseIdAndOutcome(CASE_ID, OfferOutcome.OFFERED)).willReturn(List.of(open));

		service.accept(principal, CASE_ID, new BigDecimal("400")); // 400 == 400.00 by compareTo
		verify(lifecycle).expertAcceptedFromPortal(any());
	}

	@Test
	void aSecondAcceptAfterTheFirstIsNotRefused() {
		// Review focus 1: the double click. No open offer any more → no fee check → lifecycle's idempotency.
		given(offers.findByCaseIdAndOutcome(CASE_ID, OfferOutcome.OFFERED)).willReturn(List.of());
		service.accept(principal, CASE_ID, new BigDecimal("400.00"));
		verify(lifecycle).expertAcceptedFromPortal(any());
	}
```

If the test class has no `lifecycle` mock (it may use a real one), stub accordingly — the assertion that matters is refusal before any transition.

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement**

`accept`:

```java
	/** The expert takes the case at the fee they were shown (Unit 65). Idempotent on a second click. */
	@Transactional
	public ExpertCaseView accept(PortalPrincipal principal, UUID caseId, BigDecimal fee) {
		Case authorized = authorized(principal, caseId);
		offers.findByCaseIdAndOutcome(authorized.getId(), OfferOutcome.OFFERED).stream()
				.filter(o -> o.getExpertId().equals(authorized.getExpertId()))
				.findFirst()
				.ifPresent(open -> {
					if (open.getFee() == null) {
						throw new IllegalTransitionException("The fee for this case is not set yet.");
					}
					if (fee == null || open.getFee().compareTo(fee) != 0) {
						throw new IllegalTransitionException("The fee for this case changed — review it.");
					}
				});
		return project(lifecycle.expertAcceptedFromPortal(authorized));
	}
```

Fee on reads — add a `BrandRepository brands` constructor dependency and:

```java
	/** The fee of this expert's open or accepted offer on the case, or null. */
	private BigDecimal offeredFee(UUID caseId, UUID expertId) {
		return offers.findByCaseIdOrderByOfferedAtDesc(caseId).stream()
				.filter(o -> o.getExpertId().equals(expertId)
						&& (o.getOutcome() == OfferOutcome.OFFERED || o.getOutcome() == OfferOutcome.ACCEPTED))
				.map(ExpertCaseOffer::getFee).findFirst().orElse(null);
	}

	private String currency(UUID brandId) {
		return brands.findById(brandId).map(Brand::getCurrency).orElse(null);
	}
```

Append `BigDecimal offeredFee, String currency` to both records; in `project(...)` pass `offeredFee(subject.getId(), subject.getExpertId()), currency(subject.getBrandId())`; in `expertCases` compute `String currency = currency(principal.brandId());` once before the stream and pass `offeredFee(subject.getId(), principal.expertId()), currency` (keep the existing ponytail comment style: *"ponytail: one offer read per case, like signedLetter; batch by case ids if a roster ever carries hundreds per expert."*).

Fix every `new ExpertCaseSummary(` / `new ExpertCaseView(` and every `new ExpertPortalService(` in tests (grep).

Controller:

```java
	@PostMapping("/accept")
	public ApiResponse<ExpertPortalService.ExpertCaseView> accept(@RequestParam(required = false) UUID caseId,
			@RequestParam(required = false) BigDecimal fee) {
		return ApiResponse.ok(portal.accept(expert(), caseId, fee));
	}
```

`PartyScopedPortalAccessTest`: the existing "no `payment_detail` in the JSON" assertion must still pass with the new fields — run it.

- [ ] **Step 4: Run** `./mvnw -q test -Dtest='ExpertPortalServiceTest,PartyScopedPortalAccessTest,ExpertPortalTest'` → PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(unit-65): the expert sees the fee and accepts exactly that fee"
```

---

### Task 6: Register, per-expert totals, overview and history

**Files:**
- Create: `backend/src/main/java/com/ie/evalos/service/PayoutRegisterService.java`
- Create: `backend/src/main/java/com/ie/evalos/web/PayoutRegisterController.java`
- Test: `backend/src/test/java/com/ie/evalos/service/PayoutRegisterServiceTest.java`, `backend/src/test/java/com/ie/evalos/web/PayoutRegisterControllerTest.java`

**Interfaces:**
- Consumes: `OfferLog.forOffer` (Task 3), `PayoutService.csvField` (package-private static, same package), `PayoutService.MAY_RECORD`.
- Produces (records nested in `PayoutRegisterService`):
  - `enum RegisterStatus { OFFERED, ACCEPTED, DECLINED, TIMED_OUT, SUPERSEDED, PENDING, PROCESSING, PAID }`
  - `RegisterRow(UUID offerId, UUID payoutId, UUID caseId, String caseCode, UUID expertId, String expertName, BigDecimal amount, String currency, RegisterStatus status, String feeSetByName, Instant feeSetAt, Instant offeredAt, Instant dueDate, Instant sentAt, Instant confirmedAt, boolean done)`
  - `Filter(RegisterStatus status, UUID expertId, LocalDate from, LocalDate to, String q)`
  - `ExpertTotals(UUID expertId, String expertName, String currency, BigDecimal committed, BigDecimal pending, BigDecimal processing, BigDecimal paid, Instant oldestPendingDue)`
  - `Tile(int count, BigDecimal amount)`; `Overview(String currency, Tile committed, Tile pending, Tile processing, Tile paid, List<RegisterRow> attention)`
  - `static RegisterStatus status(OfferOutcome outcome, PayoutStatus payout)`
  - `rows(Filter): List<RegisterRow>`, `exportCsv(Filter): String`, `experts(): List<ExpertTotals>`, `overview(LocalDate from, LocalDate to): List<Overview>`, `history(UUID offerId): List<OfferLog.Entry>`
  - `public static final int CONFIRM_NUDGE_DAYS = 7`
  - HTTP: `GET /api/payouts/cases`, `GET /api/payouts/cases/export`, `GET /api/payouts/cases/{offerId}/history`, `GET /api/payouts/experts`, `GET /api/payouts/overview`

- [ ] **Step 1: Failing service tests**

```java
package com.ie.evalos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutPayment;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.PayoutPaymentRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.PayoutRegisterService.RegisterStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayoutRegisterServiceTest {

	private static final UUID IE = UUID.randomUUID(), XP = UUID.randomUUID();
	private static final UUID EXPERT = UUID.randomUUID();

	private final ExpertCaseOfferRepository offers = mock(ExpertCaseOfferRepository.class);
	private final PayoutLedgerRepository payouts = mock(PayoutLedgerRepository.class);
	private final PayoutPaymentRepository payments = mock(PayoutPaymentRepository.class);
	private final CaseRepository cases = mock(CaseRepository.class);
	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final BrandRepository brands = mock(BrandRepository.class);
	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final OfferLog log = mock(OfferLog.class);
	private final PayoutRegisterService service =
			new PayoutRegisterService(offers, payouts, payments, cases, experts, brands, members, log);

	private final List<ExpertCaseOffer> offerRows = new ArrayList<>();
	private final List<PayoutLedger> payoutRows = new ArrayList<>();

	@BeforeEach
	void setUp() {
		given(offers.findScoped(any(TenantContext.class))).willReturn(offerRows);
		given(payouts.findScoped(any(TenantContext.class))).willReturn(payoutRows);
		given(payments.findAllById(anyIterable())).willReturn(List.of());
		given(cases.findAllById(anyIterable())).willReturn(List.of());
		given(experts.findAllById(anyIterable())).willReturn(List.of());
		given(members.findAllById(anyIterable())).willReturn(List.of());
		Brand ie = mock(Brand.class);
		given(ie.getId()).willReturn(IE);
		given(ie.getCurrency()).willReturn("USD");
		Brand xp = mock(Brand.class);
		given(xp.getId()).willReturn(XP);
		given(xp.getCurrency()).willReturn("INR");
		given(brands.findAllById(anyIterable())).willReturn(List.of(ie, xp));
		TestCaller.actAs(Role.EXPERT_NETWORK_MANAGER, IE); // see Step 3 note
	}

	@Test
	void theRegisterDerivesOneStatusPerOffer() {
		assertThat(PayoutRegisterService.status(OfferOutcome.OFFERED, null)).isEqualTo(RegisterStatus.OFFERED);
		assertThat(PayoutRegisterService.status(OfferOutcome.ACCEPTED, null)).isEqualTo(RegisterStatus.ACCEPTED);
		assertThat(PayoutRegisterService.status(OfferOutcome.ACCEPTED, PayoutStatus.PENDING)).isEqualTo(RegisterStatus.PENDING);
		assertThat(PayoutRegisterService.status(OfferOutcome.ACCEPTED, PayoutStatus.PAID)).isEqualTo(RegisterStatus.PROCESSING);
		assertThat(PayoutRegisterService.status(OfferOutcome.ACCEPTED, PayoutStatus.CONFIRMED)).isEqualTo(RegisterStatus.PAID);
		assertThat(PayoutRegisterService.status(OfferOutcome.DECLINED, null)).isEqualTo(RegisterStatus.DECLINED);
		assertThat(PayoutRegisterService.status(null, PayoutStatus.PENDING)).isEqualTo(RegisterStatus.PENDING);
	}

	@Test
	void anAcceptedOfferPairsWithItsPayoutAndShowsThePayoutsAmount() {
		UUID caseId = UUID.randomUUID();
		offerRows.add(accepted(IE, caseId, "350.00"));
		payoutRows.add(new PayoutLedger(IE, caseId, EXPERT, new BigDecimal("360.00"), "USD", Instant.now()));

		List<PayoutRegisterService.RegisterRow> rows = service.rows(PayoutRegisterService.Filter.none());

		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().status()).isEqualTo(RegisterStatus.PENDING);
		assertThat(rows.getFirst().amount()).isEqualByComparingTo("360.00");
	}

	@Test
	void aPayoutWithNoAcceptedOfferStillAppears() {
		// Review focus 4: pre-V79 or staff-signed cases.
		payoutRows.add(new PayoutLedger(IE, UUID.randomUUID(), EXPERT, new BigDecimal("300.00"), "USD", Instant.now()));

		List<PayoutRegisterService.RegisterRow> rows = service.rows(PayoutRegisterService.Filter.none());

		assertThat(rows).singleElement().satisfies(r -> {
			assertThat(r.offerId()).isNull();
			assertThat(r.amount()).isEqualByComparingTo("300.00");
		});
	}

	@Test
	void anUnpricedOfferWithNoPayoutIsLeftOut() {
		offerRows.add(new ExpertCaseOffer(IE, UUID.randomUUID(), EXPERT)); // closed pre-V79 shape: no fee
		assertThat(service.rows(PayoutRegisterService.Filter.none())).isEmpty();
	}

	@Test
	void expertTotalsAddUpByStatus() {
		offerRows.add(accepted(IE, UUID.randomUUID(), "100.00")); // committed
		UUID pendingCase = UUID.randomUUID();
		offerRows.add(accepted(IE, pendingCase, "200.00"));
		payoutRows.add(new PayoutLedger(IE, pendingCase, EXPERT, new BigDecimal("200.00"), "USD", Instant.now()));

		PayoutRegisterService.ExpertTotals totals = service.experts().getFirst();

		assertThat(totals.committed()).isEqualByComparingTo("100.00");
		assertThat(totals.pending()).isEqualByComparingTo("200.00");
		assertThat(totals.processing()).isEqualByComparingTo("0");
		assertThat(totals.paid()).isEqualByComparingTo("0");
	}

	@Test
	void theOverviewFlagsOverdueAndIsOnePerCurrency() {
		// Review focus 3: never add USD to INR.
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		offerRows.add(accepted(IE, a, "100.00"));
		offerRows.add(accepted(XP, b, "5000.00"));
		payoutRows.add(new PayoutLedger(IE, a, EXPERT, new BigDecimal("100.00"), "USD",
				Instant.now().minus(3, ChronoUnit.DAYS))); // due in the past → overdue

		List<PayoutRegisterService.Overview> overviews = service.overview(null, null);

		assertThat(overviews).extracting(PayoutRegisterService.Overview::currency).containsExactlyInAnyOrder("USD", "INR");
		PayoutRegisterService.Overview usd = overviews.stream().filter(o -> o.currency().equals("USD")).findFirst().orElseThrow();
		assertThat(usd.pending().amount()).isEqualByComparingTo("100.00");
		assertThat(usd.attention()).hasSize(1);
	}

	@Test
	void theCsvNeutralisesAFormulaInAnExpertsName() {
		// Review focus 5.
		UUID caseId = UUID.randomUUID();
		offerRows.add(accepted(IE, caseId, "100.00"));
		com.ie.evalos.domain.Expert evil = mock(com.ie.evalos.domain.Expert.class);
		given(evil.getId()).willReturn(EXPERT);
		given(evil.getFullName()).willReturn("=HYPERLINK(\"x\")");
		given(experts.findAllById(anyIterable())).willReturn(List.of(evil));

		assertThat(service.exportCsv(PayoutRegisterService.Filter.none())).contains("'=HYPERLINK");
	}

	private static ExpertCaseOffer accepted(UUID brand, UUID caseId, String fee) {
		ExpertCaseOffer offer = new ExpertCaseOffer(brand, caseId, EXPERT);
		offer.setFee(new BigDecimal(fee), null);
		offer.resolve(OfferOutcome.ACCEPTED, null);
		return offer;
	}
}
```

Note on `TestCaller.actAs`: the service calls `TenantContext.current()`. Copy the `actAs(Role)` helper from `OfferFeeServiceTest` into this class (with brand `IE`) instead of a shared helper — same six lines. `PayoutLedger`'s constructor is `(brandId, caseId, expertId, amount, currency, dueDate)` as used in `openForDelivery`. If `PayoutPaymentRepository` / `CaseRepository` / `ExpertRepository` / `BrandRepository` lack `findAllById` (not `JpaRepository`), use the finder `PayoutService.expertNames` / `caseCodes` already use — open them and mirror.

- [ ] **Step 2: Run — expect compilation FAIL**

- [ ] **Step 3: Write `PayoutRegisterService`**

```java
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
 * and the week's attention list. Read-only; writes stay in {@link PayoutService} and
 * {@link OfferFeeService}.
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

	public static final int CONFIRM_NUDGE_DAYS = 7;

	public enum RegisterStatus {
		OFFERED, ACCEPTED, DECLINED, TIMED_OUT, SUPERSEDED, PENDING, PROCESSING, PAID
	}

	public record RegisterRow(UUID offerId, UUID payoutId, UUID caseId, String caseCode, UUID expertId,
			String expertName, BigDecimal amount, String currency, RegisterStatus status, String feeSetByName,
			Instant feeSetAt, Instant offeredAt, Instant dueDate, Instant sentAt, Instant confirmedAt, boolean done) {
	}

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

	static RegisterStatus status(OfferOutcome outcome, PayoutStatus payout) {
		if (payout != null) {
			return switch (payout) {
				case PENDING -> RegisterStatus.PENDING;
				case PAID -> RegisterStatus.PROCESSING;
				case CONFIRMED -> RegisterStatus.PAID;
				case VOIDED -> throw new IllegalArgumentException("voided payouts are not registered");
			};
		}
		return RegisterStatus.valueOf(outcome.name());
	}

	@Transactional(readOnly = true)
	public List<RegisterRow> rows(Filter filter) {
		return allRows().stream().filter(row -> matches(row, filter)).toList();
	}

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

	@Transactional(readOnly = true)
	public List<Overview> overview(LocalDate from, LocalDate to) {
		Instant now = Instant.now();
		Instant nudge = now.minus(CONFIRM_NUDGE_DAYS, ChronoUnit.DAYS);
		Filter window = new Filter(null, null, from, to, null);
		Map<String, List<RegisterRow>> byCurrency = rows(window).stream()
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

	private List<RegisterRow> allRows() {
		TenantContext ctx = TenantContext.current();
		List<ExpertCaseOffer> offerRows = offers.findScoped(ctx);
		Map<List<UUID>, PayoutLedger> payoutByCaseAndExpert = new HashMap<>();
		payouts.findScoped(ctx).stream().filter(p -> p.getStatus() != PayoutStatus.VOIDED)
				.forEach(p -> payoutByCaseAndExpert.put(List.of(p.getCaseId(), p.getExpertId()), p));

		record Pair(ExpertCaseOffer offer, PayoutLedger payout) {
		}
		List<Pair> pairs = new ArrayList<>();
		for (ExpertCaseOffer o : offerRows) {
			PayoutLedger p = o.getOutcome() == OfferOutcome.ACCEPTED
					? payoutByCaseAndExpert.remove(List.of(o.getCaseId(), o.getExpertId())) : null;
			if (o.getFee() != null || p != null) {
				pairs.add(new Pair(o, p));
			}
		}
		payoutByCaseAndExpert.values().forEach(p -> pairs.add(new Pair(null, p)));

		// One query per lookup for the whole set, never one per row.
		Map<UUID, String> caseCodes = byId(cases.findAllById(ids(pairs, x -> x.offer() != null ? x.offer().getCaseId()
				: x.payout().getCaseId())), Case::getId, Case::getCaseCode);
		Map<UUID, String> expertNames = byId(experts.findAllById(ids(pairs, x -> x.offer() != null
				? x.offer().getExpertId() : x.payout().getExpertId())), Expert::getId, Expert::getFullName);
		Map<UUID, String> setBy = byId(members.findAllById(ids(pairs, x -> x.offer() == null ? null
				: x.offer().getFeeSetBy())), TeamMember::getId, TeamMember::getDisplayName);
		Map<UUID, PayoutPayment> paymentById = byId(payments.findAllById(ids(pairs, x -> x.payout() == null ? null
				: x.payout().getPaymentId())), PayoutPayment::getId, Function.identity());
		Map<UUID, String> currencies = byId(brands.findAllById(ids(pairs, x -> x.offer() != null
				? x.offer().getBrandId() : x.payout().getBrandId())), Brand::getId, Brand::getCurrency);

		return pairs.stream().map(x -> {
			ExpertCaseOffer o = x.offer();
			PayoutLedger p = x.payout();
			UUID caseId = o != null ? o.getCaseId() : p.getCaseId();
			UUID expertId = o != null ? o.getExpertId() : p.getExpertId();
			PayoutPayment pay = p == null || p.getPaymentId() == null ? null : paymentById.get(p.getPaymentId());
			RegisterStatus status = status(o == null ? null : o.getOutcome(), p == null ? null : p.getStatus());
			return new RegisterRow(o == null ? null : o.getId(), p == null ? null : p.getId(), caseId,
					caseCodes.get(caseId), expertId, expertNames.get(expertId),
					p != null && p.getAmount() != null ? p.getAmount() : o == null ? null : o.getFee(),
					p != null ? p.getCurrency() : currencies.get(o.getBrandId()), status,
					o == null || o.getFeeSetBy() == null ? null : setBy.get(o.getFeeSetBy()),
					o == null ? null : o.getFeeSetAt(), o != null ? o.getOfferedAt() : p.getCreatedAt(),
					p == null ? null : p.getDueDate(), pay == null ? null : pay.getPaidDate(),
					pay == null ? null : pay.getConfirmedAt(), status == RegisterStatus.PAID);
		}).sorted(Comparator.comparing(RegisterRow::offeredAt, Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
	}

	private static boolean matches(RegisterRow r, Filter f) {
		LocalDate day = r.offeredAt() == null ? null : r.offeredAt().atZone(BusinessCalendar.ZONE).toLocalDate();
		String q = f.q() == null ? null : f.q().trim().toLowerCase();
		return (f.status() == null || r.status() == f.status())
				&& (f.expertId() == null || f.expertId().equals(r.expertId()))
				&& (f.from() == null || (day != null && !day.isBefore(f.from())))
				&& (f.to() == null || (day != null && !day.isAfter(f.to())))
				&& (q == null || q.isEmpty() || contains(r.caseCode(), q) || contains(r.expertName(), q));
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
```

If any repository's `findAllById` takes `Iterable` and returns `Iterable` rather than `List`, wrap with a `List` (e.g. `StreamSupport`), or use the finder `PayoutService` uses for the same lookup. `PayoutLedger.getCreatedAt()` exists (used by `PayoutService.reportDate`). `BusinessCalendar.ZONE` is in this package (used by `PayoutService`).

- [ ] **Step 4: Run `PayoutRegisterServiceTest` → PASS**

- [ ] **Step 5: Controller + role test**

```java
package com.ie.evalos.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.OfferLog;
import com.ie.evalos.service.PayoutRegisterService;
import com.ie.evalos.service.PayoutRegisterService.Filter;
import com.ie.evalos.service.PayoutRegisterService.RegisterStatus;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Payouts module's reads (Unit 65). The same three roles as every other payout route. */
@RestController
@RequestMapping("/api/payouts")
@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'EXPERT_NETWORK_MANAGER')")
public class PayoutRegisterController {

	private final PayoutRegisterService register;

	PayoutRegisterController(PayoutRegisterService register) {
		this.register = register;
	}

	@GetMapping("/cases")
	public ApiResponse<List<PayoutRegisterService.RegisterRow>> cases(@RequestParam(required = false) RegisterStatus status,
			@RequestParam(required = false) UUID expertId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String q) {
		return ApiResponse.ok(register.rows(new Filter(status, expertId, from, to, q)));
	}

	@GetMapping(value = "/cases/export", produces = "text/csv")
	public ResponseEntity<String> export(@RequestParam(required = false) RegisterStatus status,
			@RequestParam(required = false) UUID expertId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String q) {
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payout-cases.csv\"")
				.body(register.exportCsv(new Filter(status, expertId, from, to, q)));
	}

	@GetMapping("/cases/{offerId}/history")
	public ApiResponse<List<OfferLog.Entry>> history(@PathVariable UUID offerId) {
		return ApiResponse.ok(register.history(offerId));
	}

	@GetMapping("/experts")
	public ApiResponse<List<PayoutRegisterService.ExpertTotals>> experts() {
		return ApiResponse.ok(register.experts());
	}

	@GetMapping("/overview")
	public ApiResponse<List<PayoutRegisterService.Overview>> overview(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return ApiResponse.ok(register.overview(from, to));
	}
}
```

Copy the header style of `PayoutController.export` for the CSV response if it differs.

`PayoutRegisterControllerTest`: assert the class-level `@PreAuthorize` names exactly `PayoutService.MAY_RECORD` (same reflection loop as Task 3 Step 9, over `PayoutRegisterController.class.getAnnotation(PreAuthorize.class)`). Also confirm `GET /api/payouts/cases` does not collide with `PayoutController`'s `GET /{id}`: in the existing MockMvc slice for `PayoutController`, add `PayoutRegisterController` + a mock `PayoutRegisterService` and expect `GET /api/payouts/cases` as ENM → 200 and as `PROJECT_MANAGER` → 403.

- [ ] **Step 6: Run** `./mvnw -q test -Dtest='PayoutRegisterServiceTest,PayoutRegisterControllerTest,PayoutControllerTest'` → PASS

- [ ] **Step 7: Full backend suite**

Run: `cd backend && ./mvnw -q test`
Expected: `0 failures, 0 errors` (4 opt-in skips as before). Fix any constructor call site this unit changed.

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "feat(unit-65): payouts register, per-expert totals, overview and per-offer log"
```

---

### Task 7: Staff SPA — types, rules and API for the module

**Files:**
- Create: `frontend/src/features/payouts/registerRules.ts`, `frontend/src/features/payouts/registerRules.test.ts`, `frontend/src/features/payouts/registerApi.ts`
- Modify: `frontend/src/features/payouts/payoutApi.ts` (rename `correctAmount` → `setMissingAmount`)

**Interfaces:**
- Produces (`registerRules.ts`): types `RegisterStatus`, `RegisterRow`, `RegisterFilter`, `ExpertTotals`, `Tile`, `Overview`, `LogEntry`, `OfferView`; `REGISTER_STATUS_LABEL: Record<RegisterStatus,string>`; `describeChange(before: string|null, after: string|null): string`; `registerParams(f: RegisterFilter): Record<string,string>`; `MAY_SET_FEE: readonly Role[]`
- Produces (`registerApi.ts`): `fetchRegister(f, signal)`, `exportRegister(f): Promise<Blob>`, `fetchOfferHistory(offerId, signal)`, `fetchExpertTotals(signal)`, `fetchOverview(from, to, signal)`, `fetchCaseOffer(caseId, signal): Promise<OfferView|null>`, `editOfferFee(caseId, fee): Promise<OfferView>`

- [ ] **Step 1: Failing tests**

```ts
import { describe, expect, it } from 'vitest'
import { REGISTER_STATUS_LABEL, describeChange, registerParams } from './registerRules'

describe('REGISTER_STATUS_LABEL', () => {
  it('uses the business words, with stored PAID/CONFIRMED already mapped server-side', () => {
    expect(REGISTER_STATUS_LABEL.PROCESSING).toBe('Processing')
    expect(REGISTER_STATUS_LABEL.PAID).toBe('Paid')
    expect(REGISTER_STATUS_LABEL.TIMED_OUT).toBe('Timed out')
  })
})

describe('describeChange', () => {
  it('shows old → new for a changed key', () => {
    expect(describeChange('{"fee":350.00}', '{"fee":400.00}')).toBe('fee: 350 → 400')
  })
  it('shows only the new value when there was no before', () => {
    expect(describeChange(null, '{"fee":350}')).toBe('fee: 350')
  })
  it('lowercases and spaces an outcome', () => {
    expect(describeChange('{"outcome":"OFFERED"}', '{"outcome":"TIMED_OUT","fee":350}'))
      .toBe('outcome: offered → timed out · fee: 350')
  })
  it('never throws on a snapshot that is not JSON', () => {
    expect(describeChange('oops', null)).toBe('')
  })
})

describe('registerParams', () => {
  it('drops empty filters so the URL stays clean', () => {
    expect(registerParams({ status: 'PENDING', q: '  ' })).toEqual({ status: 'PENDING' })
  })
})
```

- [ ] **Step 2: Run** `cd frontend && npx vitest run src/features/payouts/registerRules.test.ts` → FAIL (module missing)

- [ ] **Step 3: Write `registerRules.ts`**

```ts
import type { Role } from '../../lib/roles'

/**
 * The Payouts module's types (Unit 65), member for member with `PayoutRegisterService` and
 * `OfferFeeService`. Pure: this is the part vitest covers.
 */

export type RegisterStatus =
  | 'OFFERED' | 'ACCEPTED' | 'DECLINED' | 'TIMED_OUT' | 'SUPERSEDED' | 'PENDING' | 'PROCESSING' | 'PAID'

export const REGISTER_STATUS_LABEL: Record<RegisterStatus, string> = {
  OFFERED: 'Offered',
  ACCEPTED: 'Accepted',
  DECLINED: 'Declined',
  TIMED_OUT: 'Timed out',
  SUPERSEDED: 'Superseded',
  PENDING: 'Pending',
  PROCESSING: 'Processing',
  PAID: 'Paid',
}

export type RegisterRow = {
  offerId: string | null
  payoutId: string | null
  caseId: string
  caseCode: string | null
  expertId: string
  expertName: string | null
  amount: number | null
  currency: string | null
  status: RegisterStatus
  feeSetByName: string | null
  feeSetAt: string | null
  offeredAt: string | null
  dueDate: string | null
  sentAt: string | null
  confirmedAt: string | null
  done: boolean
}

export type RegisterFilter = {
  status?: RegisterStatus
  expertId?: string
  from?: string
  to?: string
  q?: string
}

export type ExpertTotals = {
  expertId: string
  expertName: string | null
  currency: string | null
  committed: number
  pending: number
  processing: number
  paid: number
  oldestPendingDue: string | null
}

export type Tile = { count: number; amount: number }

export type Overview = {
  currency: string
  committed: Tile
  pending: Tile
  processing: Tile
  paid: Tile
  attention: RegisterRow[]
}

export type LogEntry = { at: string; who: string; what: string; before: string | null; after: string | null }

export type OfferView = {
  offerId: string
  expertId: string
  fee: number | null
  currency: string | null
  outcome: 'OFFERED' | 'ACCEPTED' | 'DECLINED' | 'TIMED_OUT' | 'SUPERSEDED'
  feeSetByName: string | null
  feeSetAt: string | null
  log: LogEntry[]
}

/** Mirrors `OfferFeeService.MAY_SET_FEE`: who sees Edit on an open offer. */
export const MAY_SET_FEE: readonly Role[] = ['GM', 'PROJECT_MANAGER', 'PROJECT_COORDINATOR', 'EXPERT_NETWORK_MANAGER']

function parse(snapshot: string | null): Record<string, unknown> {
  if (!snapshot) return {}
  try {
    const value: unknown = JSON.parse(snapshot)
    return value && typeof value === 'object' ? (value as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

function word(value: unknown): string {
  if (typeof value === 'number') return String(value)
  if (typeof value === 'string') return /^[A-Z_]+$/.test(value) ? value.toLowerCase().replace(/_/g, ' ') : value
  return JSON.stringify(value)
}

/** One log line's detail: `key: old → new` for each key in the after snapshot. */
export function describeChange(before: string | null, after: string | null): string {
  const b = parse(before)
  const a = parse(after)
  return Object.keys(a)
    .filter((key) => a[key] !== null && a[key] !== undefined)
    .map((key) => (key in b && b[key] !== a[key] && b[key] !== null
      ? `${key}: ${word(b[key])} → ${word(a[key])}`
      : `${key}: ${word(a[key])}`))
    .join(' · ')
}

export function registerParams(f: RegisterFilter): Record<string, string> {
  const params: Record<string, string> = {}
  for (const [key, value] of Object.entries(f)) {
    if (typeof value === 'string' && value.trim()) params[key] = value.trim()
  }
  return params
}
```

`describeChange('{"fee":350.00}', ...)` — `JSON.parse` yields `350`, so the expected text is `350 → 400`. Confirm `Role`'s import path with `grep -rn "export type Role" frontend/src/lib`; adjust.

- [ ] **Step 4: Write `registerApi.ts`**

```ts
import { api, unwrap } from '../../lib/api'
import type { ExpertTotals, LogEntry, OfferView, Overview, RegisterFilter, RegisterRow } from './registerRules'
import { registerParams } from './registerRules'

/** The Payouts module's reads (Unit 65) and the offer-fee edit. EvalOS still moves no money. */

export async function fetchRegister(filter: RegisterFilter, signal?: AbortSignal): Promise<RegisterRow[]> {
  return unwrap<RegisterRow[]>(api.get('/payouts/cases', { params: registerParams(filter), signal }))
}

export async function exportRegister(filter: RegisterFilter): Promise<Blob> {
  const response = await api.get('/payouts/cases/export', { params: registerParams(filter), responseType: 'blob' })
  return response.data as Blob
}

export async function fetchOfferHistory(offerId: string, signal?: AbortSignal): Promise<LogEntry[]> {
  return unwrap<LogEntry[]>(api.get(`/payouts/cases/${offerId}/history`, { signal }))
}

export async function fetchExpertTotals(signal?: AbortSignal): Promise<ExpertTotals[]> {
  return unwrap<ExpertTotals[]>(api.get('/payouts/experts', { signal }))
}

export async function fetchOverview(from: string | null, to: string | null, signal?: AbortSignal): Promise<Overview[]> {
  const params: Record<string, string> = {}
  if (from) params.from = from
  if (to) params.to = to
  return unwrap<Overview[]>(api.get('/payouts/overview', { params, signal }))
}

export async function fetchCaseOffer(caseId: string, signal?: AbortSignal): Promise<OfferView | null> {
  return unwrap<OfferView | null>(api.get(`/cases/${caseId}/expert/offer`, { signal }))
}

export async function editOfferFee(caseId: string, fee: number): Promise<OfferView> {
  return unwrap<OfferView>(api.patch(`/cases/${caseId}/expert/offer/fee`, { fee }))
}
```

- [ ] **Step 5: Rename in `payoutApi.ts`**

```ts
/** Fill in a pending payout that opened with no amount (Unit 65). Refused once it has one. */
export async function setMissingAmount(payoutId: string, amount: number): Promise<LedgerRow> {
  return unwrap<LedgerRow>(api.patch(`/payouts/${payoutId}`, { amount }))
}
```

In `ExpertPayouts.tsx`: import `setMissingAmount`, call it where `correctAmount` was, and render the amount input **only** for a row whose `amount === null` (others show the amount as text). Update the component javadoc paragraph about correction to: *"A pending row with no amount (an expert with no standard fee, from before Unit 65) can have it filled in once; an amount that exists is the agreed fee and is read-only."*

- [ ] **Step 6: Run** `cd frontend && npx vitest run src/features/payouts && npx tsc -b && npx oxlint` → PASS, clean

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/payouts
git commit -m "feat(unit-65): staff types and API for the payouts module; amount set only when missing"
```

---

### Task 8: Staff SPA — the Payouts module screens

Use the **frontend-design** skill for this task's visual work. Stay inside the existing system: `frontend/src/styles/tokens.css` variables (`--text-muted` etc.), the tile / table patterns in `features/dashboards/*` and `PayoutSummary.tsx`, `formatPayout` from `lib/money`. No new dependency.

**Files:**
- Create: `frontend/src/features/payouts/PayoutsOverview.tsx`, `PayoutRegister.tsx`, `ExpertBalances.tsx`, `OfferLogPanel.tsx`
- Modify: `frontend/src/features/shell/navigation.ts` (NavGroup L58, `/payouts` item ~L424), `frontend/src/features/shell/navigation.test.ts`, `frontend/src/App.tsx` (SCREENS L62), `frontend/src/features/payouts/PayoutBatch.tsx` (remove `<PayoutSummary />`)

**Interfaces:**
- Consumes: Task 7's API and types.
- Produces: routes `/payouts` (Overview), `/payouts/cases`, `/payouts/experts`, `/payouts/pay`; `OfferLogPanel({ entries, onClose, title })`.

- [ ] **Step 1: Failing nav test** (add to `navigation.test.ts`)

```ts
it('gives payouts its own group of four screens for the three payout roles', () => {
  for (const role of ['GM', 'BRAND_MANAGER', 'EXPERT_NETWORK_MANAGER'] as const) {
    const section = navSectionsFor(role).find((s) => s.group === 'Payouts')
    expect(section?.items.map((i) => i.path)).toEqual(['/payouts', '/payouts/cases', '/payouts/experts', '/payouts/pay'])
  }
  for (const role of ['PROJECT_MANAGER', 'PROJECT_COORDINATOR', 'CASE_MANAGER', 'SALES'] as const) {
    expect(navSectionsFor(role).some((s) => s.group === 'Payouts')).toBe(false)
  }
})
```

- [ ] **Step 2: Run** `cd frontend && npx vitest run src/features/shell` → FAIL

- [ ] **Step 3: Navigation**

`NavGroup` gains `'Payouts'` (between `'Records'` and `'Admin'`). Replace the single `/payouts` item with (placed right after the `/experts` item, so the group run is consecutive):

```ts
  // Unit 65: payouts is its own module — one question per screen.
  { path: '/payouts', label: 'Overview', roles: PAYOUT_ROLES, becomes: 'Where the money stands', group: 'Payouts' },
  { path: '/payouts/cases', label: 'Cases', roles: PAYOUT_ROLES, becomes: 'Every case: fee, status, done', group: 'Payouts' },
  { path: '/payouts/experts', label: 'Experts', roles: PAYOUT_ROLES, becomes: 'Who is owed how much', group: 'Payouts' },
  { path: '/payouts/pay', label: 'Pay run', roles: PAYOUT_ROLES, becomes: 'Weekly payout batch', group: 'Payouts' },
```

`PAYOUT_ROLES` is declared below `NAV_ITEMS`; move its declaration above `NAV_ITEMS` so it can be used there. Change `EXPERT_PAYOUTS_PATH` / `PAYMENT_DETAIL_PATH` items' `group` to `'Payouts'`. Fix any existing nav test that asserted the old `/payouts` label or the `Records` group contents.

`App.tsx` SCREENS: `'/payouts': <PayoutsOverview />`, `'/payouts/cases': <PayoutRegister />`, `'/payouts/experts': <ExpertBalances />`, `'/payouts/pay': <PayoutBatch />` (+ imports). In `PayoutBatch.tsx` remove the `PayoutSummary` import and element (it moves to Overview) and fix its header copy to "Pay run".

- [ ] **Step 4: `OfferLogPanel.tsx`** — a right-hand side panel (fixed, full height, `w-full sm:w-[28rem]`, `role="dialog"`, `aria-label={title}`, closes on Escape and on the close button):

```tsx
import { useEffect } from 'react'
import { describeChange, type LogEntry } from './registerRules'

/** One offer's money history, oldest first (Unit 65 rule 8). Read-only: the log is append-only. */
export default function OfferLogPanel({ title, entries, onClose }: {
  title: string
  entries: readonly LogEntry[] | null
  onClose: () => void
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <aside role="dialog" aria-label={title}
      className="fixed inset-y-0 right-0 z-40 flex w-full flex-col gap-4 overflow-y-auto border-l p-6 shadow-xl sm:w-[28rem]"
      style={{ background: 'var(--surface)', borderColor: 'var(--border)' }}>
      <header className="flex items-start justify-between gap-3">
        <h2 className="text-base font-semibold tracking-tight">{title}</h2>
        <button type="button" onClick={onClose} aria-label="Close" className="rounded px-2 py-1 text-sm">✕</button>
      </header>
      {entries === null ? <p style={{ color: 'var(--text-muted)' }}>Loading…</p>
        : entries.length === 0 ? <p style={{ color: 'var(--text-muted)' }}>Nothing logged yet.</p>
        : (
          <ol className="flex flex-col gap-3">
            {entries.map((e, i) => (
              <li key={i} className="border-l-2 pl-3" style={{ borderColor: 'var(--border)' }}>
                <p className="text-sm font-medium">{e.what}</p>
                <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
                  {e.who} · {new Date(e.at).toLocaleString()}
                </p>
                {describeChange(e.before, e.after) && (
                  <p className="font-num text-xs tabular-nums">{describeChange(e.before, e.after)}</p>
                )}
              </li>
            ))}
          </ol>
        )}
    </aside>
  )
}
```

(Use whichever surface / border token names `tokens.css` actually defines — check it; `--text-muted` is confirmed.)

- [ ] **Step 5: `PayoutRegister.tsx`** — `/payouts/cases`:
  - Filters bound to the URL with `useSearchParams` (status `<select>` over `REGISTER_STATUS_LABEL`, search `<input type="search">`, from / to `<input type="date">`; expert filter via `?expertId=` when arriving from Experts).
  - Loads `fetchRegister(filter, signal)` in an effect with an `AbortController` (the `PayoutBatch` load pattern: ignore an aborted request).
  - Table columns: Case (link to `/cases/:caseId`), Expert, Amount (`formatPayout(amount, currency)` or "—"), Status chip (`REGISTER_STATUS_LABEL`), Set by (name + date), Due, Sent, Confirmed, Done (✓ with `aria-label="Done"`).
  - Row click (and Enter on a focused row) with an `offerId` → `fetchOfferHistory` → `OfferLogPanel` titled with the case code. Rows with no `offerId` (payout only) open nothing and say "No offer on record" in a tooltip.
  - "Export CSV" button → `exportRegister(filter)` → download as `payout-cases.csv` (reuse the blob-download helper `PayoutSummary` uses for *Export rows*).
  - Empty state: "No cases match these filters."; error state shows the message with a Retry button.
  - At 390px wide the table scrolls horizontally inside its own container (`overflow-x-auto`), the page itself never does.

- [ ] **Step 6: `ExpertBalances.tsx`** — `/payouts/experts`: `fetchExpertTotals`, table Expert · Committed · Pending · Processing · Paid · Oldest pending; sortable by clicking a header (local state `{ key, dir }`, `aria-sort` on the active header); row → `navigate(EXPERT_PAYOUTS_PATH.replace(':expertId', expertId))`; a secondary link per row "Cases" → `/payouts/cases?expertId=…`.

- [ ] **Step 7: `PayoutsOverview.tsx`** — `/payouts`: range menu (This week / This month / This year / Custom with two date inputs) computing `from`/`to` as ISO dates in the browser's local date; `fetchOverview(from, to)`; per currency, four tiles (Committed, Pending, Processing, Paid — count + `formatPayout`), each tile a link to `/payouts/cases?status=ACCEPTED|PENDING|PROCESSING|PAID`; a **Needs attention** list (case, expert, amount, "overdue since …" or "sent … — not confirmed after 7 days"); below, `<PayoutSummary />` unchanged.

- [ ] **Step 8: Run** `cd frontend && npx vitest run && npx tsc -b && npx oxlint` → all green

- [ ] **Step 9: Browser check** — start the stack the way `implementation-status.md` → Verify describes (backend on its local profile + `cd frontend && npm run dev`), sign in as the seeded ENM, and check `/payouts`, `/payouts/cases` (filter, search, open a log panel, export), `/payouts/experts` (sort, open an expert), `/payouts/pay` at 1440 and 390 wide with Chrome. Record what you saw; if the stack cannot start, say so rather than claiming it was checked.

- [ ] **Step 10: Commit**

```bash
git add frontend/src
git commit -m "feat(unit-65): payouts module — overview, cases register with log, experts, pay run"
```

---

### Task 9: Staff SPA — enter and see the fee where offers are made

**Files:**
- Modify: `frontend/src/features/board/boardRules.ts` (`assign-cm` ~L380, `reassign-expert` ~L545), `frontend/src/features/board/boardRules.test.ts`
- Modify: `frontend/src/features/case/ExpertCard.tsx`
- Modify: `frontend/src/features/board/QuickActionDialog.tsx` only if the `amount` input's `min="0.01"` must become `min="0"`

**Interfaces:**
- Consumes: `fetchCaseOffer`, `editOfferFee`, `MAY_SET_FEE`, `OfferLogPanel` (Tasks 7–8).

- [ ] **Step 1: Failing test** (`boardRules.test.ts`)

```ts
it('asks for an optional fee on every action that makes an offer', () => {
  for (const path of ['assign-cm', 'reassign-expert']) {
    const action = QUICK_ACTIONS.find((a) => a.path === path)!
    const fee = action.fields?.find((f) => f.name === 'fee')
    expect(fee?.kind).toBe('amount')
    expect(fee?.label).toMatch(/\(optional/)
  }
})
```

- [ ] **Step 2: Run** `npx vitest run src/features/board` → FAIL

- [ ] **Step 3: Add the field** to both actions' `fields`, after the expert picker:

```ts
      // Unit 65. Blank = the expert's standard fee; "(optional" is what keeps the dialog from
      // requiring it. The expert sees this amount before accepting, and it is final once they do.
      { name: 'fee', label: "Fee (optional — blank uses the expert's standard fee)", kind: 'amount' },
```

`performAction` already skips a blank field and sends `Number(raw)` for `amount`. Change the dialog's `min` for amount fields to `'0'` if a zero fee must be enterable (it must: the schema allows 0).

- [ ] **Step 4: `ExpertCard.tsx`** — below the expert's name block, when `detail.expertName` is set:
  - Load `fetchCaseOffer(caseId)` (id from `detail.summary.id` — confirm the field name in `CaseDetail`'s type).
  - Show **Fee for this case**: `formatPayout(fee, currency)` or "Not set", the outcome word (`Offered`, `Accepted`…), and "set by {name} on {date}" when present.
  - If `useMe().role` is in `MAY_SET_FEE` and `outcome === 'OFFERED'`: an **Edit** button → inline `<input type="number" min="0" step="0.01">` + Save / Cancel → `editOfferFee` → refresh. A 409 message is shown inline (the offer was answered meanwhile) and the card reloads.
  - A **History** button → `OfferLogPanel` with `offer.log`.
  - Loading and failure states are one muted line each; the card never blocks on them.

- [ ] **Step 5: Run** `cd frontend && npx vitest run && npx tsc -b && npx oxlint` → green

- [ ] **Step 6: Browser check** — as a PM: assign CM + expert with a blank fee and with a typed fee; open the case and see the fee; edit it; open History. As a CM: the case card shows the fee without Edit. Record results; say so if the stack could not be started.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat(unit-65): fee on assign and reassign; the case's expert card shows, edits and logs it"
```

---

### Task 10: Expert portal — see the fee, accept that fee

**Files:**
- Modify: `client-expert/expert/src/lib/expertCase.ts`, `client-expert/expert/src/lib/expertCase.test.ts`
- Modify: `client-expert/expert/src/services/expertPortalService.ts` (accept L62)
- Modify: `client-expert/expert/src/components/Answers.tsx`, `client-expert/expert/src/pages/NewCases.tsx` (L69), `client-expert/expert/src/pages/portal/ExpertCasePortal.tsx` (L193 and its fact strip)

**Interfaces:**
- Produces: `ExpertCaseSummary.offeredFee: number | null`, `.currency: string | null` (same on `ExpertCaseView`); `feeLine(fee: number | null, currency: string | null): string`; `accept(caseId: string, fee: number | null)`; `Answers({ caseId, fee, currency, onChanged })`.

- [ ] **Step 1: Failing test** (`expertCase.test.ts`)

```ts
import { feeLine } from './expertCase'

describe('feeLine', () => {
  it('names the amount in the brand currency', () => {
    expect(feeLine(350, 'USD')).toBe('Fee for this case: $350.00')
  })
  it('says when no fee is set yet', () => {
    expect(feeLine(null, 'USD')).toBe('Fee not set yet')
  })
})
```

- [ ] **Step 2: Run** `cd client-expert && npx vitest run expert/src/lib/expertCase.test.ts` → FAIL

- [ ] **Step 3: Implement**

`expertCase.ts`: add `offeredFee: number | null` and `currency: string | null` to both `ExpertCaseSummary` and `ExpertCaseView` types, and:

```ts
/** What the expert is offered for a case (Unit 65) — shown before they can accept. */
export function feeLine(fee: number | null, currency: string | null): string {
  if (fee === null || !currency) return 'Fee not set yet'
  const amount = new Intl.NumberFormat('en-US', { style: 'currency', currency, minimumFractionDigits: 2 }).format(fee)
  return `Fee for this case: ${amount}`
}
```

(If `expertCase.ts` or `lib/dashboard.ts` already has a currency formatter, use it instead of a second one.)

`expertPortalService.ts`:

```ts
/** Accepting sends the fee the expert was shown; a changed fee answers 409 (Unit 65). */
export async function accept(caseId: string, fee: number | null): Promise<ExpertCaseView> {
  const params: Record<string, string | number> = { caseId }
  if (fee !== null) params.fee = fee
  return unwrap(apiClient.post<ApiResponse<ExpertCaseView>>('/expert/accept', undefined, { params }))
}
```

`Answers.tsx`: props become `{ caseId, fee, currency, onChanged }`; render `<p className="font-medium">{feeLine(fee, currency)}</p>` above the three buttons; the Accept button is `disabled={fee === null}` with the title "The fee for this case is not set yet"; the accept call is `accept(caseId, fee)`. On a failed accept whose message contains "fee", show the message and call `onChanged()` so the page reloads with the current fee (read how `act(...)` surfaces errors and hook into it rather than adding a second error path).

`NewCases.tsx`: `<Answers caseId={c.caseId} fee={c.offeredFee} currency={c.currency} onChanged={onAnswered} />`.
`ExpertCasePortal.tsx`: pass `fee={view.offeredFee} currency={view.currency}` (use the page's actual variable name for the loaded view) and add a fact to the fact strip: label "Agreed fee" / "Offered fee" (accepted vs not), value `formatted amount` or "Not set yet".

- [ ] **Step 4: Run** `cd client-expert && npx vitest run && npx tsc -b -p expert && npx oxlint` → green (use the workspace's documented typecheck command if different — see `client-expert/package.json`).

- [ ] **Step 5: Browser check** — sign in to the expert portal as a seeded expert with an open offer: the fee shows on `/new` and on the case; Accept works; change the fee as staff in another window, press Accept → the changed-fee message and the reload show the new fee. At 390 wide, the fee line and buttons wrap without overflow.

- [ ] **Step 6: Commit**

```bash
git add client-expert
git commit -m "feat(unit-65): the expert portal shows the fee and accepts exactly that fee"
```

---

### Task 11: Docs, memories and the full verification

**Files:**
- Modify: `context/specs/65-case-fee-and-payouts-module.md` (status line → BUILT), `.claude/implementation-status.md` (Payments row + Next up item 7 + build state), `.claude/current-decisions.md` (D59 "specced, not built" → built), `.claude/data-model.md` (move the V79 entry from REQUIRED FUTURE into CURRENT: `expert_case_offer` row), `.claude/workflows.md` (§9: TARGET's Unit 65 block becomes CURRENT), `.claude/project-context.md` (only if it lists payout screens), `.serena/memories/{implementation_status,current_decisions,data_model,workflows}.md`

- [ ] **Step 1: Run every suite**

```bash
cd backend && ./mvnw -q test
cd ../frontend && npx vitest run && npx tsc -b && npx oxlint
cd ../client-expert && npx vitest run && npm run build
```

Expected: all green. Record the exact counts (backend `N tests, 0 failures, 0 errors, 4 skipped`; staff SPA `N tests`; portals `N tests`).

- [ ] **Step 2: Update the docs** — an **edit** to each statement that described the old state, never a note beside it:
  - `implementation-status.md` Payments (expert payouts) row: add the Unit 65 evidence (files, endpoints, tests by name from Tasks 1–10), the browser checks actually done, and remove "correct-amount" wording; Next up item 7 → struck through "Built 2026-MM-DD"; build-state line with the new counts.
  - `current-decisions.md` D59: "(Unit 65 … specced, not built)" → "(Unit 65 … built YYYY-MM-DD)".
  - `data-model.md`: the `expert_case_offer` row in CURRENT gains `fee` / `fee_set_by` / `fee_set_at` (`V79`); delete the Unit 65 bullet from REQUIRED FUTURE.
  - `workflows.md` §9: move the Unit 65 block into CURRENT IMPLEMENTATION, replacing the lines it supersedes (payout PENDING (fee) → accepted fee; `/payouts` batch → module screens); TARGET keeps only the "Not built by choice" list.
  - Spec 65 status line: `**Status: BUILT YYYY-MM-DD** (branch feature/unit-65-case-fee-payouts)`.
  - The four Serena memories: the same edits, concise.

- [ ] **Step 3: Commit**

```bash
git add context .claude .serena
git commit -m "docs(unit-65): built — status, decisions, data model, workflows and memories"
```

- [ ] **Step 4: Restart note** — tell the user the backend must be restarted to apply `V79` (and `V914` on the local profile), and list which browser checks were and were not done.
