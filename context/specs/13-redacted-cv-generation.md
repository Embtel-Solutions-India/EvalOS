# Unit 13 — Redacted CV generation

> # ⚠ **REMOVED (2026-09-02). This spec is history, not a plan.**
>
> The redacted CV is gone from the codebase: `RedactedProfileService`,
> `ExpertProfileController`, `RedactedProfilePanel`, `redactionRules`, the
> `REDACTED_PROFILE` document kind, and the client portal's `expertProfile` /
> `expertReference` fields. `V33` narrows the `case_document` kind CHECK.
>
> **The client is now told nothing about the expert at all** — the stronger position, and
> the one with nothing to get wrong. There is no redaction rule to leak through and no
> generated document to keep anonymous.
>
> **It cancelled a debt rather than paying one.** This unit was the only thing in EvalOS
> that generated a document, so it was the only reason Unit 30 owed a PDF library once
> Drive's free export went away. Removing it removed the problem.
>
> **`AuditAction.EXPORTED` is kept**, and the reason generalises: the audit trail is
> append-only, so an enum must be able to read every value any historical row carries. A
> retired audit action stays readable forever.
>
> **`mayMintPortalLink` was never this unit's** — it only shared a file. It lives in
> `client-portal/portalRules.ts` now, with its test.

> **⚠ AMENDED by Unit 30 (2026-09-02) — output target only.** **The redaction rules are
> this unit's whole substance and none of them move.** What changes is where the generated
> profile goes: `case/{caseId}/redacted-profile/` in **S3**, not the case's Drive folder.
> `GoogleDriveClient`, `GoogleDriveConfig` and the service account are deleted — which is
> what **unblocks this unit**, code-complete and waiting on that credential for weeks.
> **One cost carried, not hidden:** Drive's export produced a PDF for free and S3 converts
> nothing, so the output format is Unit 30's open question (d). See
> `30-s3-document-store.md`.

**Phase:** 2 — Connect the seams
**Depends on:** 11, and — omitted from an earlier draft of this line — 04 (all
three routes read the case through `CaseLifecycleService.read`) and 09 (the panel
mounts into `features/case/CaseDetail.tsx`)
**Unlocks:** the PM's "get the client to approve the expert" step; feeds Unit 14
(the client sees the redacted profile alongside the draft)
**Gating open questions:** **Google Drive API credentials.** A service account,
its JSON key, and write access to the brands' Drive folders do not exist today.
This unit cannot be completed without them — see the decision below.

## Goal

A client or attorney has to approve the expert before the letter is drafted, and
must be able to judge the expert's credentials **without being able to identify
them** — otherwise they contact the expert directly and EvalOS is cut out of the
work it sourced. This unit generates that redacted profile from the roster data
Unit 11 holds, and releases the full profile once the case is paid.

**Verifiable result:** a PM on a case with an assigned expert can generate a
redacted profile that contains the expert's credentials, fields, tier and
experience and **none** of their name, institution or contact details; it is
served on demand and written into that case's Drive folder; and the full profile
is refused on an unpaid case and available on a paid one.

## In scope

- One redaction template and the redaction itself.
- On-demand generation — nothing is cached or stored in Postgres.
- **Writing the generated profile into the case's Google Drive folder**, which
  means a Google Drive API client, a service account, and the credentials config
  for both.
- The paid gate on the full profile.

## Out of scope

- Editing the expert's CV, or accepting an uploaded one. EvalOS holds no files
  (invariant 14); the profile is *generated* from the roster row, which is the only
  reason this can exist at all without object storage.
- Sending the profile to the client. That is a GHL message (Unit 18) or the client
  portal (Unit 14) — this unit produces the artefact and the link, and delivers
  nothing itself.
- A PDF rendering library. See the format decision.
- Drive folder *creation*, permissions management, or reading documents out of
  Drive. This unit writes one file into a folder that already exists.

## The Drive decision, and what it costs

The build plan says the output is "served on demand (**or** written to the case's
Drive folder)". **Decision taken: both.** Served on demand *and* written to Drive.

Stated plainly, because it changes the shape of the phase: this adds the **first
Google Drive API integration in EvalOS**. Until now `Case.driveLink` has been a
string EvalOS stores and never dereferences, and `architecture.md`'s stack table
describes Drive as "link stored on the case, not re-hosted". That line needs
updating with this unit — Drive becomes an outbound integration, not just a URL
column. It is now the **only** external dependency in Phase 2 — the signature provider
that used to be the other one was dropped, and Units 15 and 21 need this same credential
in Unit 15, and it is the reason this unit has a gating open question where the
serve-on-demand-only reading would have had none.

What has to exist before this unit can be finished:

- A **Google Cloud service account** with the Drive API enabled.
- Its **JSON key**, supplied by environment variable (`GOOGLE_DRIVE_KEY_JSON` or a
  path in `GOOGLE_APPLICATION_CREDENTIALS`), bound through `application.yml` the
  same env-backed way `DB_PASSWORD` and `EVALOS_FIELD_KEY` are. **No default
  outside `local`** — an environment that forgets it must fail to start, which is
  the rule `EVALOS_FIELD_KEY` already sets.
- **Write access** for that service account on each brand's case-folder tree
  (either a Shared Drive with the service account as a member, or domain-wide
  delegation). Per brand, because a service account with blanket access to both
  brands' Drives is a cross-brand hole outside the database that no `brand_id`
  predicate can close.

**Dependencies added here and nowhere earlier**
(`ai-workflow-rules.md` — install where it first unlocks behaviour):
`google-api-services-drive` and `google-auth-library-oauth2-http`.

### The folder-id wrinkle

`evalos_case.drive_link` is a **URL**, not a folder id — typically
`https://drive.google.com/drive/folders/<id>`. The client needs the id.

- Parse the id out of the link, accepting the `/folders/<id>` and `?id=<id>`
  shapes — **after** checking the host.
- **Check the host before the shape.** `drive_link` is typed by staff (and may one
  day arrive from GHL), so it is untrusted input, and `https://evil.example/drive/
  folders/<id>` matches the pattern perfectly well. Accept only
  `drive.google.com` (and `docs.google.com`), scheme `https`, exact host match —
  not `endsWith`, which `drive.google.com.evil.example` satisfies. Anything else is
  the same 409 as an unparseable link.
- **A well-formed id is not an owned id.** Parsing proves the shape, not that the
  folder belongs to this brand's Drive — a copy-pasted link from another case, or
  another brand, parses cleanly and the write lands where the wrong people can read
  it. The service account can only write where it has been granted access, which
  bounds the damage but does not stop cross-brand leakage inside one Drive. Before
  the first write, confirm the folder resolves and its parent is the brand's
  configured root; cache that per folder id. If the brand has no configured root
  yet, that is a 409 too — an unverifiable destination is not a destination.
- **A link that does not yield a folder id is a refusal, not a fallback.** Do not
  write to a default folder, the Drive root, or the service account's own space:
  the file would silently land somewhere nobody looks, or worse, somewhere another
  brand can see. Answer 409 naming the case's unusable Drive link so somebody
  fixes it.
- A case with **no** `drive_link` gets the served-on-demand profile and a 409 on
  the Drive write, for the same reason.

## Format

One HTML template. `service/RedactedProfileService` renders it from the expert row.

- **Served on demand** as HTML, rendered in the app and printable.
- **Written to Drive** by uploading that same HTML with a target mime type of
  `application/vnd.google-apps.document`, so Drive converts it to a Google Doc on
  the way in.

**No PDF library.** Drive's own export produces a PDF from the created Doc if one
is ever wanted, so adding `openhtmltopdf`/PDFBox would be a dependency to
duplicate a feature of an integration this unit already has. One template, one
renderer, two destinations.

The template lives in `src/main/resources/templates/redacted-profile.html`. Plain
HTML with placeholder substitution — **no template engine dependency**; the
document has a fixed structure and a dozen fields.

**Every interpolated value is HTML-escaped, without exception.** This is the cost of
not taking a template engine: Thymeleaf would escape by default and the decision above
gives that up, so the escape has to be deliberate and it has to be total. The fields
are roster data — expert name, institution, qualifications — and the roster is
populated by CSV/XLSX **import**, so the content is whatever was in someone's
spreadsheet. An unescaped `<` does not need to be an attack to ruin the document, and
if it is one, the payload runs in a page staff read and in a file published to the
client's Drive folder. Escape `& < > " '` on substitution, in one helper every
placeholder goes through, and let the test assert a field containing
`<script>` renders inert rather than trusting each call site.

## What is redacted, and what survives

The point is a document that is useful and anonymous, so redaction is a
**whitelist of what may appear**, not a blacklist of what must be stripped. A
blacklist is how a field added in a later unit leaks by default.

| Included | Excluded |
| --- | --- |
| Title / academic rank (`title`) | `full_name` |
| `primary_fields`, `secondary_fields` | `institution` |
| `letter_types` | `email`, `phone` |
| `tier` | `payment_detail` (never, anywhere) |
| `total cases completed` (derived, Unit 11) | `notes` — internal, free text, and the single most likely place a name is written |
| Years since `date_onboarded` | `recruitment_source` |
| A generated reference like "Expert A" | `quality_score`, `performance_flags`, `avg_response_hours` — internal assessments |

`notes` and `recruitment_source` are excluded **because they are free text**. Any
free-text field can contain the very name being redacted, so no free-text field
enters the redacted document, whatever it is nominally for.

The reference label ("Expert A") is **stable per case**, derived from the case and
expert ids, so the PM and the client are talking about the same expert across a
conversation — and carries no ordering information about the roster.

## The paid gate

`project-overview.md`: "full profile releases on payment". Since Unit 05a a case
can be worked unpaid, so the gate is `Case.paid`.

- Redacted profile: available whenever an expert is assigned.
- **Full profile: refused unless `case.paid`.** 409, naming payment as the
  reason — the same shape `markDocsComplete` answers with.
- The full profile still **excludes `payment_detail`** (invariant 4). "Full" means
  the expert's identity and credentials, never how they are paid.

## Backend

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | /api/cases/{id}/expert-profile/redacted | GM · Brand Manager · PM · CM | generated on demand; HTML |
| POST | /api/cases/{id}/expert-profile/redacted/to-drive | GM · Brand Manager · PM | writes it into the case's Drive folder; returns the Drive file link; audited |
| GET | /api/cases/{id}/expert-profile/full | GM · Brand Manager · PM · CM | **409 unless `case.paid`**; never includes `payment_detail` |

All three read the case through `CaseLifecycleService.read`, so another brand's
case — and another CM's — is simply absent, and the expert is read with
`ExpertRepository.findScoped`. No new scoping path.

The Case Manager is included on the two reads because they draft the letter and
need to know who is signing it. They are **not** on the Drive write: publishing an
artefact toward the client is the PM's call.

**The write is idempotent per case, keyed on the file name.** The endpoint is a
button, and a button gets pressed twice — on an impatient click, on a retry after a
timeout that actually succeeded, or on a regenerate after the expert changed. Each
press otherwise adds another `redacted-profile-<case_code>.pdf` to the folder, because
Drive is happy to hold many files with one name, and the client is then looking at a
folder with three profiles and no way to tell which is current. Before uploading, list
the folder for that exact name and **update the existing file's content** if it is
there, create it only if it is not. That also makes the returned Drive link stable, so
`case.draft_link` and anything else holding it stays valid across regenerations.

The Drive call is an outbound HTTP request in a controller-triggered path, so it
runs with a **timeout** and its failure is a 502 that changes nothing in EvalOS —
the profile is regenerable, so there is nothing to roll back and no retry queue is
warranted. Failures are logged with the case and folder id. This is the one place
in the unit where invariant 6's "controllers never run long-lived work" is close
to the line: the upload is a single bounded request, not a job, and if it turns out
slow in practice it moves to `job` (Unit 19), which is where that rule points.

## Frontend deliverables

1. **Redacted-profile panel** on the case detail page, beside the existing
   `ExpertCard` (`features/case/ExpertCard.tsx`): preview the redacted profile,
   and a **Save to the case's Drive folder** action that shows the resulting link.
2. **Full profile behind the paid state**: shown when the case is paid; when it is
   not, the control says the case is unpaid rather than being hidden — the same
   reasoning as the checklist's unpaid chip.
3. The preview renders the generated HTML in an **iframe with `sandbox`**, not
   `dangerouslySetInnerHTML`. It is our own template, but a template that
   interpolates roster fields is a template that interpolates whatever the ENM
   typed into them.

## Acceptance criteria

- [ ] The redacted profile contains **none** of: the expert's `full_name`,
      `institution`, `email`, `phone`, `notes` or `recruitment_source`. Asserted by
      seeding an expert whose every free-text field contains a distinctive token
      and grepping the rendered output for each one.
- [ ] `payment_detail` appears in neither the redacted nor the full profile.
- [ ] The reference label is stable across two generations for the same case, and
      differs between two cases holding the same expert.
- [ ] The full profile is 409 on an unpaid case and 200 on a paid one.
- [ ] A case whose `drive_link` has no parseable folder id gets a 409 naming the
      link — and **nothing is written to Drive**. Likewise a case with no
      `drive_link`.
- [ ] The Drive write lands in the case's own folder and the returned link opens
      it; the write is audited with the actor, the case and the Drive file id.
- [ ] A CM on another CM's case, and any role on another brand's case, gets a 404
      from all three routes.
- [ ] The app **fails to start** outside `local` with no Drive credentials
      configured, rather than starting and failing at the first upload.
- [ ] `npm run build` green; `./mvnw verify` green. The Drive integration is
      covered by a test double; **one manual live upload against a real folder is
      required to close the unit** and is recorded in the tracker — a mocked Drive
      client proves the mapping, not the credentials.

## Invariants honored

Brand isolation via the scoped case and expert reads (1); role + ownership before
the Drive write (3); **`payment_detail` in neither profile** (4); paid-and-delivered
revenue logic untouched, and the paid flag read but never written here (5); the
Drive upload is one bounded request and moves to `job` if it stops being one (6);
new audit entry on the Drive write (13); **no file hosted by EvalOS** — the
document is generated in memory, streamed to the caller, and handed to Drive; it
is never written to Postgres or to disk (14).

## Files touched

**Created.** Backend: `service/RedactedProfileService.java`,
`integration/GoogleDriveClient.java`, `config/GoogleDriveConfig.java`,
`web/ExpertProfileController.java` (+ DTOs),
`src/main/resources/templates/redacted-profile.html`. Frontend:
`frontend/src/features/case/RedactedProfilePanel.tsx` + a redaction rules test.

**Modified.** `pom.xml` (the two Google dependencies). `application.yml` + the
`local`/`prod` profiles (the credentials binding, no non-local default).
`frontend/src/features/case/CaseDetail.tsx` (the new panel).
**`context/architecture.md`** — the stack table's Drive row and the
`integration` package description, because Drive stops being a link-only external
and becomes an outbound client. `context/progress-tracker.md`.

**Not touched.** `domain/Expert.java`, `common/PaymentDetailConverter.java`
(protected), any migration — **this unit adds no migration**, since nothing is
persisted.
