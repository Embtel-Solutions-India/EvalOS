# `db/seed-local` — local dev seed, and why it lives here

These scripts seed a laptop: two brands, six staff logins sharing one committed BCrypt
hash (`DevPassw0rd!`), throwaway per-brand webhook secrets, and a handful of experts.
They must never reach a real environment.

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
