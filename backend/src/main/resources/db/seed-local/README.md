# `db/seed-local` — local dev seed, and why it lives here

These scripts seed a laptop: two brands, **thirteen** staff logins sharing one committed
BCrypt hash (`DevPassw0rd!`), throwaway per-brand webhook secrets, and a handful of
experts. They must never reach a real environment.

## The logins

All thirteen share the password **`DevPassw0rd!`**.

| Email | Role | Brand | Notes |
|---|---|---|---|
| `gm@evalos.local` | GM | *none — every brand* | The only cross-brand reader |
| `bm.ie@evalos.local` | Brand Manager | International Evaluations | |
| `bm.xp@evalos.local` | Brand Manager | XpertsPortal | |
| `pm.ie@evalos.local` | Project Manager | IE | |
| `cm.ie@evalos.local` | Case Manager | IE | |
| `pc.ie@evalos.local` | Project Coordinator | IE | |
| `enm.ie@evalos.local` | Expert Network Manager | IE | |
| `sales.attorney.ie@evalos.local` | Sales · `ATTORNEY` | IE | Evaluation & Translational (`Eu5LMyN0ioAVXJgW1JGT`) |
| `sales.employer.ie@evalos.local` | Sales · `EMPLOYER_FIRM` | IE | PERM + IBP + Prefiling (`0kT0EC0zVKdP1fklmrFC`) |
| `sales.individual.ie@evalos.local` | Sales · `INDIVIDUAL` | IE | Expert Opinion Letter + RFE (`EoVInfnvXguEkg7wWktg`) |
| `marketing.individual.ie@evalos.local` | Marketing · `INDIVIDUAL` | IE | BDE-1 (`CYcHdfPovQpxxNlIIto8`) |
| `marketing.attorney.ie@evalos.local` | Marketing · `ATTORNEY` | IE | BDE-2 (`q9z45efXKXM3VjhtBnwZ`) |
| `marketing.bde3.ie@evalos.local` | Marketing · `EMPLOYER_FIRM` | IE | BDE-3 (`ih8dMuHEiFfzSi2rVBrN`) — added by `V909` |

**Repointed on 2026-09-14 by `V909`.** The ids above were read from the sub-account IE abandoned
on 2026-09-11, so every one of them named a pipeline that no longer exists — and because
`ghl_pipeline_id` is an *access key*, the symptom was a desk drawing zero columns with no error
at all. The current ids are from the live location `WY6bW2xUCI8Tz8gw7aLJ`.

**The sales split changed shape, not just ids.** The live account splits sales by **service line**,
not by client segment, so the three sales desks now point at Evaluation & Translation, PERM + IBP
and Expert Opinion Letter + RFE. The `segment` column is left as it was: `00b` §1.1 makes it
display-and-reporting only with no access meaning, so it is free to disagree with the pipeline name.

**The six pipeline-scoped logins are IE-only and cannot be given to XpertsPortal.**
`evalos.ghl.sales-brand` names International Evaluations, and `PipelineAssignmentService`
answers 400 for a pipeline-scoped role on any other brand — Unit 36's single-brand ceiling,
which lifts when XpertsPortal gets its own GHL location (Unit 25). The pipeline ids are real,
read from the live location on 2026-09-11; ids rather than names, because the id is the access
key and a rename must not lock a salesperson out of their own desk.

**There is now an `EMPLOYER_FIRM` marketing login**, which reverses what this section used to say.
It read: "there is no `EMPLOYER_FIRM` marketing login, because the live location has two marketing
pipelines and not three… seed the third the day the pipeline exists." That day arrived — the new
location has BDE-1, BDE-2 and BDE-3 — so `V909` seeded it.

**The GM's NULL brand means *every* brand** (`Tier.ALL` skips the predicate). It is the
only brand-less row the database allows — `team_member_brand_required` says so, restored
to that form by V30. Unit 29's sales executive briefly held a NULL meaning the *opposite*
("no brand": `Tier.SELF` with no brand is `disjunction()`, matching nothing); that role,
its seed (`V906`) and its widened constraint are all gone.

**This directory is a sibling of `db/migration`, not a child, and moving it back under
`db/migration` would be a security regression.** Flyway scans a location *and every
sub-directory below it*. Production lists plain `classpath:db/migration`
(`application.yml`), so while these files sat at `db/migration/local` they were found
and applied by any boot of any profile — the `local` profile's separate listing was
never what selected them. Two code comments asserted otherwise and were simply wrong;
the `evalos_test` schema, which never listed the seed location either, had
`local/V903__seed_local_experts.sql` in its Flyway history.

Flyway has no exclude filter. Directory separation is the entire mechanism, so
`config/MigrationTreeTest` fails the build if anything reappears below `db/migration`.

Anything that wants the seed must list it explicitly:

```yaml
spring.flyway.locations: classpath:db/migration,classpath:db/seed-local
```

Today that is `application-local.yml` and `LocalPostgresIntegrationTest` — the latter
because its brand and staff constants *are* these rows, a dependency that used to be
satisfied by the same accident.

## `V905` — the demo dataset, and why it deletes

`V900`–`V904` seed the minimum a developer needs to log in. `V905` seeds what a
*client demo* needs: 13 experts across all four availability states, 29 cases with
every stage occupied and a deliberate mix of SLA colours, and nine months of closed
work behind them so the dashboard's figures have a past to be measured against. Dates
are relative to `now()`, so it does not age.

**It deletes every transactional row before it inserts**, keeping only `brand` and
`team_member`. That is not tidiness. Integration tests wrote into `public` until they
were moved to `evalos_test`, and the residue — 69 experts, 165 cases, 33 contacts —
was still on screen months later, alongside hand-made probe rows. A seed that only
inserted would have left the demo showing both. Because it clears first, re-running it
is idempotent: it is safe for Flyway to apply after the rows are already there.

It also disables `audit_event`'s append-only trigger for exactly one `DELETE` and
turns it straight back on. That is the only place in the codebase that touches that
trigger and it is **not** a precedent — application code must never delete an audit
row. It is here because the audit rows being cleared describe cases that no longer
exist.

## Existing databases

A database that applied these under the old layout recorded them as
`local/V9xx__…sql`, which no longer resolves, so Flyway now fails validation on boot.
The files are byte-identical to what was applied, so realigning the recorded path is
enough — no repair, no checksum change:

```sql
UPDATE flyway_schema_history
   SET script = replace(script, 'local/', '')
 WHERE script LIKE 'local/V9%';
```

Run it against every schema that has one (on a stock dev box: `public` in `evalos`,
and `evalos_test`). A database that should never have had the seed at all needs more
than this: delete the seeded rows and **rotate the webhook secrets**, because
`V901`'s values are in this repository.

## A deleted seed: `V906`

`V906__seed_local_sales_executive.sql` was removed with the sales desk. A database that
already applied it still carries version 906 in `flyway_schema_history` with no file
behind it, and Flyway's default validation refuses to migrate in that state — so
`application-local.yml` and `LocalPostgresIntegrationTest` both set
`ignore-migration-patterns: "*:missing"`, and **only** those two. A checksum mismatch (an
*edited* applied migration) still fails the boot, which is the check that protects the
schema. **Never carry that setting into `application.yml` or a deployed profile**: nothing
under `db/migration` is ever deleted, so a missing one there is a real defect.

Deleting a file from *this* tree is allowed at all only because it is a disposable local
seed. `V30__drop_sales_executive.sql` removes the row it inserted, so a database that ran
it ends up in the same state as one that never did.
