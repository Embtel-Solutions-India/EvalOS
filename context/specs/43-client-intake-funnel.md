# Unit 43 — Get Started: the client intake funnel

> **Status:** specced 2026-09-11, not built.
> **Depends on:** 42 (the account this funnel creates), 30 (S3 uploads), 37 (`GhlWriteClient`).
> **Restores:** the seven-screen funnel and the conditional questionnaire deleted in `f9f1165`.

> **This unit reverses `34-portal-frontend-wiring.md` D2**, which recommended cutting the funnel:
>
> > *"It is good work and it is **front of house**, which EvalOS does not do. Wiring it would
> > create a case outside `opportunity.won`... `serviceCatalog.ts` and `questionGroups.ts` are
> > worth keeping either way; they are data, not workflow."*
>
> D2's *reasoning* was right and is preserved: **this funnel still creates no case.** What
> changed on 2026-09-11 is the business decision that EvalOS is the front of house for clients,
> which `00b` already made for Sales and Marketing. D2's closing sentence — that the catalog and
> the question groups are data worth keeping — is what makes this unit mostly a restoration.

---

## 1. What changes, in one paragraph

A visitor who clicks **Get started** on the welcome screen works through seven steps: welcome,
choose a service, state a purpose, give their details (which creates their account), answer a
questionnaire whose questions depend on what they picked, upload the documents that service
needs, and review and submit. Submitting writes an EvalOS-owned `client_application`, then
pushes a contact and an opportunity into GHL and files the answers as a note. Sales picks it up
from there. **No case is created** — the case is still born only of a won opportunity, which is
still `opportunity.won` arriving on the webhook.

---

## 2. Most of this already exists, in git

Commit `f9f1165` ("34d — the client portal has no mock left in it, and no account") deleted the
whole funnel. It was deleted for a good reason at the time — every screen imported `useAuth` and
`authService`, so when the account shell went, the funnel no longer compiled, and *"code that
cannot compile is not parked, it is broken"*. Unit 42 brings the account back, which makes the
funnel compilable again.

Recover from `f9f1165^`, do not rewrite:

| File | Lines | What it is |
| --- | --- | --- |
| `constants/serviceCatalog.ts` | 235 | 3 categories → services → which question groups and document templates each needs |
| `constants/questionGroups.ts` | 205 | reusable groups: education, professional background, translation details, … |
| `lib/questionnaire.ts` | 23 | the conditional engine |
| `types/intake.ts` | — | `QuestionDefinition`, `QuestionGroup`, `RequestAnswers`, `ServiceDefinition` |
| `components/intake/*` | — | `QuestionField`, `IntakeProgress`, `ServiceCard`, `IntakeDocumentCard` |
| `pages/intake/*.tsx` | ~900 | the seven screens |
| `schemas/intake.ts` | 31 | **never deleted** — still on disk, password rules included |

**What must change on the way back in**, and it is a short list: every `authService` /
`useAuth` import becomes Unit 42's client, `intakeService`'s `localStorage` draft becomes a
server-side `client_application` row, and `FillDemoDataButton` / `constants/demoData.ts` do not
come back.

---

## 3. The conditional questionnaire, unchanged

The engine is 23 lines and it is the right 23 lines:

```ts
getVisibleGroups(serviceId, answers)   // groups for this service, minus those whose showIf fails
getVisibleQuestions(group, answers)    // questions in a group, minus those whose showIf fails
isGroupComplete(group, answers)        // every visible required question answered
```

A `showIf` is a predicate over the answers so far, so attorney questions appear only for a
client working with an attorney and employer questions only for an employer-type client,
**with no per-service component anywhere**. Adding a service is an entry in `serviceCatalog.ts`
pointing at group ids. No screen changes.

**`clientType` is seeded into the answers pool from the account, not only held on it.** The
deleted `Questionnaire.tsx` did this deliberately and the comment explains why: groups whose
`showIf` reads `answers.clientType` cannot see a value that lives only on the account record.
Preserve that seeding; it is the one non-obvious line in the screen.

**The engine stays on the client and the server re-checks.** Required-ness is a display rule
the client evaluates to decide what to render; `POST /applications/{id}/submit` re-runs
`isGroupComplete` server-side over the same catalog and refuses an incomplete submission. A
visibility rule enforced only in a browser is not enforced.

**That means the catalog is shared, not duplicated.** `serviceCatalog.ts` and
`questionGroups.ts` become the seed for a server-side copy, and a test asserts the two agree on
service ids, group ids and required flags. Two hand-maintained copies of the same catalog is
the failure this unit is most likely to ship.

---

## 4. The account is created at step 4, not at the end

**About You creates the `client_account`** (Unit 42's table) with email, password and terms —
the fields `schemas/intake.ts` already validates. Steps 5–7 then run signed in.

This is the one substantive UX change from the deleted version, and the argument is resumption:
a client who abandons on the documents step has, by then, chosen a service, answered fifteen
questions and typed their history. If the account is created at submit, all of that is in
`localStorage` on one browser and is gone when they come back on their phone. Creating it at
step 4 means the answers live on a server row keyed to an account, and signing in again lands
them exactly where they stopped.

**So the dashboard gains one state**: an application in progress, with a *Continue* button. A
signed-in client with a `DRAFT` application and no case sees that instead of an empty portal.

---

## 5. Documents

Uploads go through the route Unit 30 already built, streaming to
`{brandId}/client/{clientAccountId}/{documentId}`.

**Keyed on the EvalOS account id, not on `ghl_contact_id`** — which is the prefix Unit 30
documents. At this point in the funnel there is no GHL contact yet: it is created at submit,
one step later. Keying the prefix on an id that does not exist until after the files are
written is not possible, and keying it on the account is also what keeps the documents readable
if the contact is later deleted or the CRM replaced. `00c` inherits this as the pattern.

Which documents are asked for comes from the service's `documentTemplates`. Nothing is
mandatory to submit: a missing document is a thing Sales chases, not a wall the funnel puts in
front of a lead.

---

## 6. Submit

```
POST /api/portal/applications                   create a DRAFT   (step 4, at account creation)
PUT  /api/portal/applications/{id}              save answers     (steps 5–6, autosaved)
POST /api/portal/applications/{id}/submit       the one that writes to GHL
```

Submit does four things in this order:

1. **Re-validate** the answers server-side against the catalog (§3). Refuse incomplete.
2. **Mark the application `SUBMITTED`** in EvalOS, and commit. This happens *before* GHL is
   touched, and that ordering is the point of §7.
3. **`GhlWriteClient.upsertContact`**, storing the returned id on `client_account.ghl_contact_id`.
4. **`GhlWriteClient.upsertOpportunity`** on the intake pipeline, then file the answers as an
   `OpportunityNote` so Sales reads them where they work.

**`MarketingLeadService.openLead` is NOT reused, and this is the correction that matters.**
It looked like the obvious call — it is exactly these two upserts in exactly this order — but
its first line is `scope.mine()`, which reads `TenantContext.current().ghlPipelineId()`: the
**staff member's** assigned pipeline. A portal request carries a `PortalPrincipal` and no
`TenantContext`, so `openLead` throws `ForbiddenException("You have no GHL pipeline assigned")`
on every submission. `PipelineScope` is Unit 36's staff-authorization model and **none of it
applies on the portal chain**, where there are no roles at all.

**The pipeline is fetched, not assigned.** `GhlPipelineClient.pipelines()` already lists the
location's pipelines; the intake pipeline is matched by name from
`evalos.ghl.intake-pipeline-name`, the same by-name convention the other three pipeline
settings use and for the same reason — the id is opaque and the name is what whoever
provisions GHL can see. A name that does not resolve fails the submit loudly rather than
filing the lead somewhere nobody looks.

---

## 7. GHL is downstream of the truth, not the truth

**Step 2 commits before steps 3 and 4 run.** If GHL is unreachable, rate-limited, or
mis-provisioned, the application is still `SUBMITTED` in EvalOS, the client still sees
"Submitted — under review", and their documents are still in S3. `ghl_contact_id` and
`ghl_opportunity_id` stay null and a retry fills them in.

This inverts the order every other GHL write in EvalOS uses, and it is deliberate. Elsewhere a
staff member is at a keyboard and can retry a failed write themselves. Here the client has
finished and closed the tab, and **"your submission was lost because a third party was down"
is not a sentence this business can send.**

**Both GHL calls are upserts**, so a retry matches the existing contact rather than duplicating
it — which `39`'s spec already established is the recoverability argument for upsert over
create, not a tidiness one. Retry is a staff action on the application row; no automatic
retry loop, consistent with *"writes do not retry"* (invariant 2), and for the same reason:
EvalOS has no idempotency key scheme, and a blind retry is how one opportunity becomes two.

---

## 8. Data model

```sql
client_application
  id uuid pk, brand_id uuid not null,
  client_account_id uuid not null,
  service_id text not null, purpose text,
  status text not null,               -- DRAFT | SUBMITTED | QUALIFIED | WITHDRAWN
  answers jsonb not null default '{}',
  ghl_opportunity_id text null,       -- a LINK, filled after submit, nullable forever
  created_at, updated_at, submitted_at
```

**`answers` is `jsonb`, not a table per question.** The shape is defined by the catalog and
changes whenever a service is added; a normalised `application_answer` table would add a join
and a migration per catalog edit to store what is read whole, every time, by one screen.

**`status` has four values and none of them is a case stage.** An application is not a case and
must never grow a lifecycle that looks like one — that is how a second case state machine gets
built by accident. `QUALIFIED` means Sales accepted it and sent an invoice; the case appears
later, through Handoff A, and is a different row entirely.

---

## 9. Invariant impact

**Invariant 8 — untouched, and this is the invariant this unit is most suspected of breaking.**
Invariant 8's own text names this funnel as *"the first real pressure"* on it. The pressure is
answered rather than pushed through: `client_application` is **not** a case, holds no case
stage, gets no `case_code`, and `ClientApplicationService` has no dependency on
`CaseIntakeService` — which `DomainInvariantsTest` already enforces structurally and which this
unit must not weaken. A case still appears only when `opportunity.won` arrives on the webhook.

**The order of events is worth writing down, because it is the whole design:** client submits →
EvalOS application → GHL opportunity → Sales qualifies → GHL invoice → client pays → GHL fires
`opportunity.won` → **Handoff A creates the case, paid.** Payment is after submission, and
qualification is a human step by a salesperson.

**Invariant 1 — held.** `client_application` is brand-scoped; every query filters `brand_id`.

**Invariant 7 — as amended by Unit 42.** EvalOS asks GHL to create a contact and stores the id
it returns; it still mints none.

**Unit 36's role model is not involved.** No `SALES` or `MARKETING` role, no `Segment`, no
`Tier.PIPELINE`, no `PipelineScope`, no `evalos.ghl.sales-brand`. Stated explicitly because
§6's two GHL writes look exactly like the marketing desk's and the reflex is to reuse its
service.

---

## 10. What this unit deliberately does not do

- **No payment.** The client pays a GHL invoice link Sales sends. Invoicing is still GHL's
  (invariant 2's surviving half), and Unit 41 reads invoices without offering a pay action.
- **No pricing or quoting in the funnel.** A service's price depends on turnaround and document
  count and is a sales conversation.
- **No automatic qualification.** A human salesperson decides. There is no rules engine here.
- **No status email.** The portal shows the state; Unit 42's mail channel is for authentication
  only and §4 of that spec is where that boundary is drawn.
- **`FillDemoDataButton` and `demoData.ts` do not come back.** They were scaffolding for a mock
  funnel and this one reaches a real CRM.

---

## 11. Acceptance criteria

1. A visitor completes all seven steps and the application reaches `SUBMITTED`, with a contact
   and an opportunity visible in GHL location `WY6bW2xUCI8Tz8gw7aLJ` and the answers on a note.
2. Abandoning at step 6 and signing in again from a different browser resumes with the answers
   intact.
3. Choosing a service whose groups include a conditional one shows those questions only when
   the `showIf` holds — asserted on the engine, not through the DOM.
4. A submit missing a required visible answer is refused **by the server**, with the client-side
   check disabled in the test.
5. A test fails if the server catalog and `serviceCatalog.ts` disagree on service ids, group ids
   or required flags.
6. With GHL unreachable, submit still returns 200, the application is `SUBMITTED`, and both GHL
   id columns are null.
7. Submitting twice does not create a second contact or a second opportunity.
8. `DomainInvariantsTest` still fails the build if `ClientApplicationService` reaches
   `CaseIntakeService`.
