# EvalOS — UI Context

## Surfaces

EvalOS has three surfaces:

- **Internal app** (staff): dashboards, Kanban production board, data tables,
  case detail, expert database, payout ledger. Information-dense. Multi-brand:
  the GM gets a brand switcher / all-brands view; the Brand Manager and below are
  locked to a single brand.
- **Client portal** (external): a single case's drafted letter for review —
  approve or request revisions. Passwordless via a GHL-delivered link. Minimal
  chrome, no navigation.
- **Expert portal** (external): one assigned case at a time — draft + evidence +
  goal, with accept / request-evidence / decline actions and a **download-then-upload
  sign step**. Passwordless via a CM-shared link. The sign panel's copy has to say
  plainly that a scanned wet signature is expected, so nobody hunts for an
  e-signature button that does not exist.

EvalOS is an internal operations tool: data-dense, fast, and legible over long
shifts. Light workspace — a calm neutral base with layered surfaces and a single
strong accent for interactive elements.

**RAG is load-bearing, not decorative.** Everything runs off red / amber / green
status (deadlines, SLA, capacity, overdue). The three status colors below are
reserved for status only and must never be used as brand or decorative color.

## Colors

Define these as CSS custom properties / Tailwind tokens. No hardcoded hex in
components.

| Role                | CSS Variable          | Value     |
| ------------------- | --------------------- | --------- |
| Page background     | `--bg-base`           | `#F7F8FA` |
| Surface / card      | `--bg-surface`        | `#FFFFFF` |
| Raised surface      | `--bg-raised`         | `#F0F2F5` |
| Primary text        | `--text-primary`      | `#1A1D23` |
| Muted text          | `--text-muted`        | `#6B7280` |
| Primary accent      | `--accent-primary`    | `#3552E0` |
| Accent hover        | `--accent-hover`      | `#2A41B8` |
| Border              | `--border-default`    | `#E3E6EB` |
| **Status — red**    | `--status-red`        | `#DC2626` |
| **Status — amber**  | `--status-amber`      | `#D97706` |
| **Status — green**  | `--status-green`      | `#16A34A` |
| Status red (bg)     | `--status-red-bg`     | `#FEECEC` |
| Status amber (bg)   | `--status-amber-bg`   | `#FEF3E2` |
| Status green (bg)   | `--status-green-bg`   | `#E8F6EC` |

### RAG semantics (single source of truth)

| Meaning                                                          | Token           |
| --------------------------------------------------------------- | --------------- |
| Overdue / breached SLA / capacity >90% / at-risk deadline <24h  | `--status-red`   |
| At risk / deadline <48h / capacity 70–90% / aging past threshold| `--status-amber` |
| On track / delivered on time / capacity <70%                    | `--status-green` |

## Typography

| Role                       | Font                    | Variable      |
| -------------------------- | ----------------------- | ------------- |
| UI text                    | Inter                   | `--font-sans` |
| Numbers / IDs / times      | Inter (tabular figures) | `--font-num`  |
| Monospace (case IDs, refs) | IBM Plex Mono           | `--font-mono` |

Use tabular figures for every column of currency, dates, deadlines, counts, and
case IDs so numbers align.

## Border Radius

| Context                     | Class          |
| --------------------------- | -------------- |
| Inline / small UI (badges)  | `rounded-md`   |
| Cards / panels / Kanban     | `rounded-lg`   |
| Modals / drawers / overlays | `rounded-xl`   |

## Component Library

Tailwind CSS, with components written in the feature folder that owns them.

**There is no `frontend/src/components/ui/` set, and that is current rather than
pending.** Through Unit 10 no screen has needed a table, dialog, drawer, or tabs,
so installing shadcn/ui and Radix to generate primitives nothing renders would be
scaffolding for later. `components/` holds only what more than one feature shares
(today `Forbidden.tsx`).

The rule that mattered still holds: **do not hand-roll a second version of
something that already exists.** Radix remains the intended source for anything
with focus-trapping or ARIA behaviour worth not writing twice — a real modal,
combobox, or tab set. The first screen that needs one adds the vetted primitive
then, and that is the point at which `ui/` is created and becomes protected (see
`ai-workflow-rules.md`).

## Layout Patterns

- **App shell**: fixed left nav (role + brand-scoped items), top bar with a
  **brand switcher** (all-brands/filter for GM, single locked brand otherwise),
  a global date filter (today / week / month / year), search, and a
  **notification bell** (in-app notification center).
- **Dashboard**: RAG tile grid at top (KPIs from the spec), tables and charts
  below. The largest tile is always the role's PRIMARY KPI. The GM's dashboard
  aggregates across brands with a brand filter.

  The **capacity thresholds below are the contract for any workload indicator** —
  the Case Manager workload widget the business asked for uses them as-is rather
  than inventing its own bands.

  **Charts are an unresolved dependency.** Unit 17 wants a cycle-time chart with a
  p90 band and **no charting library is installed**. Decide before that unit starts:
  a small library, or hand-rolled SVG for the two or three shapes actually needed.
  The component-library rule below applies — do not install one to render nothing.
- **Production board (Kanban)**: horizontal columns for the EvalOS-owned stages —
  Doc Collection · Expert Assignment · Draft / Report · Expert Signing · Final
  Delivery — with exception lanes (On Hold · Rematching · Refund Requested). The
  Draft / Report column shows draft sub-status chips (Draft in progress · PM
  review · Client review). Cards show client, service type, deadline (RAG), owner.

  The business's eight-column reading (splitting Draft into three and Signing into
  Signing + QC) is a **derived grouping of these five plus the chips**, not new
  columns to add to the enum — see `context/specs/08-production-board.md`.
- **Delivery queue** (`/delivery`): dense rows, cases in Final Delivery, oldest
  first, one-click **Deliver** per row. This is the data-table pattern's other
  landing place besides the dashboard — a Coordinator working a batch rather than
  hunting cards. The entry was previously deleted as an empty nav item; it is back
  with a screen behind it.
- **Client document upload** (portal): the client's own checklist as a list of rows,
  one upload control per required item, showing required / uploaded / flagged with
  the Coordinator's reason. Portal chrome rules apply — minimal, no nav. Accepted
  types and the size cap are stated on the control, not discovered by a rejection,
  and a rejection says which rule it broke.
- **Data tables**: dense rows, sortable, a dedicated RAG status column, row click
  opens the case. Overdue rows tinted with the `*-bg` status token. No screen uses
  this pattern yet — Unit 10 deleted the `/cases` list as a duplicate of the
  production board, and the dashboards below the RAG tiles are where it lands
  first. The checklist board is deliberately a list of rows rather than a table:
  one stage, so there is nothing to sort by that the server's longest-wait-first
  order does not already answer.
- **Case detail**: two-column — left is documents (Drive link) + draft + expert,
  right is the timeline/audit trail. Stage actions sit in a sticky header.
- **Client portal** (built in Unit 14): single centered column, one case, the
  drafted letter with big Approve / Request revisions actions and a visible
  "changes requested" note field.
  - **No shell, no nav, no brand switcher, and no auth provider** — `App` answers
    `/portal/*` before any staff-session code runs, and mounts `AuthProvider`
    around the staff surface only. A client is not a staff user with fewer links.
    Same tokens, same typography; the difference is what is absent.
  - **Both actions confirm inline before firing**, with wording that says what
    happens next — approving commits the letter to an expert's signature, and a
    revision request puts a person to work. After either, the actions are gone and
    the page states the outcome and what follows.
  - **Failure states are written for the reader, and this is product copy, not
    plumbing.** Expired, revoked and unknown links all say the same thing and point
    at whoever sent the link — never a stack trace, and **never a login form**, for
    somebody who has no account.
- **Expert portal**: single centered column, one case at a time, big Accept /
  Request-evidence / Decline / Sign actions.

## Icons

Stroke-based only, `h-4 w-4` inline and `h-5 w-5` in buttons and nav.

Drawn as inline SVG paths in the component that uses them (see `LeftNav.tsx`), not
imported from Lucide React — which is **not** a dependency. The shell needs about
eight glyphs, and a package for that is heavier than the paths. The sizes above
are Lucide's own convention, so adopting the library later is an import and a
find-and-replace rather than a re-layout; do that once the glyph count makes the
inline paths the bigger cost.
