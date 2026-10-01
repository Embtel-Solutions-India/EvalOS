# Unit 68 — GM admin: staff, pipelines, sync health, brands

**Specced and built 2026-10-02** (branch `feature/unit-70-live-screens`). The third UI unit cut from
Unit 66's gap audit. Inventory from the code on 2026-10-02.

## 0. Today, before this unit

- **Staff exist only in Flyway seeds.** `GET /api/team-members` (GM, BM) lists id, name, email, role,
  brand, team — not `active`, `segment`, `ghl_user_id` — and no route creates, edits or deactivates
  anybody. Staff have no password reset.
- **"Tag it `EXPERT_HIRING` on the pipelines screen" (D61) names a screen that does not exist.**
  `GET /api/ghl/pipelines` and `PUT /api/ghl/pipelines/{mirrorId}/purpose` (GM) are built and called
  by nothing; nor are the three `/api/team-members/{id}/pipelines` grant routes.
- **Sync health is API-only.** `GET /api/sync/drift` (GM) returns drift rows and the outbox's pending
  / dead counts; no screen reads it.
- **`/brands` is a placeholder** (`PlaceholderPage`); `GET /api/brands` returns id, name, slug.
- A staff JWT lives 8 h and is never re-checked, so a deactivated member would keep working until it
  expires.

## 1. Decisions

| # | Question | Answer |
|---|---|---|
| 1 | Who administers? | **The GM, alone.** Every write below is `hasRole('GM')` and audited (`audit_event`, object `TEAM_MEMBER`). The BM keeps the read it has. |
| 2 | Staff directory | `/admin/staff`: every member (active and not) with role, brand, segment, GHL user, active. **Create** (name, email, role, brand — none for a GM — segment for SALES / MARKETING, GHL user optional, initial password), **edit** the same fields, **deactivate / reactivate** (never delete: assignments and audit point at the row), **set password**. Pipeline grants for SALES / MARKETING on the same sheet, over the existing routes. |
| 3 | Staff passwords | **The GM sets one** (≥ 12 characters, BCrypt like every other) at create and on *Set password*, and hands it over out of band. **No staff mail**: invariant 14 names the messages EvalOS sends and a staff credential mail is not one of them. Open as `open-decisions.md` Q18 (recommended: keep it so). |
| 4 | Guard rails | The GM cannot deactivate themselves or change their own role (a locked-out business). Email unique (case-insensitive, existing constraint → 400). A role change off SALES / MARKETING revokes their pipeline grants and clears the segment, in the same transaction. |
| 5 | Deactivation takes effect when? | **On the next request.** `JwtFilter` re-reads the member by id after verifying the token and refuses an inactive or vanished one (401). One primary-key read per staff request; a role or brand change still waits for the next sign-in (the token carries them). |
| 6 | The vestigial `team_member.ghl_pipeline_id` | V39's CHECK still *requires* it for SALES / MARKETING, though V54 replaced it with `team_member_pipeline`. **`V84` drops that half of the CHECK** (it stays forbidden for other roles), so a desk can be created without inventing a value. |
| 7 | Pipelines | `/admin/pipelines`: the mirrored pipelines with stage count, last sync, *missing since*, and a purpose select (`UNASSIGNED`, `SALES`, `MARKETING`, `DELIVERY`, `EXPERT_HIRING`) over the existing PUT. This is D61's "pipelines screen". |
| 8 | Sync health | `/admin/sync`: read-only — open drift (owner, resolution, *needs a human* first), the outbox's pending / dead counts and recent dead pushes. Nothing here resolves a drift (D43). |
| 9 | Brands | `/brands` becomes a **read-only** table: name, slug, active, currency, payout term. Creating or editing a brand stays a migration: a brand carries the webhook endpoint token and signing secret, which no screen shows (D19, invariant 10). |
| 10 | GHL user picker | `GET /api/sales/users` (the mirrored location users) admits the GM, for the staff sheet's *GHL user* field. GM-only is invariant 1's own rule for location reads. |

## 2. Routes (new)

| Method | Path | Body |
|---|---|---|
| GET | `/api/team-members` | gains `active`, `segment`, `ghlUserId` |
| POST | `/api/team-members` | `{displayName, email, role, brandId?, segment?, ghlUserId?, password}` |
| PUT | `/api/team-members/{id}` | `{displayName, email, role, brandId?, segment?, ghlUserId?}` |
| PUT | `/api/team-members/{id}/active` | `{active}` |
| PUT | `/api/team-members/{id}/password` | `{password}` |

## 3. Tests

`TeamMemberAdminServiceTest` (create validates role/brand/segment, self-deactivation refused, role
change revokes grants, every write audited), `TeamMemberAdminRouteTest` (GM-only),
`JwtFilterTest`-style check that an inactive member is refused, `LocalPostgresIntegrationTest` for
`V84` (a SALES row with no `ghl_pipeline_id` inserts; a CM with one does not), `navigation.test.ts`
untouched (the new screens are GM-only).
