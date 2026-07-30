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
  goal, with accept / request-evidence / decline / sign actions. Passwordless via
  the Dropbox Sign / CM-shared link.

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
- **Production board (Kanban)**: horizontal columns for the EvalOS-owned stages —
  Doc Collection · Expert Assignment · Draft / Report · Expert Signing · Final
  Delivery — with exception lanes (On Hold · Rematching · Refund Requested). The
  Draft / Report column shows draft sub-status chips (Draft in progress · PM
  review · Client review). Cards show client, service type, deadline (RAG), owner.
- **Data tables**: dense rows, sortable, a dedicated RAG status column, row click
  opens the case. Overdue rows tinted with the `*-bg` status token. No screen uses
  this pattern yet — Unit 10 deleted the `/cases` list as a duplicate of the
  production board, and the dashboards below the RAG tiles are where it lands
  first. The checklist board is deliberately a list of rows rather than a table:
  one stage, so there is nothing to sort by that the server's longest-wait-first
  order does not already answer.
- **Case detail**: two-column — left is documents (Drive link) + draft + expert,
  right is the timeline/audit trail. Stage actions sit in a sticky header.
- **Client portal**: single centered column, one case, the drafted letter with
  big Approve / Request revisions actions and a visible "changes requested" note
  field.
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
