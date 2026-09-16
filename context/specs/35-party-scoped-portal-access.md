# Unit 35 — Party-scoped portal access, the stage projection, and the expert's payout view

> **Status: BUILT. §7 2026-09-04 (D8 + G14); §6 step 3 2026-09-10 — the migration, the two party
> reads, the projection and D6's whitelist; §6 step 4 (34b then 34d) 2026-09-11.** *(This line said
> step 4 was "NOT built" long after it shipped — corrected 2026-09-12.)*
>
> **⚠ AMENDED 2026-09-12 — the party credential survives; the staff-minted CLIENT link does not.**
> D1's party token is exactly what Unit 42's sign-in mints, so nothing here is undone. What is gone
> is the *other* way to obtain one: `POST /api/cases/{id}/portal-link?audience=CLIENT` and the
> client arm of `mintForParty`. Clients reach the portal at its own origin and sign in. `resolve`
> still admits CLIENT rows, so links already issued keep working until they expire. **The expert
> half of every mechanism in this spec is untouched** — an expert has no account.
>
> This is the unit that implements four decisions
> the business took on 2026-09-04, recorded in `34-portal-frontend-wiring.md` §4:
> **D1** (a portal credential names a party, not a case), **D5** (one lifecycle vocabulary,
> EvalOS's, projected into the payload), **D6** (an expert reads their own payout rows), and
> **D8** (no third-party analytics — which is a deletion and is done first, see §6).
>
> **It is a pivot, and pivots are specced before they are coded.** D1 reverses the
> "one token, one case" line in `architecture.md`, `project-overview.md`, `ui-context.md` and
> Unit 14's own spec. Those four are amended by this file rather than by the code.

**Phase:** 2 — Connect the seams
**Depends on:** 14 (the token model this widens), 15 (the expert surface it re-scopes),
16 + 16b (the payout rows D6 exposes), 30 (the document store), 34a/34c/34e (the wired screens)
**Unlocks:** 34b (the client's draft review, once it knows which case), 34d (both case lists)
**Gating open questions:** none. Expiry was the last one and §2 answers it.

---

## 1. What changes, in one paragraph

`portal_access` stops naming a case and starts naming a **party**: a `CLIENT` row names a
`ghl_contact_id`, an `EXPERT` row names an `expert_id` (already there since `V37`), and `case_id`
becomes **nullable**. A party-scoped token then answers "my cases" — which is what the delivered
screens draw — and the case-scoped token stays legal for the one thing it is better at: a link to
**one** case, forwarded once, revocable on its own.

Everything else about the credential is untouched: 256 bits from `SecureRandom`, returned once,
stored only as a SHA-256 hash, absolute expiry, one live token per scope, re-mint revokes the
previous, and unknown/expired/revoked answer one identical 401.

**No accounts.** No password store, no reset flow, no lockout, no session. That alternative was
priced in D1 and refused: it reverses four documents and needs a mail channel invariant 14 says
does not exist.

## 2. Expiry — open question (a), answered

**A party-scoped token lives 7 days; a case-scoped one keeps its 30.**

A party token opens every case that party has, so it is a wider credential than the case token and
must not live four times as long. Seven days is long enough to survive a weekend and a chase, short
enough that a forwarded link is dead before it is forgotten. Two properties, two settings:

| | `evalos.portal.link-ttl` | `evalos.portal.party-link-ttl` |
|---|---|---|
| Scope | one case | every case this party has |
| Default | `P30D` (unchanged) | **`P7D`** |

Re-minting is one click, which is what makes a short life cheap.

## 3. The data model

```sql
ALTER TABLE portal_access
    ALTER COLUMN case_id DROP NOT NULL,
    ADD COLUMN ghl_contact_id text;
```

- `expert_id` already exists (`V37`), and this unit gives it a second job: on a case-scoped
  expert token it is the identity check; on a party-scoped one it **is** the scope.
- `ghl_contact_id` is **GHL's contact id and never an email** (invariant 7, and `V27`'s lesson
  that email is a fallback key only). It is the same identifier the S3 key prefix uses, so a
  client resolves in GHL, in the bucket and in EvalOS with no mapping table.
- **Exactly one of `case_id` / party is set, and the CHECK is worth having here** — unlike
  `V37`'s, which had to be omitted because it would have blocked the revoke that retires an old
  row. This constraint is about rows nothing has written yet:

```sql
CONSTRAINT portal_access_scope_is_one_thing CHECK (
    (case_id IS NOT NULL)
    OR (audience = 'CLIENT' AND ghl_contact_id IS NOT NULL)
    OR (audience = 'EXPERT' AND expert_id IS NOT NULL))
```

- The one-live-token index (`V23`) moves with the scope: `(case_id, audience) WHERE revoked_at IS
  NULL` becomes two partial indexes, one per shape, so a party link and a case link for the same
  person can coexist and neither can double up.

## 4. What the principal carries, and what the services do with it

`PortalPrincipal` gains nothing new — it already carries `expertId` — but `caseId` becomes
nullable and the two portal services stop assuming it:

- **Case-scoped token**: exactly today's behaviour, including `V37`'s expert check. Nothing
  regresses, and the existing tests are the proof.
- **Party-scoped token**: a new **list** read per audience, and every existing single-case route
  takes the case id as a **path variable that is checked against the party** — the client's own
  contact id, or the case's `expert_id`. That check is the whole authorization and it is the same
  shape as the document-kind filter Unit 34c added: the id may arrive from the request precisely
  because it is matched against the credential before anything is read.

| Method | Path | Scope | Answers |
| --- | --- | --- | --- |
| GET | `/api/portal/client/cases` | party | this contact's cases, each with D5's client step |
| GET | `/api/portal/client/cases/{id}` | party | one of them, the `ClientDraftView` whitelist |
| GET | `/api/portal/expert/cases` | party | this expert's assignments, each with D5's expert step |
| GET | `/api/portal/expert/cases/{id}` | party | one of them, the `ExpertCaseView` whitelist |
| GET | `/api/portal/expert/payouts` | party | **D6** — see §5 |

The existing case-scoped routes (`/client/case`, `/expert/case`, …) stay exactly as they are. A
party token on them resolves the party's *only* case when there is one and answers **409 with a
"say which case" code** when there are several — rather than picking one, which is how a client
approves the wrong draft.

## 5. D5 — the projection, and D6 — the payout rows

**D5.** EvalOS serves the step, the SPA renders the string. The mapping from Unit 31's twelve
stages to the client's five words and the expert's three lives in **one** place server-side
(`PortalStageProjection`), and `34-portal-frontend-wiring.md` §4 D5 holds the table. The SPA
already holds no lifecycle enum (34c and 34e both kept that line); this is what lets the *list*
screens keep it too.

**D6.** An expert reads `payout_ledger` rows for their **own** `expert_id`: case reference,
amount, currency, status, and the settlement date when `payout_payment` has one. **Never
`payment_detail`** — that field has no read path anywhere in EvalOS, not even for the ENM who
typed it (invariant 4), and this does not become the first one. New whitelist, so it is a record
with a named field list and a serialization test, like Unit 14's and Unit 15's.

## 6. Build order

1. ~~**D8 first, because it is a deletion** (§7)~~ — **DONE 2026-09-04.** Seven call sites, the
   module and both env vars deleted from `client-expert/client`.
2. ~~**G14's upload controls** (§7)~~ — **DONE 2026-09-04.** `common/UploadedFileType` guards both
   upload surfaces, and every presigned read is an `attachment`. 12 tests.
3. ~~The migration, the two party reads, the projection, D6's whitelist.~~ — **DONE 2026-09-10.**
   `V38`, `PortalStageProjection`, five new portal routes, `?party=true` on the mint. 23 tests;
   611 backend green. What landed differs from this spec in one place, recorded rather than
   hidden: **the 409 has its own code, `SAY_WHICH_CASE`**, not `ILLEGAL_TRANSITION` — a portal
   that cannot tell "not allowed" from "which one" shows the client the wrong sentence.
4. The two list screens (34d), then the client's draft review (34b) — which is the highest-value
   screen in the whole portal and the reason D1 was worth taking. **Still owed**, and the reason
   one acceptance criterion below is unticked.

## 7. Shipped alongside, because the business asked for them in the same breath

- **D8 — analytics off.** `VITE_GTM_ID` / `VITE_GA4_ID` and `utils/analytics.ts` come out of
  `client-expert/client` entirely. Not stubbed: a no-op module is one somebody re-points at a
  provider. The pages that show a client's passport scan send nothing to anybody.
- **G14 — the antivirus posture, implemented rather than declared.** EvalOS does not run a
  scanner and this unit does not pretend to add one; what it adds is the three controls that
  actually reduce the risk of an uploaded file, and a written stance:
  1. **Content sniffing on the client's upload too.** Unit 30 recorded "a declared type is
     recorded, not trusted" as an owed item; Unit 15 sniffed magic bytes for the signed letter.
     The same helper now guards both surfaces, so a `.pdf`-named executable is refused at the
     door on either.
  2. **Nothing is served inline.** Every presigned read is minted with
     `response-content-disposition: attachment` and the stored content type, so a malicious
     HTML or SVG cannot execute in the browser origin that opened it. This is the control that
     matters most, because it is the one that closes the *path* rather than the file.
  3. **The stance, written down:** EvalOS accepts files from a public link, validates type and
     size, stores them in a versioned bucket it is the only writer to, and serves them only as
     short-lived attachments. **Scanning is the bucket's job** — S3 malware protection is an
     infrastructure control the business enables on the bucket, not code EvalOS ships. That ask
     goes in the tracker beside the credential.

## 8. Invariant impact

- **1 (brand isolation)** — a party token still carries a brand, off the case or expert it names,
  and every scoped read keeps its predicate.
- **4 (`payment_detail`)** — **load-bearing in D6.** Ledger rows yes, that field never.
- **7 (`ghl_contact_id` is canonical)** — **D1 rests on it.** A client token names GHL's contact
  id and nothing else; no portal-minted client identifier, ever.
- **13 (append-only audit)** — every portal act already writes through
  `AuditService.recordPortalEvent`. A party token widens what a row can be *about*, so the audit
  row must name the case it acted on, which it already does.
- **14 (no files, no email)** — unchanged. §7's attachment header is the opposite of a widening.
- **15 (no AI)** — unchanged, and **Unit 20 is now gone from the schedule as well as from scope**
  (see the build plan): the anomaly half was arithmetic and belongs to Unit 17 as a tile if the
  business ever wants it, not to a unit whose name invites the model back.

## 9. Acceptance criteria

- [x] A `CLIENT` party token lists exactly the cases its `ghl_contact_id` has, in one brand, and
      nothing else — asserted with a second contact in the same brand and a third in another.
      `PartyScopedPortalAccessTest`.
- [x] An `EXPERT` party token lists exactly its own assignments; another expert's case id on
      `/expert/cases/{id}` answers **403**, not 404 with a hint.
- [x] A case-scoped token behaves exactly as it does today, `V37`'s expert check included —
      claimed by the 588 pre-existing tests continuing to pass, not by a new assertion.
- [x] A party token on a single-case route with several cases answers **409**, and with one case
      answers that case.
- [ ] **NOT MET, and it belongs to 34b/34d.** Every status word either portal shows is a string
      EvalOS sent; a search for a lifecycle enum in `client-expert/*/src` finds none. The server
      half is done — `PortalStageProjection` is the single mapping and both list payloads carry
      `step` + `actionRequired` — but the SPA still holds `RequestStatus`, `SigningStatus`,
      `DocumentStatus` and `IntakeDocumentStatus`. They come out when the screens that read them
      are rewired; three of the four now sit behind parked routes.
- [x] The expert's payout read carries no `payment_detail` in any form, asserted by serializing.
- [x] A party token expires in 7 days by default; re-minting revokes the previous one — and
      **only the previous party one**: a case link already sent keeps working.
- [x] **A `.pdf`-named JPEG is refused on the client's upload too**, by content. (G14, 2026-09-04.)
- [x] **A presigned read carries `attachment`**, asserted on the URL EvalOS mints. (G14.)
- [x] No third-party tag loads in `client-expert/client`; `VITE_GTM_ID` and `VITE_GA4_ID` do not
      exist. (D8.)
- [x] `./mvnw verify` green (611 tests); both portal apps build; the portal test suite green (22).
