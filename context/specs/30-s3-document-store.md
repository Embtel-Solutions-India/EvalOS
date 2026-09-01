# Unit 30 — S3 document store, and the shared client identity

> **Status: SPECCED (2026-09-02), not built.** This is a **pivot spec**: it replaces a
> decision that is already in the codebase, so it is written and agreed before any code
> moves. It supersedes the Google Drive document architecture wherever the two disagree.
>
> **⚠ CORRECTED 2026-09-02, same day.** The first draft of this spec said the Client Portal
> was a **separate application that writes to S3**, with EvalOS read-only on `client/`. That
> is wrong. The Client Portal is a **separate frontend whose backend is EvalOS**: the client
> SPA calls EvalOS endpoints, and **EvalOS writes to S3**. §3's IAM split and §4's
> "listing somebody else's tree" are corrected below. The **shared client identity** (§2) is
> unaffected and still holds.
>
> **It amends invariant 14**, deletes the "No object storage" paragraph, and rewrites the
> *Raw documents* row of the stack table. Those edits are made in `architecture.md` as
> part of this unit, not left to be discovered later. (Invariant 13 is the audit-trail
> invariant and is untouched — every presigned-URL issue writes a row *under* it, which is
> §4's requirement, not an amendment to it.)

**Phase:** 2 — Connect the seams
**Depends on:** 02 (portal auth surface), 04 (case lifecycle), 10 (checklist), 14 (portal
token model)
**Supersedes:** the Drive halves of Units 13, 15 and 21
**Unlocks:** Units 13, 15 and 21, all three of which have been blocked on a credential
that never arrived

---

## 1. Why this exists

Two things changed at once, and they are one change.

**Documents move to an S3 bucket, and EvalOS is the only thing that writes to it.**
Clients upload through a **separate Client Portal frontend** — a different SPA, a different
deployment — but that frontend has **no storage credential and no AWS access**. It calls
**EvalOS's portal API**, and EvalOS streams the bytes to S3. Google Drive leaves the
architecture completely: the API client, the service account, the dependency and the
`drive_link` column.

**S3 holds the client's uploaded documents (scans and images) and the draft PDFs**, plus
the redacted expert profile and the expert's signed letter. It is the document store for
everything that used to be a Drive link.

**And the client is one client everywhere.** The Client Portal, EvalOS and GHL identify
the same person by the same **Client ID and email address**. This is not a new identity
model — it is the existing one (invariant 7: `ghl_contact_id` is canonical, email is a
fallback) extended to a third system and stated as a contract rather than an assumption.

### What this unblocks, which is the practical point

Units 13, 15 and 21 all need one Google service account, and `00-build-plan.md` has
called it "the single most valuable thing to chase" for weeks. **Unit 13 is code-complete
and stuck on it. Units 15 and 21 are not built at all.** The pivot does not work around
that blocker; it deletes it. The credential this architecture needs instead is an AWS one
the business already controls.

### What this is not

- **Not a re-hosting of documents inside EvalOS.** The bytes live in S3. EvalOS holds
  object keys, exactly as it held Drive links — see §7 on what invariant 14 becomes.
- **Not a new auth surface.** The Client Portal frontend authenticates against EvalOS's
  **existing** portal chain (Unit 14's `portal_access` token model, `audience = 'CLIENT'`).
  A separate frontend is a deployment fact, not an authentication one.

### The same frontend hosts the Expert Portal (confirmed 2026-09-02)

**One external frontend deployment, two portals, one backend — this one.** Beside the client's
surface it carries the **expert portal**: the expert downloads the client-approved draft, signs it
in their own tool, and uploads the signed document back. Further expert functionality is to be
specified later; what is settled is the deployment shape and that sign step.

**This changes nothing about the auth model, which is the point of checking.** `portal_access`
already carries an `audience` column constrained to `CLIENT` and `EXPERT` (V21), `PortalAudience`
already maps each to its `ActorType`, and the portal filter chain already matches
`/api/portal/**`. Two audiences on one chain was Unit 14's design and Unit 15's plan; a second
frontend consuming it is not a new surface.

**The signed upload is EvalOS's own**, under `case/{caseId}/signed/` — an expert's letter is not a
client document and must not land under `client/`. It is the one upload EvalOS accepts, it
streams, and Unit 15's provenance model (hash pair, attestation, `EXPERT` audit row) is untouched.
**Unit 31 already gave it a home**: `case_document` with `kind = 'SIGNED_LETTER'`.

### ⚠ CORS — a real gap, and it belongs to this unit

**There is no CORS configuration anywhere in the codebase.** That was correct while every caller
was same-origin. Two portals on a separate frontend origin are not: the browser will preflight
every non-simple request, and with no `Access-Control-Allow-Origin` **every portal call fails
before it reaches a filter** — including the ones that work perfectly in a curl test, which is how
this kind of gap survives a backend-only check.

It is filed here rather than in Unit 15 because it is one configuration for both portals, and
discovering it twice is worse than deciding it once.

- **Allow the portal origin explicitly, per environment.** Never `*` — the portal chain carries a
  bearer token in a header, and a wildcard origin on a credentialed API is the mistake this note
  exists to prevent.
- **Scope it to `/api/portal/**`.** The staff API is same-origin and has no reason to answer a
  cross-origin preflight.
- **`X-Portal-Token` must be in the allowed request headers**, or the preflight passes and the
  real request arrives unauthenticated — a 401 that looks like a token problem and is not.

- **Not AI review of uploads.** Ruled out in Unit 21 and Unit 20 and still ruled out.

---

## 2. The shared client identity

### The contract

| Field | Source of truth | In EvalOS | In the Client Portal |
|---|---|---|---|
| **Client ID** | **GHL** — the contact id | `contact_snapshot.ghl_contact_id` | the same string, verbatim |
| **Email** | GHL contact record | `contact_snapshot.email` | the same address |

**The Client ID is GHL's contact id and nothing else.** EvalOS must not mint its own
client identifier for the portal to mirror, and the portal must not mint one for EvalOS
to adopt. Both read GHL's. This is invariant 7 restated across a system boundary, and it
is what makes an S3 key written by the portal resolvable by EvalOS without a lookup table
between them.

### Email is still a fallback, and V27 is the reason

The requirement says the Client ID **and** email are consistent across all three systems.
**Consistent is not the same as co-equal**, and this spec must not be read as reviving the
key that `V27__contact_email_is_a_fallback_key_only.sql` deliberately demoted.

That migration exists because two distinct GHL contacts can share an inbox — an attorney,
a family, a shared office address. When email was treated as identity, intake found the
first client's row, overwrote it with the second client's details, and **attached a case
to the wrong client**. So:

- **Identity is the Client ID.** Every S3 key is built from it.
- **Email is a fallback and a cross-check.** It identifies a client only where no Client
  ID exists, and where both are present and disagree, the Client ID wins and the mismatch
  is recorded rather than silently resolved.
- **No S3 key contains an email address.** Beyond the identity argument, an email in an
  object key is PII in a log line, a bucket listing and every access record that names it.

### The failure this creates, named so it is designed for

A client can exist in the Client Portal before EvalOS has ever heard of them — EvalOS
takes custody at `opportunity.won`, and a client may upload documents earlier than that.
So **EvalOS will encounter object keys for Client IDs it has no `contact_snapshot` for.**

That is expected, not an error. Those objects are simply not yet attached to a case;
intake resolves them when the case is created. **An unrecognised Client ID must never be
treated as a reason to reject or delete an object** — EvalOS does not own that bucket's
contents and must never assume its own ignorance means the data is wrong.

---

## 3. Storage architecture

### One bucket, two owners, split by prefix

```
s3://<evalos-documents-bucket>/
├── client/{clientId}/{caseCode|inbox}/{documentId}-{filename}   ← Client Portal writes · EvalOS READS ONLY
└── case/{caseId}/
    ├── draft/{version}-{filename}                               ← EvalOS writes
    └── signed/{filename}                                        ← EvalOS writes (Unit 15, expert's upload)
```

**EvalOS is the only writer, and the prefixes are for humans rather than for IAM.**
The correction above removes the read-only-on-`client/` split: there is no second system
with a credential, so there is nothing for IAM to separate. The prefixes stay because they
make a bucket listing legible and make a lifecycle rule expressible — `client/` is what a
client sent us, `case/` is what we produced — but **the access control that matters is
EvalOS's own scope check**, which is the same one that guards the case.

**That is a real reduction in defence and it should be named rather than glossed.** The
first draft could say a client's uploaded evidence was safe from EvalOS overwriting it
because the credential forbade it. That guarantee is gone. What replaces it: **bucket
versioning is now non-optional** (an overwrite is recoverable), and **EvalOS never
overwrites a `client/` key** — every upload writes a new key, and that is a code rule
backed by a test rather than by IAM.

**The Client Portal frontend holds no AWS credential.** It uploads by calling EvalOS,
which streams to S3. A presigned **PUT** handed to the browser was considered and rejected:
it would let the client write a key directly, which puts the key format — the thing that
makes a document findable — in the hands of the least controlled party in the system, and
it skips the content-type and size checks that must happen somewhere.

### Why one bucket rather than two

Two buckets would make the ownership split physical rather than a prefix, which is
tidier on paper. It is rejected because the split is already enforced by IAM — the
stronger mechanism — and a second bucket doubles the configuration, the credential
rotation and the environment-parity surface for a boundary that is already held. One
bucket, two prefixes, two policies.

### Bucket configuration (required, not optional)

| Setting | Value | Why |
|---|---|---|
| Public access | **Blocked, all four settings** | Client identity documents. There is no case in which a public read is correct |
| Encryption | SSE-S3 at minimum; SSE-KMS if the business has a key | At-rest requirement for identity documents, and it is one checkbox |
| Versioning | **On** | The only defence against an overwrite of a client's document. Cheap; the alternative is unrecoverable |
| TLS | Enforced by bucket policy (`aws:SecureTransport`) | A policy, so it cannot be forgotten by a client library's default |
| Lifecycle | Deferred — **open question (a)** | Retention was Drive's problem and is now ours. See §9 |

### Per-brand isolation

**Open question (b).** EvalOS is brand-scoped by default and the bucket is currently
specified as one. A single bucket holding both brands' client documents relies entirely on
`case_id`/`clientId` scoping in application code, where every other store in EvalOS also
enforces brand at the row. The candidate answers are a `brand/{brandId}/` segment at the
top of every prefix, or a bucket per brand. **This must be decided before the key format
is built**, because changing a key format after objects exist is a migration of the data
rather than of the code.

---

## 4. How EvalOS reads

### Fetching a document

EvalOS never proxies bytes to a browser and never buffers an object on the heap. Staff and
portal users receive a **short-lived presigned GET URL** generated per request:

- **Lifetime: 5 minutes.** Long enough to click, too short to be worth forwarding.
- **Generated per request, never stored.** A presigned URL in a database column is a
  credential in a database column.
- **Issued only after the caller passes the same scope check that guards the case.** The
  presigned URL is the *last* step, not the authorization: a URL minted before the check
  is a URL that leaked before the check.
- **Every issue writes an audit row.** Who asked for which object, and when. This is the
  EvalOS-side record that a document was accessed, and it is the same append-only trail
  every other act uses.

This replaces the Drive link the case used to carry. A Drive link was a *permanent*
capability held in a column and mailed around; a presigned URL is a bounded one. The pivot
improves this rather than merely relocating it, and that is worth stating because it is
the one place the new architecture is strictly safer than the old.

### Knowing what a client uploaded

**EvalOS wrote it, so EvalOS knows.** The correction removes the polling problem entirely:
an upload is an EvalOS request, so it writes the `document_checklist_item` row and the
audit row **in the same transaction** as the object write. There is no reconciliation
sweep, no listing of somebody else's tree, and no "arrived but unnoticed" window.

**Ordering matters and has one correct answer:** stream to S3 **first**, then commit the
row. The other order leaves a row pointing at an object that does not exist — a broken
link on the Coordinator's screen. This order can leave an object with no row, which is
invisible, costs storage and is cleaned up by a lifecycle rule. **Prefer the orphan to the
dangling pointer.**

**Notification is immediate and is Unit 06's existing mechanism**: the upload publishes an
event, the Coordinator is notified. Open question (c) is closed by this correction.

---

## 5. Data model changes

| Change | Table | Note |
|---|---|---|
| **Drop** `drive_link` | `evalos_case` | Replaced by a derived prefix. See below |
| **Change** `draft_link` | `evalos_case` | Becomes an S3 object key rather than a Drive URL. **The column's meaning changes, so the migration must state it** — a URL and a key are not interchangeable and nothing may guess which one a row holds |
| **Add** `object_key` | `document_checklist_item` | The `client/` object that satisfies this item. Nullable — an item is required before it is met. This is the association §4 describes |
| **Add** `uploaded_at` | `document_checklist_item` | When the object was observed, not when the client uploaded it. **Two different facts; the column name must not blur them** |
| **No new blob column anywhere** | — | Invariant 14's surviving half |

**`drive_link` is dropped rather than renamed.** A client's document location is now
*derivable* — `client/{clientId}/` — from the contact the case already points at, so
storing it is storing a second copy of a fact the schema already holds, which is the
duplication this codebase deletes on sight. `draft_link` survives because a draft's key is
**not** derivable: it names one file among several versions.

**Migration ordering matters** and must be one file: the checklist columns are added, the
`draft_link` meaning is documented, and `drive_link` is dropped last, so a partially
applied migration never leaves a row whose document location cannot be found by either
scheme.

---

## 6. Requirements

### Functional

| # | Requirement |
|---|---|
| **F1** | EvalOS resolves a client's documents by **Client ID**, which is GHL's contact id, shared verbatim with the Client Portal |
| **F2** | EvalOS accepts client uploads through the portal API, **streams** them to `client/{clientId}/` under a newly generated key, and **never overwrites an existing one** |
| **F3** | EvalOS writes its own artefacts — draft, redacted expert profile, signed letter — under `case/{caseId}/` |
| **F4** | A staff or portal user opens any document through a **presigned GET URL valid for 5 minutes**, issued only after the case's own scope check passes |
| **F5** | Every presigned URL issued writes an append-only audit row naming the actor and the object |
| **F6** | The Coordinator associates an arrived object with a checklist item; the item moves to `UPLOADED`, and their review sets `APPROVED`, `MISSING` or `INCORRECT` |
| **F7** | An upload for a client EvalOS has no case for is refused at the API, not written and orphaned — the portal token is scoped to a case, so this cannot arise silently |
| **F8** | Where a Client ID and an email disagree about which client an object belongs to, **the Client ID decides** and the disagreement is recorded |
| **F9** | Google Drive is absent from the codebase: no client, no config, no credential, no dependency, no column |

### Non-functional

| # | Requirement |
|---|---|
| **N1** | **No object's bytes are written to EvalOS disk, heap or database.** EvalOS streams or presigns; it never buffers. Asserted by test, as invariant 14's Drive version was |
| **N2** | The bucket blocks public access, encrypts at rest and requires TLS in transit |
| **N3** | Versioning is on, so an overwrite is recoverable |
| **N4** | An S3 outage degrades to an explicit "documents are unavailable" state on the screen — **never to an empty checklist**, which reads as "the client sent nothing" and is a lie that causes a chase |
| **N5** | The AWS credential is environment-supplied, absent from every committed profile, and asserted so by the existing `ConfigSecretsTest` |
| **N6** | The local profile boots and every non-document route works with **no AWS credential at all**, exactly as `evalos.drive.required=false` allows today |

---

## 7. Invariant changes

### Invariant 14 — rewritten

> **Was:** "EvalOS hosts no files and sends no email. Documents are Drive links, the
> signed letter is filed into the case's Drive folder…"

> **Becomes:** "EvalOS hosts no files and sends no email. Documents are **objects in the
> S3 document store, referenced by key**; EvalOS stores no bytes on disk, on the heap or
> in a column, and reads them only through short-lived presigned URLs it issues after a
> scope check."

**The testable property is unchanged and that is the point.** "Hosts no files" always
meant *stores none*, not *accepts none* — Unit 21 was allowed to take an upload because
the bytes streamed through. The same property now holds for a different backing store, and
it must stay a test rather than a convention.

### The "No object storage" paragraph — deleted

`architecture.md` says **"No object storage. EvalOS hosts no files."** The first sentence
is now false and is removed; the second remains true and is what invariant 14 carries. A
document store EvalOS reads is object storage, and pretending otherwise to preserve a
sentence is how a document stops being trustworthy.

**This is the second invariant this project has reversed** — Unit 29 reversed invariant 2
and then reverted it. The lesson recorded there applies here: an invariant is reversible,
but the reversal is written down at the moment it happens, in the document that states it,
with the reason. A codebase whose stated invariants and actual behaviour disagree has
neither.

### Invariant 13 — untouched, and used

13 is the append-only audit invariant. Nothing in it changes. **It is what §4's
presigned-URL audit row is written under** — "who opened which document" is a state fact
about access, recorded through the same append-only trail as every other act, with no new
mechanism.

### Invariant 7 — reinforced, not changed

`ghl_contact_id` remains the canonical client identity. This unit extends its reach to a
third system and to the S3 key format. **V27 stands**: email is a fallback key only.

---

## 8. User stories and acceptance criteria

### US-1 — A client's documents reach the Coordinator

> **As a** Project Coordinator
> **I want** the documents a client uploaded in the Client Portal to appear against my
> checklist
> **So that** I can chase what is genuinely missing rather than what I cannot see.

**Acceptance criteria**

- **Given** a client with Client ID `X` has uploaded two files in the Client Portal,
  **when** the Coordinator opens the case checklist, **then** both objects are listed
  under `client/X/` and can be associated with checklist items.
- **Given** an object is associated with a checklist item, **then** that item's status is
  `UPLOADED` and the audit trail records who associated it.
- **Given** the Coordinator marks an item `INCORRECT`, **then** the item is chaseable
  again and **the object is not deleted** — EvalOS does not delete from `client/`.
- **Given** S3 is unreachable, **then** the checklist states that documents cannot be
  loaded and **does not** render as an empty list.

### US-2 — Opening a document

> **As** staff with access to a case
> **I want** to open a client's document
> **So that** I can evaluate it.

**Acceptance criteria**

- **Given** I may read the case, **when** I open a document, **then** I receive a
  presigned URL valid for **5 minutes** and an audit row is written naming me and the
  object.
- **Given** I may **not** read the case, **then** no URL is issued and the refusal is a
  403 — **and the scope check runs before the URL is minted**, asserted by test.
- **Given** a presigned URL older than its lifetime, **then** S3 refuses it. EvalOS does
  not need to do anything for this to be true, which is the reason for choosing it.

### US-3 — One client across three systems

> **As** the business
> **I want** one client to be one client in GHL, the Client Portal and EvalOS
> **So that** a document uploaded in one place is found by the case in another.

**Acceptance criteria**

- **Given** GHL contact `C` becomes an EvalOS case, **then** that case's client documents
  resolve at `client/C/` with no mapping table anywhere.
- **Given** two GHL contacts share an email address, **then** they remain two clients and
  their documents remain separate — **the V27 regression test**, restated for S3.
- **Given** an object exists for a Client ID EvalOS has never seen, **then** it is
  retained, ignored, and picked up if that contact later becomes a case. No error, no
  deletion.
- **Given** a Client ID and an email disagree, **then** the Client ID decides and the
  disagreement is recorded.

### US-4 — The expert's signed letter (Unit 15's half)

> **As an** expert
> **I want** to upload my signed letter through my portal link
> **So that** the case can be delivered.

**Acceptance criteria**

- The signed file is written to `case/{caseId}/signed/` — **EvalOS's own prefix**, because
  this upload comes through EvalOS's portal and is not a client document.
- The bytes stream; nothing is buffered (**N1**).
- The existing provenance model is unchanged: hash pair, attestation, `EXPERT` audit row.
  **This unit changes where the file lands and nothing about how it is trusted.**

### US-5 — The redacted expert profile (Unit 13's half)

> **As a** Project Manager
> **I want** the redacted expert profile filed with the case
> **So that** the client can see who is doing the work without seeing who they are.

**Acceptance criteria**

- The generated profile is written to `case/{caseId}/redacted-profile/`.
- **The redaction rules are untouched.** Unit 13's whole substance is *what* is removed;
  this unit changes only where the result is put.
- **EvalOS gains a PDF library, and this is a real cost.** Drive's export produced the PDF
  for free — §9, open question (d).

---

## 9. Open questions

| # | Question | Blocks | Default if unanswered |
|---|---|---|---|
| **(a)** | Retention/lifecycle policy for client documents. Drive held this and now nobody does | Nothing yet; needed before go-live | Keep indefinitely, versioned. Deleting client evidence on a guess is the worse error |
| **(b)** | **Per-brand isolation**: `brand/{brandId}/` segment, or a bucket per brand? | **The key format — decide before building** | A `brand/` segment. Brand-scoping at the row is the standing rule and a key is the S3 equivalent of a row |
| ~~**(c)**~~ | ~~Can the Client Portal emit an upload event?~~ **CLOSED by the correction** | — | EvalOS writes the object, so it publishes the event itself. No webhook, no polling |
| **(h)** | **CORS: which origin(s), per environment?** The external frontend hosts both the client and expert portals; nothing is configured today | Every portal call from the browser | Allow the one portal origin per environment, on `/api/portal/**` only, with `X-Portal-Token` in the allowed headers. **Never `*`** |
| ~~**(d)**~~ | ~~PDF generation~~ **CLOSED 2026-09-02 by removing Unit 13.** The redacted profile was the only thing needing conversion; with it gone, nothing in EvalOS generates a document. **The problem was removed, not solved** — no PDF library, no dependency decision | — |
| **(e)** | Bucket name, region, and account per environment | Implementation | — |
| **(g)** | **A12's inline draft comments.** `process-automation.md` records "comments visible inline on the draft" as satisfied by **Drive's own commenting**. **S3 has no commenting** | A12's *inline* half. The notification and the return reason are unaffected | Return comments live in EvalOS (the PM return reason already exists and is already shown); "inline on the document" has no home and must stop being recorded as covered |
| **(f)** | Does the Client Portal already exist and write these keys, or is it also being built? | The integration contract in §3 is a **contract**, so it must be agreed by both sides before either builds to it | — |

**(b) and (f) should be answered before code starts.** (d) is closed. The others can be settled while
it is written.

### Two capabilities Drive was quietly providing

Neither is a reason not to do this. Both are costs that must be paid deliberately rather
than discovered after the credential is revoked.

1. ~~**PDF conversion (d).**~~ **No longer owed.** Drive's export turned the generated
   redacted profile into a PDF for free. Unit 13 is removed (2026-09-02) and it was the
   only generator, so this debt was cancelled rather than repaid — a reminder that the
   cheapest way to pay for a capability is sometimes to stop needing it.
2. **Document commenting (g).** Drive's commenting was the *entire* mechanism behind A12's
   "comments visible inline on the draft". S3 has no equivalent, and building one is a
   feature, not a migration.

The pattern is worth naming: **an integration is not only its API surface.** Drive was
carrying two product behaviours nobody had written down as requirements, and both surfaced
only by auditing what depended on it. Anything else discovered later belongs in this
section, not in a commit message.

---

## 10. What is removed

Concrete, because a pivot that leaves the old path half-present is worse than either path.

**Backend**
- `config/GoogleDriveConfig.java`
- `integration/GoogleDriveClient.java`
- `integration/DriveUnavailableException.java`
- The Drive write path in `service/RedactedProfileService.java` (the generation and
  redaction logic stays; the upload target changes)
- `driveLink` on `Case`, `CaseIntakeService.NewCaseRequest`, and every projection carrying it
- The Drive handler in `common/ApiExceptionHandler.java`

**Configuration**
- `evalos.drive.*` from `application.yml`, `application-local.yml`, `application-prod.yml`
- `GOOGLE_DRIVE_KEY_JSON` from every environment and from the deployment runbook

**Dependencies**
- `google-api-services-drive` and `google-auth-library-oauth2-http` from `backend/pom.xml`,
  plus the `google-api-drive.version` / `google-auth.version` properties

**Database**
- `evalos_case.drive_link`, dropped by the new migration (V5 stays; applied migrations are
  never edited — invariant 9)

**Frontend**
- Drive links and their labels in `DocumentsPanel`, `DraftPanel`, `RedactedProfilePanel`,
  `CaseChecklist`, and the portal's document view

**Documentation**
- The *Raw documents* stack row, the "No object storage" paragraph, the "Drive stopped
  being a link-only external" section, the Drive line in the externals list, invariants 13
  and 14, the Drive-blocked notes in `00-build-plan.md`, and the Drive rows in
  `process-automation.md`

**Specs amended, not deleted:** 10, 13, 14, 15, 21 each get a banner pointing here, the
way `29-sales-desk.md` carries its REMOVED banner. They record decisions that were made,
and the reasoning in them about redaction, portal tokens and checklist vocabulary is
entirely unaffected by where the bytes live.

---

## 11. Verification

1. `./mvnw verify` green, including: the no-bytes-buffered assertion (**N1**), the
   scope-check-precedes-presign assertion (**US-2**), and the V27 shared-email regression
   restated for S3 keys (**US-3**).
2. **No Google artefact remains** — a build-failing grep for `google`, `drive` and
   `Drive` across `backend/src/main`, `frontend/src` and `backend/pom.xml`, in the shape
   of the existing `MigrationTreeTest` and `ConfigSecretsTest`. A pivot needs a test that
   the old path is *gone*, not merely unused.
3. `npm run build` clean; frontend tests green.
4. **The local profile boots with no AWS credential** and every non-document route works
   (**N6**), which is the property `evalos.drive.required=false` protects today and the
   one most likely to be lost in translation.
5. **Six-role scope check** against document endpoints: no role reaches an object outside
   its scope, and no presigned URL is issued before the check.
6. A **supervised first read** against the real bucket, once the Client Portal has written
   an object — the same shape of live acceptance Unit 29 used for its first write.
