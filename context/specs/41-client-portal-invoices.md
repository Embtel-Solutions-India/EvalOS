# Unit 41 — Invoices and payments in the Client Portal

> **Status: BUILT and EXERCISED LIVE 2026-09-11.** `invoices.readonly` turned out to be
> **already granted** — a probe with the app's own token returned HTTP 200 and 212 real invoices
> for the location. A party-scoped portal credential for GHL contact `lF6a5leuKo7GMBLGz60F`
> returned two genuine paid invoices (INV-1220 $700, INV-1127 $1,648) through
> `GET /api/portal/client/invoices`. No code changed. Programme decisions:
> `00b-ghl-operational-programme.md`.
>
> **How the "ungranted" claim survived so long:** the scope was recorded as an ask when the unit
> was specced and never re-probed after the build. A 401/403 was assumed, never observed. The
> lesson is cheap and worth keeping — **probe the grant before writing "blocked" in a doc**, and
> distinguish a 422 (auth passed, params wrong) from a 401/403.
>
> **Independent of Units 36–40.** This unit needs nothing from the pipeline access model, the write
> door, the boards or the desks. It needs **Unit 35, which shipped**, and **one OAuth scope, which
> has not been granted**. Run it in parallel the moment that scope lands.

**Phase:** 3 — EvalOS as the operational system
**Depends on:** 35 ✅ (the party-scoped portal credential)
**Unlocks:** nothing
**Migration:** none
**Gating open questions:** none; **blocked on `invoices.readonly`** (§2)

---

## 1. What changes, in one paragraph

A client opens their portal and sees **their invoices and payment status**, alongside the cases
Unit 35 already gives them. EvalOS reads `GET /invoices/?contactId=…` and projects a **named
whitelist** of fields. Nothing is stored, nothing is written, and QuickBooks is never contacted —
GHL's existing integration does the accounting and EvalOS reads the outcome.

## 2. Why this is the cheapest unit in the programme

`GET /invoices/` filters by **`contactId`** and `status`. Unit 35 shipped a portal credential that
names a **`ghl_contact_id`** — `V38` made `case_id` nullable and added the column precisely so a
credential could name a party rather than a case.

**The key the portal already holds is the key the invoice API wants.** There is no mapping, no new
identity, and no lookup: the credential's contact id goes straight into the query parameter.

That is also the security property. The contact id is **matched from the credential, never from
the request** — the same rule Unit 34c's document filter and Unit 35's case routes follow. A client
cannot ask for another contact's invoices because they cannot name a contact at all.

**The one blocker is the grant.** `invoices.readonly` is not in the current scope set
(`opportunities.write` + `contacts.write`). It belongs in `00-build-plan.md`'s Step 0 table.

## 3. The route

| Method | Path | Answers |
| --- | --- | --- |
| GET | `/client/invoices` | every invoice for the credential's contact, newest first |

**No `{id}` route in this unit.** A client with three invoices reads three rows on one screen;
a detail route would be a second scope check protecting nothing the list does not already show.
Add it when there is a field worth a screen of its own.

**A party credential is required.** A case-scoped credential (Unit 35's other kind) answers
**403** — it names a case, and an invoice belongs to the client, not the engagement. Consistent
with Unit 35's rule that a wrong-scope read is 403 and never 404, so nothing here is an oracle.

## 4. The field whitelist

Named, not derived — the pattern Units 14, 15 and 35's D6 all use, and asserted **on the produced
JSON** rather than on a field list, because a nested DTO passes a field-name check and still leaks.

| Field | Why |
| --- | --- |
| invoice number | the client's reference when they call |
| issue date, due date | what they need to act |
| amount, currency | the figure |
| status | paid / unpaid / partially paid / void |
| amount paid, amount due | the reason a client opens this screen |

**Not carried:** internal ids beyond the invoice number, GHL's raw payload, line-item cost
structure, anything naming a team member, and anything about other contacts. **A GHL response is
not a DTO** — the projection is explicit and the test asserts what is absent, not only what is
present.

## 5. Nothing is stored

No table, no cache, no `V`-migration. Invoices are read live per request.

**The Unit 38 arithmetic does not apply here.** That cache exists because a board is ~115 cursor
pages; one client's invoices are one page. The rate limiter in `GhlHttp` already paces the call,
and a portal read is not a dashboard sweep.

**So `00b` §1.3's "GHL is truth" holds here in its strongest form:** EvalOS holds no invoice fact
at all, and invariant 2's surviving clause — *invoicing is GHL's, full stop* — is untouched by
this unit even though it is the unit named "invoices".

## 6. Invariant impact

- **2** — **untouched by this unit.** EvalOS raises no invoice, computes no total, and touches no
  accounting. Reading a figure is what Units 24/26/27 already do.
- **5** (revenue recognition = paid **and** delivered) — **untouched, and the trap is worth
  naming.** A GHL invoice marked paid is **not** revenue recognition, and this screen must not be
  read as such by any later dashboard. `RefundService.isRevenueRecognized` remains the only reader
  of that question. A "paid" invoice here is a fact about the client's bill, not about the case.
- **7** — `ghl_contact_id` is again the canonical client identity, used exactly as the invariant
  says.
- **13** — a portal read writes no audit row; `recordPortalEvent` exists for portal **actions**,
  and there is no action here.

## 7. What this unit deliberately does not do

- **No invoice creation.** Sales raises invoices in GHL (Unit 40 §5).
- **No QuickBooks.** GHL's integration owns it; EvalOS never sees it.
- **No payment taking.** The portal displays status; paying happens through GHL's own link.
- **No refund surface.** Refunds are GM-approved in EvalOS already and are a different question
  (invariant 5).
- **No storage** (§5).

## 8. Acceptance criteria

- [x] A party credential reads its own contact's invoices; the contact id comes from the
      credential and a contact id in the request is ignored or refused.
- [x] A case-scoped credential answers **403**, not 404 and not an empty list.
- [x] Another contact's invoice is unreachable by any request this portal can make.
- [x] The response JSON contains exactly the §4 whitelist — asserted on the serialized body, so a
      nested DTO cannot smuggle a field through.
- [x] Multiple invoices for one contact are all listed, with per-invoice status.
- [x] No table, no migration, no cached invoice row exists after a portal read.
- [x] Absent the `invoices.readonly` grant the route answers **502** through
      `GhlUnavailableException`, with a message naming the missing scope — the failure a
      provisioner can act on, following `GhlHttp`'s existing diagnostic reasoning.
- [x] `./mvnw verify` green; the client portal app builds.


## 9. What the build found

**The block was on exercising the code, not on writing it** — the same distinction the build plan
already draws for the AWS credential. Every acceptance criterion above is met against a stub GHL;
what waits on `invoices.readonly` is a live call, and the unit was specced from the start to
answer **502 naming the missing scope** in exactly this state.

**The missing-grant diagnostic earns its place.** Every *other* GHL-backed screen in EvalOS works
on the current token, so a 401 here reads as "the token is broken" when it means "the token is
missing one scope that nothing else uses". `GhlInvoiceClient.missingScopeHint` decorates 401 and
403 with the grant name — **and only those two**: a guard that fires on 404s and timeouts too
would teach the reader to ignore it, so `anUpstreamFaultIsNotBlamedOnTheScope` pins that it does
not.

**One API detail worth knowing, and it is not consistent with the rest of GHL.** The invoice
endpoint addresses the sub-account with **`altId` + `altType=location`**, not with `locationId`
like the opportunity endpoints. Pinned in `GhlInvoiceClientHttpTest`, because getting it wrong is
a 422 and nothing else — the same class of mistake that cost this codebase a live afternoon on
`pipeline_stage_id`.

**The 403 is explained on screen rather than left generic.** A case-scoped link cannot show
billing, and "something went wrong" would send the client to support for something a different
link fixes. The portal page says so in their own terms: *"This link opens one case rather than
your account."*

**Two portal test slices needed a mock bean**, because `ClientPortalController` gained a
collaborator — `ClientPortalTest` and `ExpertPortalTest`, both of which import that controller.
Caught by the full `verify` rather than by any per-class run, which is the third time this
programme that a slice test has needed updating for a new constructor argument.

**Nothing is stored, and there is no migration.** Unit 38's cache exists because a board is ~115
cursor pages; one client's invoices are one page, and `GhlHttp`'s limiter already paces it. So
`00b` §1.3's "GHL is truth" holds here in its strongest form: EvalOS holds no invoice fact at all.

**Invariant 5 is the trap this unit is closest to.** A GHL invoice marked paid is **not** revenue
recognition — that is *paid AND delivered*, read only through `RefundService.isRevenueRecognized`.
Said on the route, in the shared type, and here, because a later dashboard summing this screen is
exactly the mistake the invariant exists to prevent.