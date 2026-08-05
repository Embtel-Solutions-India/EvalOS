# Unit 15 — Expert portal + Handoff B + sign-off

> **Rewritten: Dropbox Sign is out.** The expert signs the letter however they
> already sign things and **uploads it back through their portal**. See "Signing
> without a signature provider" below for the decision, what it costs, and the three
> measures that compensate. Everything else in this spec — the portal, the three
> responses, `EXPERT_TIMED_OUT`, the offer record, the SLA — is unchanged, because
> none of it ever depended on the provider.

**Phase:** 2 — Connect the seams
**Depends on:** 12 (the offer record and the rematch shortlist), 14 (the portal token
model and chain), **21 (the upload path this unit reuses wholesale)**
**Unlocks:** 16 (a signed, delivered case is what creates a payout), 19 (the
sign-timer this unit defines)
**Gating open questions:** **none external.** Both of this spec's original blockers
were Dropbox Sign's — the account/key/template/callback secret, and how a provider
callback would resolve its brand. Dropping the provider removes both, which is most
of why it was dropped: this unit went from the heaviest external dependency in the
phase to one that can be built the day Unit 21 lands.

## Goal

Close Handoff B. The client has approved the draft and the case is sitting in
`EXPERT_SIGNING` waiting for a human with credentials to put their name on it.
This unit gives that human a screen, the letter, and a way to send it back signed.

**Verifiable result:** on client approval the assigned expert gets a link; they open
it, see that one case's draft, goal and evidence, and can accept, ask for more
evidence, or decline with a reason; on accepting they **download the letter, sign it
in whatever tool they already use, and upload the signed file back**, which files it
into the case's Drive folder, records who uploaded what and when, and moves the case
to the PM for final QC; and a case still unsigned at 24 business hours is flagged for
reassignment.

## In scope

- The `EXPERT`-audience portal chain and view, on Unit 14's token model.
- The three expert responses: accept, request evidence, decline.
- **Download the final letter, and upload the signed one back** — Unit 21's upload
  path with `audience = 'EXPERT'`.
- The signed-letter provenance record: hashes, attestation, audit row.
- The 20h / 24h sign-SLA **alerting and the reassign operation**.

## Out of scope

- **Any e-signature provider.** No Dropbox Sign, no DocuSign, no in-browser
  signature pad — see below.
- **Scheduling** the 20h/24h timers. The build plan names the timer here; the
  invariant puts the scheduler in `job` (invariant 6), and Unit 19 owns that
  package. Split the same way Unit 10 split the chase: **this unit owns the
  logic, the events and the reassign operation; Unit 19 owns the clock.** Nothing
  fires on a schedule until Unit 19.
- Any new stage transition for "send to expert" — see below, the case is already
  in `EXPERT_SIGNING`.
- **Hosting the signed letter.** It goes to the case's Drive folder like every other
  document; EvalOS keeps the Drive file id, the hash and the timestamp. Invariant 14
  holds for the same reason it does in Unit 21 — the bytes stream through.
- An expert account, password, or a list of the expert's cases — see the
  one-token-one-case note.
- The payout the signature eventually earns — Unit 16.

## Signing without a signature provider

**The decision.** The expert downloads the letter, signs it the way they already sign
things — wet ink and a scan, or their own PDF tool — and uploads the signed file back
through the portal. EvalOS provides no signing surface at all.

**Why.** Three reasons, in order of weight:

1. **A scanned wet signature is the norm for this document.** An expert opinion
   letter accompanies an immigration filing, and a scanned signature is accepted
   there — often more readily than a third-party e-signature. The provider was
   solving a problem this document does not have.
2. **It removes the phase's heaviest dependency for almost no new code.** Unit 21
   already streams an uploaded file into the case's Drive folder, validates it, and
   writes an audit row naming a portal actor. This unit reuses that with a different
   audience. Against that, Dropbox Sign wanted an account, an API key, a template, a
   callback secret, a new inbound source, and an answer to how a provider callback
   resolves its brand.
3. **The supply side is the participant we do not control.** Not asking 400 experts
   to learn a tool removes friction from exactly the group that cannot be trained.

**What it costs — stated plainly, because it is a real loss.** A provider issues a
tamper-evident certificate: who signed, when, from where, over a document hash. An
uploaded PDF carries none of that, so **EvalOS cannot cryptographically prove an
expert signed anything.** If an expert later disputes a signature, there is no
certificate to produce.

**The three measures that compensate.** None is a certificate authority; together
they are a defensible chain of custody.

- **Hash both directions.** Record a hash of the letter as *sent* and of the signed
  file as *received*, both on the case. That does not prove the signature, but it
  proves exactly what EvalOS handed over and exactly what came back — which is what
  an actual dispute turns on.
- **Capture an attestation at upload.** The portal requires an explicit tick: *"I,
  {expert name}, confirm this is my signature on this letter."* Store the wording,
  the name as displayed, and the timestamp. A contemporaneous statement recorded
  against a named person is meaningful evidence even without a provider.
- **Write the audit row as the expert.** `actor_type = 'EXPERT'` already exists
  (`V22`), so the trail says the expert acted, through that token, at that time —
  not "a staff member recorded that the expert signed", which is what the old
  staff-stand-in path said.

**PM final QC stops being a formality.** It is now the only check that the uploaded
file is the right letter and is actually signed. That step already exists in the
pipeline; this decision makes it load-bearing, and the spec should say so where it
describes QC.

**No in-browser signature pad.** It is the weakest form legally, it would mean
building signing surface EvalOS has no business building, and it fails the same
non-repudiation test as an upload while costing far more. If a certificate is ever
genuinely required — a client demands one, or a dispute makes the risk concrete — the
answer is to add a provider back behind this same portal step, not to hand-roll one.

## There is no "send to expert" transition, and none is needed

The build plan describes "send-to-expert (client-approved → `EXPERT_SIGNING`)".
That transition **already exists and already runs**:
`CaseTransitions` declares `CLIENT_APPROVE_DRAFT: DRAFT_GENERATION → EXPERT_SIGNING`,
and Unit 14's portal fires it. By the time this unit is involved the case is in
`EXPERT_SIGNING` already.

What is missing is not a stage change but **getting the letter in front of the
expert**: minting their portal link. So this unit subscribes to
`draft.client_approved` and mints an `EXPERT`-audience `portal_access` row. No new
column remembers "sent" — the presence of an unrevoked expert token is that fact,
exactly as the presence of a signature-request id used to be.

Do **not** add a second transition into `EXPERT_SIGNING`. Two paths into one stage
is how a case gets there without an expert assigned.

## New actions on the transition table

Four, all declared **from `EXPERT_SIGNING` only**. Three are things only an expert
with a case in front of them can do; the fourth is a staff act about an expert who
did nothing, and the reason it has to exist is set out under the sign SLA below.
Each carries its own event and audit action, per `CaseTransitions.Action`'s
contract that a transition cannot be logged as one thing and published as another.

| Action | Lands on | Effect | New event |
| --- | --- | --- | --- |
| `EXPERT_ACCEPTED` | `EXPERT_SIGNING` (in place) | stamps the offer `ACCEPTED`; the expert has taken the case | `expert.accepted` |
| `EXPERT_REQUEST_EVIDENCE` | `EXPERT_SIGNING` (in place) | sets `ON_HOLD_AWAITING_CLIENT`, **adds a required checklist item** carrying the expert's description | `expert.evidence_requested` |
| `EXPERT_DECLINED` | *already exists* | sets `EXPERT_DECLINED_REMATCHING`; `REASSIGN_EXPERT` is the declared way out | `expert.declined` (exists) |
| `EXPERT_TIMED_OUT` | `EXPERT_SIGNING` (in place) | sets `EXPERT_DECLINED_REMATCHING` and stamps the offer `TIMED_OUT`. **GM · Brand Manager · PM**, never the expert and never a job | `expert.timed_out` |

`EXPERT_TIMED_OUT` mirrors `EXPERT_DECLINED`'s exact shape — stage-preserving,
setting the same exception state, so `REASSIGN_EXPERT` (which
`CaseTransitions.REQUIRES_EXCEPTION` pins to `EXPERT_DECLINED_REMATCHING`) is the
way out of both without being widened. It is a **separate action rather than a
reuse of `EXPERT_DECLINED`** for the reason this whole unit exists: "the expert
refused" and "the expert never answered" are different facts about a person whose
acceptance rate the match engine scores, and recording silence as a refusal would
put a decline the expert never made into the trail and into their rate.

`EXPERT_DECLINED` and `REASSIGN_EXPERT` are **already built** (Unit 04) and already
wired into `REQUIRES_EXCEPTION`. This unit only lets the expert be the one who
fires the first, instead of a staff member recording it second-hand.

**"Opens a client task" is a checklist item**, not a new entity. Unit 10 already
owns required-vs-supplied documents, the Coordinator's board already shows what a
case is waiting for, and the chase already reaches the client through GHL. A
separate task table would be a second answer to "what does this case need from the
client", and two answers disagree. So request-evidence calls
`ChecklistService`'s add-item path and publishes the event GHL turns into a
message.

The case going `ON_HOLD_AWAITING_CLIENT` is deliberate and has a consequence worth
stating: while held, the case accepts **nothing but `RESUME_FROM_HOLD`**
(`CaseTransitions`: "a case sitting in an exception state accepts nothing but its
way out"). The expert therefore cannot sign until the Coordinator resumes it. That
is correct — the expert asked for evidence precisely because they were not willing
to sign yet — but it means **the sign-SLA clock must not run while the case is
held**, which `SlaCalculator` already gets right (it returns null in an exception
state).

## Getting the letter signed

No integration, no SDK, no callbacks. Two portal operations, both reusing Unit 21.

### Download

The expert portal offers the final letter for download from the case's Drive folder.
Nothing new: the letter is already there as `draft_link`, and the expert's whitelist
(below) already includes it.

Record a **hash of the file as offered**, once, the first time it is downloaded, on
`letter_sent_hash`. This is half of the provenance chain — it is what lets EvalOS
later say *this* is the document the expert was given.

### Upload the signed letter

`POST /api/portal/expert/signed-letter` — Unit 21's path with
`audience = 'EXPERT'`. Everything in Unit 21's security section applies unchanged:
`X-Portal-Token` header only, allowlist by sniffed content, size cap, per-token rate
limit, generated filename, bytes streamed to Drive and never persisted by EvalOS.

Two additions specific to signing:

- **PDF only.** A signed evaluation letter is a PDF; JPEG and PNG are acceptable for
  a client's supporting document but not for the deliverable. Narrow the allowlist
  rather than reusing Unit 21's.
- **The attestation is required, not optional.** The request must carry the ticked
  confirmation and its wording; refuse `400` without it. This is the evidence, so it
  cannot be a UI-only checkbox that the API accepts absent.

On success, in one transaction: store `signed_letter_drive_file_id`,
`signed_letter_hash`, `signed_at`, the attestation text and the displayed name; fire
the existing **`EXPERT_SIGNED`** transition; stamp the offer `ACCEPTED` **only if it
is still `OFFERED`** (an expert who pressed Accept and then uploaded produces two
writes of one outcome on the happy path, and Unit 12's rule is that an outcome leaves
`OFFERED` exactly once — so the second is a no-op, not an error); and write the audit
row with `actor_type = 'EXPERT'`.

**Failure leaves the case where it was.** Drive down → 503, nothing recorded, the
case stays in `EXPERT_SIGNING` unsigned, which is a visible and recoverable state the
board already shows. Never record the signature before the file is safely filed —
that ordering is the same rule Unit 21 states, and for the same reason.

### What replaces the callbacks

| Dropbox Sign gave | Now |
|---|---|
| `signature_request.signed` | the upload itself, by the expert, through their own token |
| `signature_request.declined` | the portal's existing decline action, with a reason |
| `signature_request.viewed` | the portal's read receipt — Unit 14 already tracks this, so the capability survives |
| a tamper-evident certificate | **nothing.** The hash pair + attestation + `EXPERT` audit row, as described above |

**The staff-recorded stand-ins stay, and their meaning changes.**
`POST /api/cases/{id}/expert/signed` and `.../expert/declined` remain, gated as they
are — an expert who cannot work a portal will email a signed PDF to a Case Manager,
and that must not stop the business. But note the difference in the trail: a
staff-recorded signature audits as **staff** recording a claim about the expert,
where a portal upload audits as the **expert** acting. Both are legitimate; only one
is first-hand evidence. Prefer the portal, and do not let the stand-in become the
normal path out of convenience.

### One dependency this removes

The inbound gateway keeps **one source, GHL**. `WebhookSource.DROPBOX_SIGN` becomes an
enum value nothing writes — leave it (the column is text and costs nothing) but do not
build a handler for it. Two consequences worth recording elsewhere: spec 05's
"reused by Dropbox Sign in Unit 15" justification for the gateway no longer applies,
and **the brand-resolution problem disappears entirely.** That problem — one Dropbox
Sign account means one callback URL and the per-brand endpoint token cannot
distinguish brands, so the protected brand-resolution step might have had to change —
was this spec's second gating question and the only place in the design that
threatened a protected step. Dropping the provider removes it rather than solving it,
which is the best available outcome.

## What the expert may see

A whitelist, like Unit 13's and Unit 14's.

**Included:** the draft (`draft_link`), the **stated goal** — initial petition vs.
RFE, which is `service_type` plus `visa_category` — the evidence the checklist
records as supplied, the client's name (the expert is signing a letter about a
named person; it cannot be withheld), the case reference, and the signing
deadline. **Excluded:** `deal_value`, `invoice_ref`, `campaign_attribution`,
`pm_strategy_notes` (the PM's internal framing is not the expert's), the audit
timeline, staff assignment fields, other experts, and every other case.

**One token, one case** — the same rule as Unit 14, and it is a good fit here
rather than a compromise: an expert holding three cases legitimately holds three
links, each revocable on its own. There is no expert case list to build, and
therefore no expert account, password, or session to secure. It also bounds the
blast radius of a leaked link to one case.

## Backend

Expert portal routes, on the Unit 14 portal chain, `X-Portal-Token` header:

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | /api/portal/expert/case | portal token (EXPERT) | the whitelisted view; stamps `last_seen_at` |
| POST | /api/portal/expert/accept | portal token (EXPERT) | → `EXPERT_ACCEPTED` |
| POST | /api/portal/expert/request-evidence | portal token (EXPERT) | → `EXPERT_REQUEST_EVIDENCE`; body carries what is missing |
| POST | /api/portal/expert/decline | portal token (EXPERT) | → `EXPERT_DECLINED`; reason required |
| GET | /api/portal/expert/letter | portal token (EXPERT) | download the final letter; stamps `letter_sent_hash` on first fetch |
| POST | /api/portal/expert/signed-letter | portal token (EXPERT) | **the signature.** Multipart, PDF only, attestation required → `EXPERT_SIGNED`. Unit 21's upload path with `audience = 'EXPERT'` |

Staff-side, on the normal chain:

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| POST | /api/cases/{id}/expert-portal-link | GM · Brand Manager · PM · CM | mint or re-mint the expert link. Now the **only** way the expert is reached, so it is the main path rather than a fallback; `V23`'s one-unrevoked-token index still applies |
| POST | /api/cases/{id}/expert/timed-out | GM · Brand Manager · PM | → `EXPERT_TIMED_OUT`. The human answer to the 24h prompt: stamps the offer `TIMED_OUT` and opens the rematch. **Not on the CM's list** — taking a case off an expert is the same weight of call as staffing it |

Audit rows from the portal use `AuditService.recordPortalEvent` with
`actor_type = EXPERT` (Unit 14's column), so "the expert declined" and "a Case
Manager recorded that the expert declined" are two distinguishable facts in the
trail. That distinction is the reason this unit exists.

## The sign SLA

`SlaCalculator` already budgets expert sign at **24 business hours**. This unit adds
the **20-hour warning** and the **reassign operation**; Unit 19 fires both on the
Pacific business calendar.

- `expert.sign_overdue_warning` at 20h → in-app notification (Unit 06) to the CM
  and PM.
- `expert.sign_overdue` at 24h → notification, and the case is **flagged for
  reassignment** — surfaced to the PM with the Unit 12 shortlist for the next
  expert.
- **Auto-reassign proposes; it does not reassign.** Silently pulling a case off an
  expert who was about to sign, and emailing a second expert the same letter, is
  worse than a late case. The build plan's "auto-reassign" is read as
  **auto-prompt**, which is also the wording `project-overview.md` uses ("the case
  auto-prompts reassignment"). Where the two documents differ, the narrower reading
  wins and is recorded here.

  **The CRM build spec agrees, in its own words.** A18 reads "Auto-reassignment
  workflow triggered. ENM notified. **CM prompted to confirm next expert.**" A
  workflow that ends in a human confirming is a prompt, so there is no conflict to
  resolve — only a wording trap for whoever reads the first sentence alone.

- **Who sees the prompt, and who may fire it — these are different people, and A18
  reads as though they are the same.** The CM is notified at 20h and 24h and sees the
  reassign prompt on their dashboard; **firing `EXPERT_TIMED_OUT` stays GM · Brand
  Manager · PM.** Taking a case off an expert is the same weight of call as staffing
  it, and it stamps a permanent outcome on that expert's record. So the CM's "confirm
  next expert" is a request, not an authority: the CM raises it, a PM or above acts.
  Recorded because the business spec puts the verb next to the CM.
- **And the prompt needs somewhere to lead, which is why `EXPERT_TIMED_OUT`
  exists.** `REASSIGN_EXPERT` requires `EXPERT_DECLINED_REMATCHING`
  (`CaseTransitions.REQUIRES_EXCEPTION`), and an expert who has not answered has
  not declined — so before this action was declared there was **no legal path from
  a 24h timeout to a rematch at all**, and `TIMED_OUT` was an outcome nothing could
  ever write. An earlier draft of this file said `TIMED_OUT` is "stamped when the
  case is actually reassigned after a timeout" without saying how the case got
  there; the honest answers were a staff member firing `EXPERT_DECLINED` on an
  expert who never declined, or widening `REASSIGN_EXPERT` to fire from anywhere.
  The first corrupts the trail this unit exists to keep straight; the second
  removes the guard that stops a case being pulled off an expert mid-signature.
  So: the timer prompts, and a **human fires `EXPERT_TIMED_OUT`**, which is the act
  that both stamps the offer `TIMED_OUT` and opens the rematch. `TIMED_OUT` is
  therefore written **here**, by a person, not by Unit 19's clock — the clock only
  raises the notification that asks for it.

## Frontend deliverables

1. **Expert portal** (`features/expert-portal`), a separate entry point outside
   `AppShell`, on Unit 14's pattern. Route `/portal/expert#<token>`.
2. **Single-column assigned-case view**: the goal at the top (what this letter has
   to achieve), then the draft, then the evidence list, then the actions. One
   column because the expert has one decision to make and reads top to bottom.
3. **Three actions**: Accept · Request evidence (with a required description of
   what is missing) · Decline (with a required reason). Decline confirms — it sends
   the case back to rematching.
4. **Sign** is a two-step panel, and the copy carries the weight: **Download the
   letter** → sign it in your own tool → **Upload the signed letter**. Say plainly
   that a scanned wet signature is expected and accepted, so nobody hunts for an
   e-signature button that does not exist. State the accepted type (PDF) and the size
   cap on the control, per `ui-context.md`.
5. **The attestation is part of the upload, not a separate step**: a required tick
   reading *"I, {name}, confirm this is my signature on this letter"* next to the
   file input, with upload disabled until it is ticked. It is the evidence, so it must
   be impossible to submit without it — and the API refuses it absent regardless of
   what the UI does.
6. **Status after acting**, including the deadline and, when held for evidence,
   that EvalOS is waiting on the client — not on the expert.
7. Staff side: the case detail expert card gains the sign status, the viewed
   timestamp, the sign deadline, the **signed letter link** once uploaded, and
   **Re-send link**; the production
   board's `EXPERT_SIGNING` column shows the 20h/24h warning state. Past 24h the
   card also offers **Mark timed out & rematch** (`EXPERT_TIMED_OUT`), which is the
   answer to the overdue prompt — confirmed, and worded so it is not mistaken for
   recording a decline the expert never made.

## Acceptance criteria

- [ ] Client approval mints exactly one unrevoked `EXPERT` token for the assigned
      expert's case, and re-minting revokes the previous one (`V23`).
- [ ] An `EXPERT` token reads its own case only; the whitelist excludes
      `deal_value`, `invoice_ref`, `campaign_attribution` and
      `pm_strategy_notes`, asserted by grepping the serialized response.
- [ ] A `CLIENT` token is **rejected** on `/api/portal/expert/**` and an `EXPERT`
      token on `/api/portal/client/**`. One table, two audiences, no crossing.
- [ ] Accept stamps the offer `ACCEPTED` and writes an audit row with
      `actor_type = EXPERT`.
- [ ] Request-evidence puts the case in `ON_HOLD_AWAITING_CLIENT`, **creates a
      required checklist item** visible on the Coordinator's board, publishes the
      GHL event, and — asserted explicitly — the expert **cannot then sign** until
      the case is resumed, and the sign SLA reports no clock running while held.
- [ ] Decline sets `EXPERT_DECLINED_REMATCHING`, stamps the offer `DECLINED` with
      the reason, and the case appears in the board's rematching lane with a
      shortlist for the next expert.
- [ ] **A timed-out case can actually be rematched, and the trail says why.**
      `EXPERT_TIMED_OUT` sets `EXPERT_DECLINED_REMATCHING`, stamps the offer
      `TIMED_OUT` (not `DECLINED`), and `REASSIGN_EXPERT` then succeeds — asserted
      end to end, because before this action existed the path did not close.
      An expert who timed out shows no decline in their audit trail and no
      `DECLINED` row against their acceptance rate.
- [ ] The expert cannot fire `EXPERT_TIMED_OUT` (no portal route reaches it) and a
      CM gets 403 from the staff route.
- [ ] Accepting in the portal and then uploading leaves the offer `ACCEPTED` with
      **one** outcome write — the upload's stamp is a no-op, not a second one.
- [ ] **Uploading the signed letter fires `EXPERT_SIGNED` once**, files the PDF in the
      case's Drive folder, and records `signed_letter_drive_file_id`,
      `signed_letter_hash`, `signed_at`, the attestation text and the displayed name.
      The audit row's `actor_type` is **`EXPERT`**, not `STAFF`.
- [ ] **An upload without the attestation is refused `400`** even though the UI
      disables the button — the API is the guard, because the attestation is the
      evidence.
- [ ] **Non-PDF is refused** by content sniffing, not by extension: a `.pdf`-named
      JPEG does not become a signed letter.
- [ ] **Drive unavailable → 503 and the case is unchanged** — still `EXPERT_SIGNING`,
      still unsigned, no partial record. Asserted, because the reverse order would
      mark a case signed with no letter behind it.
- [ ] **The letter's hash is recorded when it is downloaded**, so a dispute can be
      answered with what was sent as well as what came back.
- [ ] An expert who is `ON_HOLD_AWAITING_CLIENT` for evidence **cannot upload** until
      the case resumes.
- [ ] No file is written to local disk and no blob column exists — the invariant-14
      property, asserted as in Unit 21.
- [ ] The staff-recorded `expert/signed` and `expert/declined` endpoints still work
      and write the same transition — but audit as **`STAFF`**, so the trail
      distinguishes a first-hand signature from a recorded claim about one.
- [ ] `npm run build` green; `./mvnw verify` green. **A live round-trip is required to
      close the unit** — a real expert token, a real download, a real signed PDF
      uploaded into a real Drive folder — recorded in the tracker, the same standard
      Unit 05 was held to. This now needs the **Google service account** rather than a
      Dropbox Sign account, i.e. the same credential Units 13 and 21 need.

## Invariants honored

Brand isolation — the expert token names one case in one brand (1); the expert sees
only their assignment (3); `payment_detail` is not on this surface, and the expert's
own payment detail is not shown to them either, there being no read path for it at
all (4); the timers run in `job`, not in a controller (6); the portal controller
carries no business logic — it routes to `CaseLifecycleService` (12); every response
writes an append-only audit row naming **the expert** as actor (13); **the signed
letter is filed in Drive and streams through EvalOS without being stored, and EvalOS
sends no email — the expert is reached by a portal link** (14).

Invariant 10 no longer applies to this unit at all: there are no callbacks, because
there is no provider. The inbound gateway keeps one source.

## Files touched

**Created.** Backend: `service/ExpertSignService.java` (mint / re-mint / the signed
upload / the SLA computation), `web/ExpertPortalController.java`,
`web/ExpertSignController.java` (+ DTOs).
Migration `V<next>__case_expert_signing.sql` — `expert_viewed_at`,
`sign_deadline_at`, `letter_sent_hash`, `signed_letter_drive_file_id`,
`signed_letter_hash`, `signed_at`, `sign_attestation`, `sign_attested_name` on
`evalos_case`. Frontend: `frontend/src/features/expert-portal/*` (`ExpertCaseView`,
`SignPanel`, `expertPortalApi`).

**Reused, not created.** Unit 21's upload path — validation, streaming,
`GoogleDriveClient.uploadFile`, the `folderIdOf` helper and the portal rate limiter.
This unit adds a narrower allowlist (PDF only) and the attestation requirement; it
must **not** fork a second upload implementation.

**Modified.** `service/CaseTransitions.java` — the three new actions
(`EXPERT_ACCEPTED`, `EXPERT_REQUEST_EVIDENCE`, `EXPERT_TIMED_OUT`).
`event/CaseEvents.java` — `expert.accepted`, `expert.evidence_requested`,
`expert.timed_out`, `expert.sign_overdue_warning`, `expert.sign_overdue`.
`service/CaseLifecycleService.java` — the three new transition methods, and the
first-write-wins guard on the offer outcome.
`service/ChecklistService.java` — the add-item path reused by request-evidence.
`notification/NotificationListeners.java` — the new events' recipients.
`frontend/src/features/case/ExpertCard.tsx`, `frontend/src/App.tsx`.
`application.yml` — nothing secret; the upload limits already exist.

**Deleted from the plan.** `integration/DropboxSignClient`,
`config/DropboxSignConfig`, `webhook/DropboxSignHandler`, the SDK dependency, the
three router event types, and the `signature_request_id` column. None was ever built,
so this is a spec change and not a migration.

**Not touched.** `webhook/*` entirely — no new inbound source, so the gateway and the
protected brand-resolution step are out of scope for this unit rather than
conditionally in it. `service/ScopePredicate.java`. Every applied migration.
`pom.xml` — this unit now adds **no dependency**.
