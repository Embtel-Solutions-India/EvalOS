# Unit 52 — The Client Portal ↔ GHL integration

**Status: PARTIALLY BUILT (2026-09-16).** §4 is built and tested. §5 is the delta, and most of it
is **Units 44–48**, not this unit — decided 2026-09-16, see §6.

The requirement, as the business stated it:

> When a client signs up, EvalOS fires an API to create or find/link the corresponding GHL Contact
> and stores the GHL Contact ID in EvalOS. When the client submits a service request, EvalOS
> creates a basic GHL Opportunity containing only the essential request/client data, while the
> detailed questionnaire, uploaded documents, and request information remain owned by EvalOS. A GHL
> workflow triggered by opportunity creation assigns the opportunity to the appropriate pipeline
> based on the requested service. Both EvalOS and GHL stay synchronized so Sales can see and manage
> the Contact and Opportunity from either platform, while EvalOS provides the complete business
> context — Request, Questionnaire, Documents, Payments and Case — without making GHL the primary
> source of business data.

---

## 1. The truth model this rests on, restated

Unchanged from `00b` §1.3 and still correct:

| Record | Owner | EvalOS holds |
|---|---|---|
| Contact | **GHL** | `client_account.ghl_contact_id`, `contact_snapshot` |
| Opportunity | **GHL** | `client_application.ghl_opportunity_id` |
| Request + questionnaire | **EvalOS** | `client_application` (`V49`) |
| Documents | **EvalOS** | `case_document`, S3 object keys |
| Invoices / payments | **GHL** (D15) | read-only, `PortalInvoiceService` |
| Case | **EvalOS** | `evalos_case`, born only of `opportunity.won` (invariant 8) |

**"Without making GHL the primary source of business data" is already true and is enforced rather
than intended.** Nothing in `client_application` has a GHL equivalent — a service id from EvalOS's
own catalog, and a questionnaire whose questions depend on it — and `CachedOpportunity`'s class
comment makes it a build-time rule that no EvalOS screen may show a value that exists only in a GHL
cache.

---

## 2. Status against each clause of the requirement

| Clause | State |
|---|---|
| Sign-up creates or finds the GHL Contact | ⚙️ **moved, §8 and §11** — the contact is created at **set-password**, not at sign-up: an unauthenticated `permitAll` route may not write to the live CRM. `ClientAccountService.setPassword` → `GhlWriteClient.upsertContact` |
| The GHL Contact ID is stored in EvalOS | ✅ **built** — `client_account.ghl_contact_id`, set by `linkGhlContact` |
| A basic GHL Opportunity carrying the essential data | ✅ **built (§4.1)** — contact, name, the requested service, the correlation key and `SUBMITTED`, all on one create **at submit** (§9) |
| Questionnaire / documents / request stay EvalOS's | ✅ **built** — and structurally, see §1 |
| A GHL workflow routes it to a pipeline by service | ⚙️ **EvalOS's half is built (§4.1); the workflow is yours to build in GHL (§4.3)** |
| GHL learns the request was submitted | ✅ **built** — and since §9 the opportunity's existence *is* the signal; the field rides the create rather than a follow-up call |
| Both platforms stay synchronized | ❌ **Units 44–48**, see §5.1 and §6 |
| Sales manages from either platform | ◐ EvalOS→GHL works; GHL→EvalOS is §5.1 |

---

## 3. One decision the requirement collided with, and how it was resolved

The requirement says the opportunity is created **when the client submits**. EvalOS created it when
the client **picked a service** — decision **D10**, taken on 2026-09-15 because the questionnaire is
the longest part of the funnel and therefore exactly where people stop, so *a lead who abandons
halfway must already be on a salesperson's board*.

**Resolved 2026-09-16 in favour of the requirement, at the third asking — see §9.** It was refused
twice first, the second time with a compromise (deal at service-pick plus a `SUBMITTED` marker).
The business asked again; the argument against is on record and repeating it a fourth time would be
substituting EvalOS's judgement for theirs on a question that is theirs. The opportunity is now
created at submit, and **an abandoned questionnaire reaches nobody** — a cost §9.2 states plainly
and `open-decisions.md` Q11 carries.

---

## 4. What was built

### 4.1 The requested service goes to GHL as an opportunity custom field

`evalos.ghl.opportunity-service-field` (`GHL_OPPORTUNITY_SERVICE_FIELD`) names a GHL opportunity
custom field id. `ClientApplicationService` sets it to the **service id** — `academic_evaluation`,
`eb1a_expert_opinion_letter`, … — when it creates the opportunity.

**This is the whole of EvalOS's part in the routing, and the narrowness is deliberate.** A GHL
workflow can branch on a custom field; it cannot branch on the free text in the opportunity's
*name*, which is where the service appeared before and nowhere else. So EvalOS states the fact it
owns — which service was asked for — and GHL decides which pipeline that belongs on.

- **The id, not the display name.** The name is written for a human reading a board and is reworded
  whenever the catalog is; the id is the stable slug a workflow condition can be written against.
- **Mapping services to pipelines lives in the workflow, not here.** Eighteen services against four
  sales pipelines is a business rule. A copy in EvalOS goes stale the first time somebody reworks
  the routing in GHL — which is exactly why `hot-stage-name` was removed from this class on the
  business's instruction, and this unit does not bring it back.
- **No price is sent.** EvalOS holds no price list; what the work is worth is Sales' to set. A zero
  would be a priced deal worth nothing rather than an unpriced one, and would land in the GM
  dashboard's won figures as exactly that.
- **Blank omits the field and nothing else changes.** An environment that has not created the field
  yet still opens the deal — losing a finished request over an unconfigured field would be the
  worse failure by a wide margin.

### 4.2 Submitting the request tells GHL

`evalos.ghl.opportunity-submitted-field` (`GHL_OPPORTUNITY_SUBMITTED_FIELD`) names a second custom
field carrying the constant `SUBMITTED`.

**It used to be a follow-up call and is now part of the create (§9.3).** The field existed because
the old D10 opened the deal at service-pick, so a board could not tell somebody browsing from a
finished request. Since §9 only a submit opens a deal, so the opportunity's *existence* already
says it — the field is kept, written on the create, because a GHL workflow may already be keyed on
it and because an explicit fact is cheaper to write a condition against than an implicit one.

- **`GhlWriteClient.setOpportunityFields` is no longer on this path at all.** It was used here to
  avoid a body carrying a `pipelineId`, which could have undone GHL's own routing. There is nothing
  to undo now: the marker is set at the moment the deal is created, before any workflow has moved
  it. The method remains for other callers.
- **A failure refuses the submit**, which is the opposite of the old rule and follows from the
  change. The swallow was right when the deal already existed and only a marker was missing; a
  failed *create* means Sales has no deal at all, and answering "sent" to that is the one lie this
  flow must not tell. The draft and the answers survive and the next attempt retries against the
  same correlation key.

### 4.3 What you have to build in GHL

EvalOS cannot create a workflow through the API; this is UI work in the sub-account
(`WY6bW2xUCI8Tz8gw7aLJ`).

1. Create two **Opportunity** custom fields under Settings → Custom Fields: one for the service
   (text), one for the request status (text or a picklist containing `SUBMITTED`).
2. Copy their **ids** into `GHL_OPPORTUNITY_SERVICE_FIELD` and `GHL_OPPORTUNITY_SUBMITTED_FIELD`.
   `GET /api/sales/opportunity-fields` — which the sales desk already calls — lists them.
3. Build a workflow triggered on **opportunity created**, filtered to the intake pipeline, with
   branches on the service field that move the deal to the right pipeline. A GHL *Update
   Opportunity* action can change the pipeline: `PUT /opportunities/{id}` treats the pipeline as a
   mutable field and the opportunity **keeps its id**, which is what makes the EvalOS link and the
   note stream survive the move.
4. Optionally, a second workflow on the submitted field changing.

**Verify step 3 in the GHL workflow builder before promising it.** The API-side capability is
proven (`GhlWriteClient.moveStage` relies on it); that the workflow builder exposes a
pipeline-changing action in this sub-account is a UI fact this repository cannot check.

**Also outstanding and not this unit's:** `GHL_INTAKE_PIPELINE_NAME` defaults to blank, which makes
every request-start answer 502, and `00d` records that the `opportunity.won` workflow was never
recreated in the new sub-account — so **no case is created by anything** today. Both are Phase 0
runbook items and both must be done for this flow to work end to end.

---

## 5. The delta that remains

### 5.1 GHL → EvalOS is one webhook wide

The only inbound event handled is `opportunity.won`, which creates a case. `WebhookRouter` archives
and acks `contact.created`, `contact.updated` and `refund.requested` without routing them, and
`opportunity.update` is not recognised at all. So today:

- A salesperson moving a deal, pricing it or reassigning it in GHL leaves **no trace in EvalOS**.
- A contact edited in GHL does not reach `client_account` or `contact_snapshot`.
- "Manage from either platform" is true in one direction only.

### 5.2 `client_application` has no link to the case

`evalos_case.ghl_opportunity_id` and `client_application.ghl_opportunity_id` hold the same value
when a request is won, so the join exists — but nothing reads it, and there is no
request → case navigation on any screen. That is what "EvalOS provides the complete business
context" needs and does not yet have.

### 5.3 No document is attached to a request

`case_document` is case-scoped. A client uploading during intake has nowhere to put the file until
a case exists, which `00d` §2.2 already records.

---

## 6. Decision — how far the sync goes

**Units 44–48, the full mirror. Decided 2026-09-16.**

Not this unit, and not a webhook bolted on to it. The programme is already specced in
`context/specs/00c-ghl-independence-programme.md` and amended by `00d` §6:

| Unit | What it lands |
|---|---|
| 44 | Tier-1 mirror tables + the correlation custom field + `pipeline` / `pipeline_stage` + `team_member_pipeline`; merges `contact_snapshot` and `client_account` |
| 45 | The outbox (partial-unique on `entity_id`, not payload), per-field ownership, `sync_drift`, paged diff audit, error classification, a sync-status surface |
| 46 | The desks move onto the mirror |
| 47 | Cut to what 46 actually reads |
| 48 | The switch, exercised in staging |

**This unit hands three requirements to that programme**, and they should be read as acceptance
criteria for it rather than as gaps here:

- §5.1 — inbound `opportunity.*` and `contact.*` become the sync engine's input, not one-off handlers.
- §4.2's lost-marker `ponytail:` — Unit 45's outbox is its fix.
- §5.2 — the request → case join is a mirror-era read.

`00d` §6.1 is the part to read first: **at-least-once webhook delivery over a non-idempotent create
produces duplicates**, which is the failure this whole flow is most exposed to, and its correlation
field is pulled forward into Unit 44 for exactly that reason.

---

## 7. Acceptance

- [~] **Superseded by §8.** Sign-up upserted a GHL contact and stored its id. The upsert moved to
      `setPassword` on 2026-09-16; all three states (no contact, contact only, contact + account)
      remain legal, and state (a) now simply lasts until the mailbox is proved.
- [x] Picking a service opens a GHL opportunity carrying the contact, a human-readable name and the
      **service id** as a custom field.
- [x] No stage, no assignee and no monetary value are sent — GHL owns placement and Sales owns price.
- [x] Submitting writes `SUBMITTED` to the submitted field through a call that cannot carry a
      pipeline, so it cannot undo GHL's routing.
- [x] A GHL outage at submit leaves the request submitted in EvalOS and logs the missed marker.
- [x] Both fields blank leaves every existing behaviour unchanged.
- [ ] A GHL workflow routes by the service field — **yours to build (§4.3)**.
- [ ] GHL → EvalOS sync — **Units 44–48 (§6)**.

---

## 8. Amendment, 2026-09-16 — the contact is born at set-password, not at sign-up

**This reverses §2's first row and §7's first acceptance box.** Both are edited above rather than
left standing beside this section; what follows is the argument, not a second opinion.

### 8.1 Why the old placement was wrong

`POST /api/portal/auth/sign-up` is `permitAll`. The only thing in front of it is
`PortalTokenFilter`'s per-IP fixed window at `PORTAL_RATE_LIMIT:60`/min, keyed on
`getRemoteAddr()` and held in a per-instance `ConcurrentHashMap`. Putting
`GhlWriteClient.upsertContact` behind that matcher made **an unauthenticated stranger's HTTP
request into a write on the live CRM**, with nothing between the two but a counter an IP pool
defeats.

What that buys an attacker, in order of severity:

1. **GHL's 100-requests-per-10-seconds-per-location budget.** `GhlHttp.MIN_REQUEST_INTERVAL`
   spaces EvalOS's own calls at 110ms precisely because that budget is shared. A signup flood
   spends it, `GhlFailure` starts answering, and **every GHL-backed staff screen 502s** — the
   client signup form becomes a way to take down the sales desk.
2. **Contacts in the sub-account Sales works in.** Not EvalOS's rows to delete; someone cleans
   them up by hand, in GHL.
3. `client_account`, `contact_snapshot` and an **append-only** `audit` row per attempt. The audit
   row is the one that cannot be swept, by invariant.

None of this needed the contact to be there. The address was unproven at that moment — that is
exactly what **D4** says — so the row it created was a CRM record for a mailbox nobody had shown
they could open.

### 8.2 The rule

**Proving the mailbox is what creates the contact.** `setPassword` is that moment: it spends a
single-use `client_credential_token` that only ever reached the client's own inbox, sets the
password, and returns the session that opens the dashboard. The contact is written there.

| Step | Before | After |
|---|---|---|
| `POST /auth/sign-up` | GHL contact + account + snapshot | **account only** (EvalOS rows, ours to sweep) |
| set-password link clicked | password set, session minted | password set, **contact upserted + linked + snapshot written**, session minted |
| Client picks a service | opportunity created (D10) | **nothing** — §9 moved it to submit |

Sign-up is now an entirely local write. A flood costs junk in two EvalOS tables that a sweep can
clean, and **nothing leaves the JVM**.

### 8.3 Provenance: `source`, because GHL will not take UTM

The business asked for the contact to carry a UTM source identifying the client portal.
**GHL's public API does not accept attribution on a write.** Verified against
`marketplace.gohighlevel.com/docs/ghl/contacts/create-contact` and `.../upsert-contact`: neither
body documents `attributionSource`, `utmSource`, `utmMedium`, `sessionSource`, `campaign` or
`referrer`. Confirmed against the live sub-account too — the contact this flow created on
2026-09-16 carries `createdBy.source: "INTEGRATION"` and no attribution block at all. GHL
populates those fields from its own tracking on forms and funnels, and a portal-native signup
never touches one.

The writable field is `source` (string, "Source from which the contact was created"). So:

- A portal signup sends **`source: "Client Portal"`**.
- `MarketingLeadService` and `SalesDeskService` name themselves the same way, because a `source`
  that only one of three callers sets is worse than none.

If true UTM attribution is ever required, it cannot be done from here — the contact would have to
enter through a GHL form carrying the parameters, which is the opposite of a portal-native signup.
That trade is recorded rather than worked around.

### 8.4 The failure this move exposes, and its fix

`ClientApplicationService.linkOpportunityIfMissing` returns **silently** when
`client.getGhlContactId() == null`. Today that is nearly unreachable. After this move it is the
state of any client whose set-password landed during a GHL outage — and it would leave them able
to file requests that no salesperson ever sees, with no error anywhere.

So the null branch stops being a silent return and **creates the contact**. It is the one place
that genuinely needs the id, it already tests for it, and a backfill there also covers the `V45`
accounts seeded from snapshots that carried no `ghl_contact_id`.

A GHL outage during set-password therefore **does not refuse the set-password** — locking a client
out of their own account over a third party's downtime is the worse failure — it defers the
contact to the first moment one is actually required.

### 8.5 Acceptance

- [ ] `signUp` makes no outbound call; `GhlWriteClient` is not reachable from it.
- [ ] `setPassword` upserts the contact with `source: "Client Portal"`, links it, writes the
      snapshot, and still returns a session if GHL refused.
- [ ] `linkOpportunityIfMissing` creates a missing contact instead of returning silently.
- [ ] A seeded client with no `ghl_contact_id` gets one on their first request.
- [ ] Signing up 100 times reaches GHL zero times.

---

## 9. Amendment, 2026-09-16 — the opportunity is created at submit

**This reverses §3, which resolved the same question the other way earlier the same day.** §3 is
edited above; the compromise it describes (deal at service-pick + a `SUBMITTED` marker) is gone.

### 9.1 What changed and why it is not a reversal by accident

The requirement always said the opportunity is created **when the client submits**. §3 refused
that on 2026-09-15 and again on 2026-09-16 in favour of `D10` — the deal opened at service-pick so
that a client who abandoned the questionnaire still reached a salesperson — and offered the
`SUBMITTED` custom field as the way to satisfy what the requirement was *for*.

**The business asked a third time, and the third asking carries it.** The argument against was made
twice and is on record; repeating it a fourth time would be substituting EvalOS's judgement for the
business's on a question that is theirs. The funnel is now what they asked for:

    submit → opportunity → Sales review → won → payment

### 9.2 What that costs, stated plainly

**An abandoned questionnaire now reaches nobody.** The `client_application` row survives with
`status = DRAFT` and nothing reads it, sweeps it or reports it. That is the exact failure `D10`
existed to prevent, and it is now accepted rather than solved. It is filed as **Q11** in
`open-decisions.md` with a recommendation (a staff list of stale drafts — no GHL write, no new
table), because the fix is a screen and the unresolved part is whose job the chase is.

### 9.3 The second call is deleted, not kept

`markSubmittedInGhl` and its `setOpportunityFields` call are gone. Every opportunity is now a
submitted one, so a follow-up announcing it would say nothing that the row's existence does not.
The field is still **written on the create**, so a GHL workflow already keyed on it keeps firing —
`evalos.ghl.opportunity-submitted-field` still means what it meant, and blank still omits it.

One create now carries all three custom fields: service id (routing), correlation key (Unit 44d's
retry-after-timeout answer) and `SUBMITTED`.

### 9.4 A failed create refuses the submit

Stricter than before, and it follows from the change rather than being an extra rule. The old
swallow was correct when the deal already existed and only a marker was missing; now a failed
create means **Sales has no deal at all**, and answering "sent" to that is the one lie this flow
must not tell. The draft and the answers survive, and the next attempt retries against the same
correlation key.

### 9.5 Acceptance

- [ ] `start` and `save` reach GHL zero times.
- [ ] `submit` creates the opportunity, carrying service id, correlation key and `SUBMITTED`.
- [ ] `setOpportunityFields` is not called from this service at all.
- [ ] A GHL refusal at submit answers 502 and leaves the application `DRAFT`.
- [ ] A retry reuses the local opportunity row rather than minting a second correlation key.

---

## 10. Amendment, 2026-09-16 — GHL sends the mail, so the contact comes back to sign-up

**This reverses §8's placement and keeps §8's reason.** §8 is edited above; D3a is rewritten to
state the *property* it was always about, and D3d records the new mechanism.

### 10.1 The constraint that forced it

The business's flow routes the set-password mail through GHL. `POST /conversations/messages`
**requires `contactId`** — verified against `/docs/ghl/conversations/send-a-new-message`, and a
`conversationId` is no escape because a conversation belongs to a contact. There is no
contact-less send.

So the ordering §8 chose is not available: the mail that leads to set-password needs the contact
that §8 created *at* set-password. One of the two had to move, and the mail is the requirement.

### 10.2 What actually protected anything, restated

§8's argument was never "later is safer" — it was **a stranger must not be able to drive unbounded
writes into the live CRM**, because an IP-rotating script fills the sub-account Sales works in and
spends GHL's shared 100-per-10-seconds location budget, which makes every GHL-backed staff screen
answer 502. Delay was one way of getting that. It is not the only one, and it was never the part
that mattered.

Three things carry it now:

| | Status |
|---|---|
| `PORTAL_CLEANUP` removes what a flood leaves in EvalOS | **built** (§ sweep, 2026-09-16) |
| Sign-up gets its own budget, not the shared 60/min/IP | **not built** |
| A proof-of-human gate on `/auth/sign-up` | **not built** |

**The last two are outstanding and this spec does not pretend otherwise.** Until they exist the
exposure §8 closed is open again, with only the cleanup sweep behind it. A tighter budget is a
config value and a second counter; the gate needs a decision about a dependency (Turnstile is the
obvious one — Cloudflare already fronts the origin) and that decision has not been taken.

### 10.3 A GHL outage refuses nothing

Sign-up stands with no contact. `identify` then answers `MAIL_UNAVAILABLE` — which is *true* rather
than a guess, because the transport genuinely cannot address someone it has no contact for — and
`ensureCrmIdentity` repairs the link at the next sign-in, or at the first request (D3c). Losing a
sign-up to a third party's downtime would be the worse failure, and the client is not stranded:
the screen tells them to get in touch, which is what `MAIL_UNAVAILABLE` is for.

### 10.4 Mail became a service, because it will change again

`MailTransport` is an interface with two implementations and a third expected. That is the whole
justification — a seam under two messages would otherwise be ceremony, and the rule here is no
interface with one implementation. The answer to "who sends the mail" has already changed once
(SMTP → GHL) and Brevo is named as next.

- `evalos.mail.transport` picks one **by name** from the beans on the classpath. An environment
  change, not a build — the day a sending domain is being re-verified, the fix is a variable.
- **A name matching nothing fails at startup and names what it found.** A silent fallback is
  discovered by a client who never got their link.
- `Recipient` carries **both** an address and a `ghlContactId`. GHL addresses a contact; SMTP and
  Brevo address an address. A recipient carrying only the intersection would make GHL
  unimplementable.
- Callers ask **`canReach`**, not `isConfigured`. The GHL transport can be perfectly configured and
  still unable to address a client with no linked contact — and treating that as "configured, so
  send" mints a credential token for a link that never leaves, whose cooldown then suppresses the
  retry for a full TTL.
- The send is **audited** (`PORTAL_LINK_ISSUED` on the contact's audit key), carrying the contact
  id, the subject and GHL's message id — **never the link**, which is the credential, and never the
  address, which is PII `GhlWriteClient` already keeps out of that table. `GhlHttpTest`'s
  structural rule caught this class; it was right to.

### 10.5 Acceptance

- [x] Sign-up upserts exactly one contact per new account, and none for an address already held.
- [x] The contact is created **before** the mail, asserted in order.
- [x] Sign-up still answers a state and never a token.
- [x] `evalos.mail.transport=ghl` sends via `/conversations/messages` with `contactId`.
- [x] An unknown transport name refuses to start.
- [x] A configured transport that cannot address this person sends nothing and reports false.
- [x] Sign-in repairs a missing contact ("login verify and update if something missing").
- [ ] **Sign-up has its own rate budget** — not built.
- [ ] **Sign-up has a proof-of-human gate** — not built, needs a dependency decision.
- [ ] **Phone matches an EvalOS account**, not just a GHL contact — not built, see §10.6.

### 10.6 Phone matching is not built, and the rule it needs first

The flow says *check ClientAccount by email/phone*. GHL's half already works — `upsertContact`
matches email then phone on its side, which is step 4 of the flow and needs nothing from EvalOS.

The EvalOS half is a different thing and is **deliberately not built yet**, because it has a
takeover shape that needs a written rule before code: if a typed email and a phone-matched account
disagree, linking the typed address to that account hands a stranger somebody else's cases by
typing their phone number. The rule that makes it safe is *the link always goes to the matched
account's stored email, never the typed one* — and a shared number (a family, an office) then means
two people resolve to one account, which is a product decision rather than a technical one.

---

## 11. Amendment, 2026-09-17 — Brevo replaces GHL mail, and the contact goes back off sign-up

**This restores §8 and closes §10.** §10's placement was never a preference; it was a constraint,
and the constraint is gone.

### 11.1 What changed

GHL no longer sends the set-password mail. `BrevoMailTransport` does —
`POST https://api.brevo.com/v3/smtp/email`, header `api-key`, a text-only body. The `ghl`
transport is deleted outright rather than kept as an option: it addressed a `contactId`, and
keeping a transport that can only reach people who already have a CRM row would keep the whole
problem §10 describes.

**The seam paid for itself.** Swapping providers was one new class and one changed value of
`evalos.mail.transport`. Nothing that knows what a set-password mail *says* moved, which is what
§10.4 claimed the interface was for.

### 11.2 The contact is off sign-up again (D3d → D3a's ordering)

§10.1 put it there because `POST /conversations/messages` required a `contactId`. Brevo takes an
address, so **there is no longer any reason for an unauthenticated route to write to the live
CRM**, and the exposure §8.1 describes is not worth carrying for a placement nothing needs.

**Two call sites, and this is the part worth remembering.** `signUp` called `ensureCrmIdentity`
directly *and* fell through to `identify` → `issueCredential`, which called it again — the second
added in review to repair an account the GHL transport could not address. Removing only the direct
call would have left sign-up writing to GHL by the second path while looking fixed.
`signingUpReachesGhlZeroTimes` drives a hundred sign-ups and asserts `verifyNoInteractions`, which
is what catches that class of half-fix.

`persistCrmLink` went with them: it existed because `identify` and `forgotPassword` are not
transactional, and every remaining caller of `ensureCrmIdentity` is.

### 11.3 Where the contact is created now

| Moment | Authenticated by |
|---|---|
| `setPassword` | a single-use token that only reached the client's own inbox |
| `signIn` | a password |
| first request needing a deal | a portal session (D3c) |

None of them is reachable by a stranger with a script, which is the whole of D3a's property.

### 11.4 What this costs

A client who signs up and never opens the mail is invisible to Sales until they do. That is D3a's
original trade, taken again knowingly: an unproven address is not yet a lead, and the row is
`created_via = 'SIGNUP'` so `PORTAL_CLEANUP` clears it after 30 days.

**It also makes the outstanding work smaller rather than larger.** §10.2 listed a tighter sign-up
budget and a proof-of-human gate as owed, because a `permitAll` route was writing to the CRM. It no
longer is: a flood now costs two EvalOS rows and an append-only audit row per attempt, all of them
ours and two of them swept. A gate is still worth having — the audit table grows and mail can be
aimed at an address — but it stopped being the thing standing between a script and the sales desk.

### 11.5 Acceptance

- [x] `evalos.mail.transport=brevo` sends via `POST /v3/smtp/email` with `api-key`.
- [x] `GhlMailTransport` is deleted; `Recipient` no longer carries a contact id.
- [x] The send is audited once, in `ClientMailer`, carrying subject and brand and never the link.
- [x] A hundred sign-ups reach GHL zero times, by either path.
- [x] Sign-up still answers a state and never a session.
- [ ] `EVALOS_MAIL_BREVO_API_KEY` / `_SENDER_EMAIL` set, and the sender verified in Brevo — **not
      done, and prod defaults to `brevo`**: until both are set every client answers
      `MAIL_UNAVAILABLE`.
- [ ] A proof-of-human gate on `/auth/sign-up` — still worth having, no longer urgent.
