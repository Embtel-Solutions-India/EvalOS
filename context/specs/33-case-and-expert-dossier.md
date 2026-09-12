# Unit 33 — The full record: applicant, case discipline, and the expert dossier

> **Status: BUILT 2026-09-03** (`V35`, backend, frontend, 532 backend tests + 141 frontend tests
> green, `ddl-auto=validate` passing against a real Postgres). Two things the build surfaced that
> this spec did not predict are recorded in the tracker: the importer's number parser had to be
> **narrowed**, not widened, and the edit form would have blanked the whole dossier on save.
>
> **Originally: SPECCED 2026-09-03.** Written before code because it widens two tables every
> other unit reads and reverses one documented omission.
>
> **What it changes in one line:** EvalOS currently stores **14 of the 36 facts** the
> business keeps about an expert and is **missing the applicant's name** on every case that
> is not a B2C individual. This unit closes both gaps, so the record EvalOS holds is the
> record the business holds — the list stays lean, the detail view shows everything.
>
> **This reverses a documented decision** (`12-match-scoring-engine.md`: a case carries no
> field tag). §3 says why that decision was right when it was taken and is wrong now.

**Phase:** 2 — Connect the seams
**Depends on:** 11 (expert database + import), 12 (match scoring), 31 (lifecycle v2)
**Amends:** 03, 09, 11, 12, 22
**Source of truth for the field list:** `IE_Case_Sample_Data.xlsx` and
`IE_Expert_Sample_Data.xlsx` (28 and 36 columns; sample rows fictional, headers are not).

---

## 1. What the audit found

Both sheets were checked column by column against V1–V34, the entities, `ExpertForm`,
`ExpertImportService` and `ExpertMatchService`.

**`drive_link` is the one column correctly ignored.** V34 dropped it; documents are S3 keys
in `case_document` (Unit 30). It comes out of the sheet, not into the schema.

**Cases — 5 gaps in 28 columns.** Everything else maps: `case_code`, `created_at`,
`client_type`, `contact_snapshot.*`, `service_type`/`service_subtype`, `visa_category`,
`assigned_cm`, `expert_id`, `current_stage`, `pool_status`, `delivery_date`, `deal_value`,
`invoice_ref`, `pm_strategy_notes`.

**Experts — 19 of 36 columns have no column, no entity field and no import target.** The
whole standing block: degree, position, affiliation type, location, years of experience,
LinkedIn, supported visa categories, publications, citations, h-index, patents, awards,
memberships, editorial roles, languages, rush capability, human id, turnaround.

That block is not decoration. **It is the evidence an expert opinion letter rests on, and
the whole basis on which an Expert Network Manager prefers one expert to another.** A
roster that knows an expert's tier but not their h-index cannot support the decision it
exists to support.

---

## 2. The applicant is not the contact

`contact_snapshot` holds the person GHL sent us — the attorney, the agent, the HR contact.
For `client_type = INDIVIDUAL` that person is also the beneficiary. For `ATTORNEY`,
`EMPLOYER`, `AGENT` and the new `NACES` they are **not**, and the letter is about someone
whose name EvalOS does not currently store anywhere.

**`evalos_case.applicant_name text`, and not on the snapshot.** Invariant 7 makes the
snapshot a read-only copy of a GHL record; the applicant is not a GHL fact — Handoff A's
confirmed payload is a contact record and carries no beneficiary (see the 2026-09-02 tracker
entry). It is typed by the Case Manager at intake, prefilled from the contact's name when
the client type is `INDIVIDUAL`. Nullable, because a case can be created before the
documents that name the applicant arrive.

---

## 3. The case gets a field tag, and why that is not a reversal of the argument

`ExpertMatchService` records the omission deliberately: *"the tag is an argument here rather
than a column because the PM has just read the documents… a column would have to be filled
at intake by a GHL webhook that carries no such thing, and would then be a stale guess."*

**Every word of that is still true, and it argues against an intake column — not against
storing what the PM typed.** The objection was to a *source*, not to persistence. The PM
already supplies the tag at match time, from knowledge they have at exactly that moment;
today it is used once to rank a shortlist and then discarded, so a delivered case cannot
answer "what discipline was this?" — which is the question the sheet's `field_of_expertise`
exists to answer, and the question a second EOL for the same client starts from.

**`evalos_case.field_of_expertise text` (a `FieldTag` name), written by the match action,
never by Handoff A.** The closing condition the original note set — *"if a later unit finds
a second consumer, the column can be added then, with a real source"* — is met: the consumer
is the case record and the ENM's repeat-match, and the source is the PM's own input.
`ExpertMatchService` keeps taking the tag as an argument; the assignment writes it. It stays
nullable, and a null means *no match has been run*, never *no discipline*.

---

## 4. The RFE date is a second deadline, not the same one

`deadline` drives `DeadlineRisk` and the SLA — it is what EvalOS promised. `rfe_date` is
what USCIS imposed. They coincide on most cases and diverge on exactly the cases that
matter. **`evalos_case.rfe_date date`**, nullable, displayed on the case detail and feeding
nothing automatically: the Case Manager sets `deadline` from it, and a rule that did so
silently would be a rule nobody could see was wrong.

**`priority` and `urgency` stay out.** Unit 32 decision 5 refused an urgency column because
the deadline already expresses urgency and a second field would drift from it. That holds
here — both sheet columns are readings of a date we now store twice, and the sheets should
drop them rather than the schema gain them.

---

## 5. The expert dossier — one wide table, not a child table

Nineteen columns are added to `expert`. **No `expert_credentials` side table:** the
relationship is 1:1, there is no history to keep and no second implementation, so a child
table would be a join added for symmetry. Widening is the smaller diff and the smaller read.

| Column | Type | Note |
|---|---|---|
| `expert_code` | `text` | The sheet's `IE-EXP-###`. **Unique per brand.** This is the join key between the two sheets — without it `evaluator_id` cannot be resolved on import. |
| `sub_specialization` | `text` | Free text ("Power Systems & Smart Grids"). The real matching signal, and deliberately *not* an enum — the niche is where a closed vocabulary would immediately be wrong. `secondary_fields` stays the enum. |
| `highest_degree` | `text` | PhD / MD / ScD / PharmD / MS. Free text: the sheet already carries five spellings and a fixed list buys no query. |
| `degree_field`, `degree_institution` | `text` | Where the terminal degree came from. Distinct from `institution`, which is where they work **now**. |
| `current_position` | `text` | Job title today. |
| `affiliation_type` | `text` | `AffiliationType` name — `UNIVERSITY`, `INDUSTRY`, `NATIONAL_LAB`, `GOVERNMENT`, `INDEPENDENT`. Closed, because the ENM filters on it. |
| `country`, `state_region` | `text` | US-based is preferred for USCIS letters, so it is a filter. |
| `years_experience` | `int` | Seniority. |
| `linkedin_url` | `text` | Verification link. |
| `visa_categories` | `text[]` | `VisaCategory` names, CHECK-constrained like `letter_types`. **Not the same fact as `letter_types`** — that is the deliverable, this is the petition it supports. |
| `publications`, `citations`, `h_index`, `patents` | `int` | The standing metrics. |
| `notable_awards`, `professional_memberships`, `editorial_roles` | `text` | Narrative, comma-separated as the sheet has them. Free text and not arrays: nothing queries inside them, and three GIN indexes for a detail-view render is cost with no reader. |
| `languages` | `text` | Same reasoning. <!-- ponytail: free text; make it text[] with a GIN index the day a screen filters by language --> |
| `rush_available` | `boolean NOT NULL DEFAULT false` | Can take a 48-hour rush. |
| `avg_turnaround_days` | `int` | Typical days to complete a letter — the ENM's figure. Distinct from `avg_response_hours`, which is time to *answer an offer*. |

**Two facts in the sheet are refused as columns.**

- **`last_active_date` is derived, not stored.** `max(offered_at)` over `expert_case_offer`
  is the same fact with no writer to forget. A stored column would need updating from every
  path that touches an expert and would be wrong the first time one didn't.
- **No payment column, still.** `ExpertImportService` already refuses `paymentDetail` as a
  mapping target and the sheets carry no bank field. Unchanged, and re-stated because this
  unit widens the import surface.

---

## 6. Vocabularies

`FieldTag`'s 28 values were drawn for **credential-evaluation degree fields**. The expert
roster is an **expert-opinion-letter** roster, and 10 of the 22 disciplines in the sheet have
no tag at all — an expert whose field cannot be spelled scores zero on a 40-point factor.

- **`FieldTag` +=** `AEROSPACE_ENGINEERING`, `ARTIFICIAL_INTELLIGENCE`,
  `BIOMEDICAL_ENGINEERING`, `BIOTECHNOLOGY`, `CYBERSECURITY`, `ENVIRONMENTAL_ENGINEERING`,
  `MATERIALS_SCIENCE`, `NEUROSCIENCE`, `PHARMACOLOGY`, `RENEWABLE_ENERGY_ENGINEERING`,
  `SOFTWARE_ENGINEERING`.
  *Applied Mathematics → `MATHEMATICS` and Clinical Medicine → `MEDICINE` are correct as they
  stand; `PHARMACOLOGY` is added beside `PHARMACY` because they are a science and a practice.*
- **`ServiceType` +=** `RECOMMENDATION_LETTER`, `WAGE_LEVEL_LETTER`.
- **`LetterType` +=** the matching two, and **`ExpertMatchService.LETTER_FOR_SERVICE` gains
  both entries** — an unmapped service type silently makes every expert ineligible.
- **`ClientType` +=** `NACES`.
- **`VisaCategory` +=** `L1A`, `EDUCATION`, `EMPLOYMENT`, `ADMISSION`. The last three are the
  NACES purposes; they currently collapse into `OTHER`, which loses the distinction the
  credential-evaluation side of the business runs on.

**`Availability` is unchanged** — the sheet's Active/Inactive already map onto `AVAILABLE`
and `INACTIVE`.

**The V18 CHECK constraints are extended in the same migration as the enums.** The
vocabulary lives in two places on purpose (V18: the enum stops a caller, the CHECK stops a
seed script), and the failure mode of updating one is a row the application can write and
the database rejects.

---

## 7. Surfaces

**The rule for both screens: the list stays lean, the detail shows everything.** Nothing is
added to a table row or a board card.

- **`ExpertProfile` (view mode)** gains two fact groups beside the existing six —
  **Credentials** (degree, degree field, degree institution, position, affiliation type,
  years) and **Standing** (publications, citations, h-index, patents, awards, memberships,
  editorial roles) — plus location and languages in Contact, and `rush_available` in
  Availability. Edit mode gains the same fields. The roster table is untouched.
- **`CaseDetail`** shows `applicant_name`, `field_of_expertise` and `rfe_date`. The applicant
  sits beside the contact and is labelled as the beneficiary, because the two being
  confusable is the whole reason the column exists.
- **`Timeline`** must render the three milestone dates the sheet keeps as columns —
  documents complete, sent for review, sent for signature. **No new columns:** they are
  `STAGE_CHANGED` rows in `audit_event`, which is append-only truth. If the timeline already
  shows them, this is a verification step and nothing more.

**`payment_status`'s Invoiced and Overdue stay in GHL.** EvalOS never invoices (scope rule);
`paid`/`paid_at` is the whole of what EvalOS is entitled to know, and the sheet's third state
belongs to the system that issues the invoice.

---

## 8. Import

`ExpertImportService.TARGET_FIELDS` is derived reflectively from `ExpertForm`'s record
components, so **every new field becomes a mappable sheet column for free.** The work is the
new components on `ExpertForm`, the matching positional arguments in `candidate(…)`, and
`number`/`tags`/`single` parsers for the typed ones. Both sample sheets must import clean
with a header-to-field mapping and no row problems — that is this unit's acceptance test.

---

## 9. Acceptance

1. A case created for an `ATTORNEY` client stores an applicant name distinct from the contact.
2. Running a match writes `field_of_expertise` onto the case; the case detail shows it after
   delivery.
3. All 28 rows of `IE_Expert_Sample_Data.xlsx` import with zero row problems, and no sheet
   column is left without a target field to map onto.
4. All 25 rows of `IE_Case_Sample_Data.xlsx` are representable: every `customer_type`,
   `service_type` and `purpose_visa` value resolves to an enum constant, and `evaluator_id`
   resolves to an expert by `expert_code`.
5. `ExpertProfile` view mode shows every sheet fact except the two derived/refused; the
   roster table gained no column.
6. `ddl-auto=validate` passes — every CHECK vocabulary matches its enum.

**The sheets are edited too, and only where EvalOS has decided not to store something:**
`drive_link`, `priority` and `urgency` come out, with the reason recorded in the Field guide
tab so the next reader does not re-add them.
