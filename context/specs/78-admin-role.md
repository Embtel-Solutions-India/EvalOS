# Spec 78 — The Admin account (D78)

**Status:** decided 2026-10-08, built the same day. Decisions: `.claude/current-decisions.md` D78; D19e is
untouched.

## Why

Staff, pipeline assignment, pipeline purpose, background jobs, sync health and brand settings were all
`hasRole('GM')`. The GM is a business role (targets, refunds, the whole-business dashboards); handing the
same account the keys to staff and integrations meant one login could add a user, grant a pipeline and
read every client. The business asked for a **separate admin account** for those functions.

## Decisions (the user's, 2026-10-08)

1. **The admin functions move from the GM to the Admin** (separation of duties). The GM keeps business
   views, targets, refunds and read access to brands, and loses the *Admin* nav group.
2. **The Admin also has read-only business views** — the dashboards and the two boards — not just the
   admin screens.

Recommended by me and taken: the Admin is **cross-brand** (`Tier.ALL`, `brand_id` NULL, like the GM) and
**cannot create itself or sign in with a published password** (prod seeds it from a placeholder).

## The model

`Role.ADMIN(Tier.ALL)`. `Role.hasGmView()` is true for `GM` and `ADMIN` — the two roles that read the
whole business (Sales/Marketing journey drill-down, the sales-only pipeline board of D19e).
`Role.seesCaseContent()` is **false** for the Admin: client names are withheld on the boards it can see.

### The boundary is default-deny, in one place

Tier `ALL` alone would be unsafe: many staff endpoints (case list/read/documents/notes, timeline,
checklists, chat) have no `@PreAuthorize` and rely on tier scoping, so an all-brand Admin would read
every client. So `AdminAllowlistFilter` runs after the JWT filter and **refuses any request from an
ADMIN that is not on this list** (403 `FORBIDDEN`, the same body the access-denied handler writes):

| Allowed for ADMIN | Methods |
|---|---|
| `/api/me` | GET |
| `/api/notifications/**` (their own) | GET, PUT, POST |
| `/api/team-members/**`, `/api/ghl/**`, `/api/jobs/**`, `/api/sync/**` | all (the admin functions) |
| `/api/brands/**`, `/api/sales/users` (the staff sheet's GHL user picker) | GET |
| `/api/metrics/gm`, `/api/metrics/journey` | GET |
| `/api/cases/board` | GET |
| `/api/opportunities/board` | GET (not `/refresh`) |

Everything else — cases, documents, contacts, payouts, experts, chat, refunds, targets, writes to the
boards — is 403, **whatever the controller's own annotation says**. A new endpoint is therefore closed to
the Admin until someone adds it here, which is the safe direction to be wrong in. The controllers' own
`@PreAuthorize` is the second layer: `ADMIN` is added only to the admin endpoints and to the four reads
above.

### What moves

| Endpoint | Before | After |
|---|---|---|
| `POST/PUT /api/team-members`, `/active`, `/password`, `/{id}/pipelines` (grant, revoke, read) | GM | **ADMIN** |
| `GET /api/team-members` (roster) | GM, BM | **ADMIN**, BM |
| `/api/ghl/**` (pipelines, purpose) | GM | **ADMIN** |
| `/api/jobs/**`, `/api/sync/**` | GM | **ADMIN** |
| `GET /api/brands` | GM | GM, **ADMIN** |
| `GET /api/metrics/gm`, `/journey` | GM (+SALES, MARKETING) | + **ADMIN** |
| `GET /api/opportunities/board` | GM, SALES, MARKETING, ENM | + **ADMIN** |
| monthly targets, refunds, `PUT /api/metrics/gm/goal` | GM | GM (unchanged) |

## Data

`V88__admin_role.sql` widens `team_member_role_valid` with `'ADMIN'` and `team_member_brand_required` to
`role IN ('GM','ADMIN') OR brand_id IS NOT NULL`. No other table lists roles.

Accounts: `seed-local` gets `admin@evalos.local` (`DevPassw0rd!`, like the others). **`seed-prod` gets
`V961__seed_admin.sql` with the `${admin-password-hash}` placeholder** (env `ADMIN_PASSWORD_HASH`, no
default — an environment that forgets it fails to migrate rather than seeding a known hash). Because the
GM no longer administers staff, **prod must have an Admin before this ships**, or nobody can add staff.

## Frontend

`Role` gains `'ADMIN'`; label *Administrator*. Navigation: the **Admin** group (Staff, Pipelines, Sync
health, Background jobs) is `ADMIN`-only; Brands is `GM` and `ADMIN`. The Admin's other entries are the
three read-only views (Sales performance, Marketing performance, Production board) plus My pipeline,
and every write control on them is hidden. **The Admin's `/dashboard` is its own overview** — active
staff, pipelines with no purpose, sync drift and dead outbox items, each linking to the screen that
fixes it — not the GM's. The GM overview calls the revenue, PM and expert-network metrics too, and
revenue is explicitly gated to whoever may see a deal value (`MetricsController.revenue`); the Admin may
not, so giving it that screen would mean either error cards or widening that gate. The Admin has no
chat and no bell actions beyond reading.

## Not done / decided against

- **Creating an Admin through the API** is allowed only for an Admin, and the form does not offer GM.
  The first Admin comes from the seed.
- **Case detail, contacts, payouts for the Admin:** not read-only views, so 403. A board card does not
  open for the Admin.
- **Admin-specific audit view:** actions already land in the append-only audit log under the Admin's id.
