> ## ⚠️ SUPERSEDED (2026-08-25) — read this box before anything below it
>
> The Protend language this guide describes **has been replaced**. A second visual pass
> adopted a different reference, and it reverses this document's central claim.
>
> | | Protend (this guide) | Current |
> |---|---|---|
> | Nav rail | white **floating rounded card**, inset 20px, radius 30px | **dark navy, flush to the edge, full height** |
> | `--radius-xl` | 30px, used as a pill on controls | **12px, overlays only**; controls take `md` (6px) |
> | Accent | `#3c21f7` violet | `#2563eb` blue |
> | Canvas | `#f9fafe` | `#f4f5f7` |
> | Elevation | 50px ambient bloom | hairline border + short shadow |
> | KPI figures | monochrome | **large, semantically coloured, with delta chips** |
>
> **What still holds, and is why this file is kept rather than deleted:**
> - The *method* — extract values into `tokens.css` and express them with Tailwind
>   utilities; do not port a foreign stylesheet or its jQuery.
> - The **scope rule** in the next section, which is still the contract for any visual
>   pass: colour, type, spacing, radius, shadow, iconography and presentational markup
>   are in scope; business logic, APIs, routing, state, workflows, stage vocabulary and
>   role gates are **not**. If a visual change requires a logic change, the visual change
>   is wrong.
> - The three "do not adopt" warnings (no tabular figures, decorative colour, thin
>   accessibility).
>
> **Current values live in `frontend/src/styles/tokens.css`**, and `context/ui-context.md`
> mirrors them. Treat every hex and every geometry number below as history.

# UI Migration Guide — adopting the Protend visual language in EvalOS

Reverse-engineered from **Protend – Project Management Admin Dashboard HTML** (package
dated 2023-11-27). This document is the **visual** contract for EvalOS. It changes how
the app looks and nothing about what it does.

## Scope, stated once so it is not negotiated per screen

**In scope:** colour, type, spacing, radius, shadow, iconography, and the internal
markup of presentational components.

**Out of scope, and this is absolute:**

- Business logic. Every `*Rules.ts` file (`boardRules`, `checklistRules`,
  `redactionRules`, `portalRules`, `expertRules`, `shortlistRules`) is display *logic*
  with tests behind it. A migration does not touch them.
- APIs. No `*Api.ts` file changes. No endpoint, payload or query parameter changes.
- Routing. `App.tsx`'s route table and `features/shell/navigation.ts` stay as they are.
  **`navigation.ts` is deliberately one table serving both the nav and the route
  allow-list** — splitting it to make the sidebar prettier reintroduces the
  deep-linkable-but-unlisted bug it exists to prevent.
- State. `AuthProvider`, `filtersContext`, `session.ts` are untouched.
- Feature names, workflows, stage vocabulary, role gates.

If a visual change requires a logic change, the visual change is wrong. Stop and ask.

---

## The one architectural decision

**Do not port Bootstrap. Port the design language into the tokens EvalOS already has.**

| | Protend | EvalOS |
|---|---|---|
| CSS | Bootstrap 5 + 89 KB `style.css` + `responsive.css` | Tailwind v4 + `styles/tokens.css` |
| JS | jQuery, Bootstrap bundle, simplebar, owl.carousel, peity, moment | none (4 runtime deps total) |
| Icons | Boxicons 2.0.7 via CDN | inline SVG, in 2 files |
| Charts | ApexCharts + Chart.js | none installed |

Protend ships ~700 KB of CSS and eight jQuery plugins to render what EvalOS renders
with Tailwind utilities and 41 lines of custom properties. Adopting its stylesheet
would mean two cascade systems fighting, a jQuery dependency in a React 19 app, and a
CDN request that the artifact CSP-equivalent concerns in this repo already argue
against.

**Therefore: extract the values, write them into `frontend/src/styles/tokens.css`, and
express them with Tailwind utilities.** The migration is mostly a token diff plus
component-by-component class changes. Total new runtime dependencies: **zero**, unless
the icon decision below says otherwise.

---

## Extracted design language

### 1. Layout architecture

```
┌─ 30px gutter ────────────────────────────────────────────────────┐
│  ┌──────────────┐   ┌──────────────────────────────────────────┐ │
│  │              │   │  HEADER  fixed, 136px, bg = body bg      │ │
│  │  SIDEBAR     │   │  (not a white bar — it floats on canvas) │ │
│  │  floating    │   └──────────────────────────────────────────┘ │
│  │  card        │   ┌──────────────────────────────────────────┐ │
│  │  400 × 100vh │   │  MAIN CONTENT  padding 40px              │ │
│  │  radius 30px │   │  margin-top 100px                        │ │
│  │  fixed       │   │  ┌────────────┐ ┌────────────┐           │ │
│  │  top/left 30 │   │  │ .box 25px  │ │ .box 25px  │  radius   │ │
│  │              │   │  │ radius 10  │ │ radius 10  │  10px     │ │
│  └──────────────┘   │  └────────────┘ └────────────┘           │ │
└──────────────────────────────────────────────────────────────────┘
```

The defining move: **the sidebar is a floating rounded card, not a flush panel.**
`position: fixed; top: 30px; left: 30px; height: 100vh; border-radius: 30px` over a
tinted canvas (`#F9FAFE`). The header shares that canvas colour rather than being a
white bar, so the only white surfaces are the sidebar and the content cards. That
single decision is most of the "modern" feel.

Header clears the sidebar with `padding-left: 470px` (400 + 30 offset + 40 gap) — a
hardcoded offset that must become a token.

### 2. Sidebar behaviour

| Property | Value |
|---|---|
| Width | `--sidebar-size: 400px` |
| Position | `fixed`, `top: 30px`, `left: 30px`, `height: 100vh` |
| Radius | `30px` |
| Shadow | `0 0 50px 0 rgba(42, 89, 152, 0.10)` |
| Logo block | `padding: 74px 0`, image height `57px`, centred |
| Menu padding | `43px 43px 63px` |
| Item | `height: 45px`, `padding: 34px 15px`, `radius: 10px`, `font-size: 20px`, `weight: 500`, `text-transform: capitalize` |
| Item colour | `--text-second-color` (#878787), hover → accent |
| **Active item** | `background: rgba(60,33,247,0.15)`, colour → accent, **no left border** |
| Item icon | `1.5rem`, `margin-right: 12px` |
| Submenu | `height: 0 → 100%`, `transition: height .25s ease-in-out`, child `padding-left: 50px`, font drops to `16px/400`, height `40px` |
| Chevron | absolute `right: 15px`, `rotate(180deg)` when open, `.3s` |
| Collapse | `body.sidebar-expand.active .sidebar { transform: translateX(-120%) }`, header padding drops to `40px`, both `.3s ease-in-out` |
| Scroll | custom 5px thumb tinted `--menu-item-active-bg` |

Collapse is a **translate off-canvas**, not a width shrink to an icon rail. There is no
mini/icon-only state.

### 3. Header design

| Property | Value |
|---|---|
| Height | `136px`; `90px` below 1200px |
| Background | `--body-bg` — matches canvas, no shadow, no border |
| Layout | `flex`, `justify-content: space-between`, `padding: 20px`, `padding-left: 470px` |
| Page title | `36px`, `weight: 600`, `capitalize` |
| Search | pill: `height: 60px`, `radius: 30px`, `border: 1px solid #BCBEC6`, `padding-left: 63px`, icon absolute at `left: 29px`, `22px` |
| Chips | language/profile pills: `background: #E5E2FE`, `radius: 30px`, `padding: 12px 27px`, `font-size: 20px` |
| Avatar | `--profile-image-size: 45px` |
| Notification badge | absolute `top: 12px; right: 4px` on the icon |
| Mobile | search collapses to an icon + dropdown; hamburger `#mobile-toggle` appears |

### 4. Navigation patterns

Single-level accordion in the sidebar; **no breadcrumbs anywhere**; page identity is
carried entirely by the 36px header title. Active state is a tinted pill, and the
parent of an open group also carries `.current-menu-item` with the same tint. Tabs
(`.nav.panel-tabs`) are used for within-page sections on detail screens.

### 5. Card styles

```css
.box {
  background: #fff;
  padding: 25px;
  border-radius: 10px;
  box-shadow: 0 0 50px 0 rgba(42, 89, 152, 0.05);
}
```

Card header: `h3` at `24px/600` capitalize, usually with a right-aligned dropdown
("Monthly / Yearly / Weekly"). A `.divider` rule separates header from body.

**Stat tiles** are the template's signature component — a pastel panel containing a
gradient icon chip:

```
.icon-box.bg-color-N   → pastel tint background
  .icon.bg-icon-N      → vertical gradient chip, holds a Boxicon
  .content
    h5.title-box       → label
    p.color-N          → value/caption in the saturated hue
```

Thirteen numbered pairs exist. The four worth taking:

| Pair | Tint | Gradient chip | Text |
|---|---|---|---|
| 1 amber | `rgba(255,188,3,.1)` | `#FFBB00 → #FFF574` | `#FFBB01` |
| 2 green | `#E5F8EB` | `#00843E → #59F187` | `#00BC39` |
| 3 indigo | `rgba(60,33,247,.1)` | `#3C21F7 → #9B8DFF` | `#9687FE` |
| 9 red | `#FEE9ED` | `#F7284A → #F9BAC6` | `#F7284A` |

In dark mode every tint collapses to `#1E1D2B` — the tint is a light-mode device only.

### 6. Tables

`<table class="table table-vcenter text-nowrap table-bordered">`

- Header cells `14px`, `weight: 500`, `border-bottom: 0`
- Cells `padding: 20px 0`, `border-bottom: 1px solid var(--border-color)`
- Wrapped in `.table-responsive` with a 5px accent-coloured scrollbar
- `text-nowrap` throughout — the table scrolls rather than wraps
- Row entity pattern: 45px round avatar + name, `weight: 500`, capitalize
- Status cell is a pill (`.order-status`: `padding: 5px 15px`, `radius: 5px`,
  `weight: 500`, tinted background)
- Trailing "Action" column of icon buttons

### 7. Forms

Bootstrap `.form-control` / `.form-select` / `.form-label`, with the template's
contribution being **large, round, low-contrast fields**: pill radius on search
(`30px`), `10px` on block inputs, generous height (`60px` on the header search), border
`#BCBEC6`, no inner shadow. Labels sit above. Dark mode makes fields transparent with
`--text-second-color` text.

### 8. Buttons

Protend leans on Bootstrap's `.btn` plus a few local patterns:

- Primary: `background: var(--main-color)` (#3C21F7), white text
- On-accent light button `.btn-now a`: white bg, accent text, `radius: 7px`,
  `padding: 13px 29px 12px`, hover `opacity: .8`, `transition: all .5s`
- Icon-only circular action: `30px` circle, `background: #F6F8FD`, flex-centred
- Add-card button: full width, `padding: 20px`, translucent `rgba(255,255,255,.7)`,
  heavier shadow `0 0 50px rgba(42,89,152,.15)`

Radius vocabulary is inconsistent across the template (`5px` status, `7px`
badge/button, `10px` card, `30px` sidebar/pill). Normalise on migration — see the token
table.

### 9. Typography

**The template's own font declaration is dead code.** `style.css` sets
`font-family: "Poppins", sans-serif` but the HTML loads only **Roboto** — Poppins is
never fetched, so every page actually renders in Roboto. This is precisely the bug
EvalOS already fixed in its visual pass (15 files of `tabular-nums` that were no-ops
because no webfont was loaded). Do not copy the mistake; decide the font deliberately.

| Role | Protend |
|---|---|
| Base | 16px, Roboto (declared Poppins) |
| Page title | 36px / 600 / capitalize |
| Card title | 24px / 600 / capitalize |
| Sidebar item | 20px / 500 / capitalize |
| Table header | 14px / 500 |
| Badge | 12px / 500 |
| Headings | `h4` 1.5em, `h5` 1.25em, `h6` 1.125em, all `600`, `line-height: 1.2` |
| Weights available | 300–900 as `.font-w300`…`.font-w900` |
| Scale | `.fs-12` … `.fs-46`, each with a paired line-height; `.fs-16/18/28` step down at ≤575px |

`text-transform: capitalize` on titles, sidebar items and status pills is a consistent
signature.

### 10. Colours

```css
--body-bg:      #F9FAFE;   /* tinted canvas, not white */
--box-bg:       #ffffff;
--main-color:   #3C21F7;   /* indigo-violet accent */
--text-color:   #222943;   /* near-black with a blue cast */
--text-second-color: #878787;
--border-color: #e9e9e9;
--menu-item-active-bg: rgba(60, 33, 247, 0.15);
--bs-yellow:    #FFBF3A;
--bs-blue:      #5F45FF;
--bg-card:      rgba(255, 255, 255, 0.7);
```

Dark mode via a `.dark` class on `<body>`: `--body-bg: #1E1D2B`,
`--box-bg: #252837`, `--border-color: #222028`, text `#fff`, and `--main-color`
flipped to `#fff` (with links/buttons overridden back to the indigo).

Gradients are the template's decorative device: `linear-gradient(to right, #3C21F7,
#9B8DFF)` for banners, `to bottom` for icon chips.

### 11. Spacing

- Page gutter `30px`, content padding `40px`, card padding `25px`
- Card gap driven by Bootstrap's grid
- A full utility ladder `.mg-*` / `.mt-*` / `.pd-*` in 5px steps up to 100
- `.px-3` is **overridden to 30px**, not Bootstrap's 1rem — a trap if you mix the two
- Vertical rhythm inside cards: `15px` list rows, `20px` table cells

### 12. Icons

**Boxicons 2.0.7** (`bx bx-*`, `bx bxs-*` solid), loaded from unpkg. Sidebar icons
`1.5rem`, header `22–30px`, animation utilities used sparingly (`bx-tada` on the
notification bell).

### 13. Charts

**ApexCharts** is the primary library (`libs/apexcharts`), with **Chart.js** also
bundled and **peity** for inline sparklines. `chart-apex.html` is a dedicated showcase
page. Charts sit inside a standard `.box` with the usual 24px header and a period
dropdown.

### 14. Dialogs

Bootstrap modals with a local skin: `.modal.custom-modal.fade`,
`.modal-dialog.modal-dialog-centered` (`.modal-lg` for forms). Structure is
`modal-header` (title + close) → `modal-body` → footer actions. Destructive dialogs use
a `.modal-btn.delete-action` pair.

### 15. Drawers

**None.** The template has no off-canvas drawer. The nearest things are the sidebar's
own translate-off-canvas collapse and dropdown panels (`.notification-list.card`)
anchored under header items. A drawer, if EvalOS needs one, must be invented in this
language: white surface, `radius: 30px` on the outer corners, the `0 0 50px` shadow.

### 16. Tabs

`<ul class="nav panel-tabs w-100 d-flex justify-content-between">` on the client-detail
page — full-width, evenly distributed, Bootstrap tab JS behind it. Used for
within-page sections, not for navigation.

### 17. Timeline

**None.** No timeline component exists anywhere in the template. EvalOS's
`features/case/Timeline.tsx` therefore has no counterpart to copy and must be
restyled by derivation: `.box` container, `15px` row rhythm, the `.product-list-item`
flex pattern, an accent dot or gradient rail in `--main-color`, secondary metadata in
`--text-second-color` at `fs-13`.

### 18. Loading and empty states

**Neither exists.** Every page ships fully populated with static demo data. There is no
skeleton, no spinner, no zero-state illustration, and no error panel.

This is a gap, not a licence: EvalOS already distinguishes zero from no-data
deliberately (`slaMix` keeps `unknown` as a fourth band; the board's read-failure panel
says "Nothing was changed"). **Those behaviours are business-meaningful and must
survive the migration** — restyle them, never delete them. Derive the visuals:
skeletons as `--bg-raised` blocks at card radius; empty state as centred `fs-18`
secondary text inside a `.box`; error state keeping its existing copy.

### 19. Responsive behaviour

Breakpoints: `1820`, `1700`, `1640`, `1200`, `991`, `767`.

| Below | Change |
|---|---|
| 1820 / 1700 / 1640 | sidebar and header offsets tighten progressively |
| **1200** | header `136 → 90px`, `padding-left: 30px`; stat tiles wrap to 2-up (`width: 49%`) |
| **991** | card padding `25px → 25px 15px`; two-column rows collapse to stacked (`width: 100%`) |
| 767 | single column throughout |
| 575 | `.fs-16 → 14`, `.fs-18 → 16`, `.fs-28 → 24` |

The sidebar becomes a translate-in overlay driven by `#mobile-toggle`; there is no
persistent rail on small screens.

---

## Three places the template is worse than EvalOS. Do not adopt these.

1. **No tabular figures.** Protend uses none. EvalOS requires them on every column of
   dates, counts, currency and case IDs (`context/ui-context.md`), fixed with measured
   evidence in the visual pass. **Keep `--font-num` and the `.tabular-nums` rule.** If
   the font changes, re-verify with the same probe: `111111` and `000000` must measure
   identical widths. Roboto and Poppins both support `tabular-nums`; a system fallback
   does not.

2. **Colour is decorative.** Thirteen `bg-color-N`/`color-N` pairs used for visual
   variety. In EvalOS, **red/amber/green are load-bearing status semantics with a
   single source of truth** and fixed thresholds (capacity 70/90, deadline 24h/48h).
   The migration takes Protend's palette for *surfaces and accent only*. `--status-*`
   values are not touched, and no tile picks a hue for decoration.

3. **Accessibility is thin.** No visible focus ring, colour-only status pills,
   `outline` removed in places. EvalOS has one app-wide `:focus-visible` ring and an
   `aria-label` carrying the counts on `SlaRail` precisely because a colour-only
   instrument is not one. **Both stay.**

---

## Token diff — `frontend/src/styles/tokens.css`

Keep the variable *names*. Change the values, add the new ones. Every component
referencing `var(--bg-base)` then moves with no edit.

| Token | Now | Target | Note |
|---|---|---|---|
| `--bg-base` | `#f7f8fa` | `#F9FAFE` | tinted canvas |
| `--bg-surface` | `#ffffff` | `#ffffff` | unchanged |
| `--bg-raised` | `#f0f2f5` | `#F1F1F1` | Protend `--bs-light` |
| `--text-primary` | `#1a1d23` | `#222943` | blue-cast near-black |
| `--text-muted` | `#6b7280` | `#878787` | |
| `--border-default` | `#e3e6eb` | `#e9e9e9` | |
| `--accent-primary` | `#3552e0` | `#3C21F7` | already close; this is the biggest single visual shift |
| `--accent-hover` | `#2a41b8` | `#2E19C4` | derived, Protend has no hover token |
| `--accent-soft` | — | `rgba(60,33,247,0.15)` | **new** — active nav, selected states |
| `--accent-gradient` | — | `linear-gradient(180deg,#3C21F7,#9B8DFF)` | **new** — icon chips only |
| `--shadow-card` | `0 1px 2px rgb(26 29 35/.05)` | `0 0 50px 0 rgba(42,89,152,.05)` | ambient, no offset |
| `--shadow-pop` | (text at low alpha) | `0 0 50px 0 rgba(42,89,152,.15)` | |
| `--status-*` | *(6 values)* | **unchanged** | non-negotiable |
| `--ring-focus` | accent double ring | unchanged, follows new accent | |
| `--radius-md` | `.375rem` | `0.4375rem` (7px) | badges, buttons |
| `--radius-lg` | `.5rem` | `0.625rem` (10px) | cards, panels |
| `--radius-xl` | `.75rem` | `1.875rem` (30px) | sidebar, modals, pills |
| `--sidebar-width` | — | `15rem` (240px) / `13rem` ≤1200px | **new** — see deviation below |
| `--shell-gutter` | — | `1.25rem` (20px) | **new** |
| `--header-height` | — | `4.5rem` (72px) | **new** |
| `--board-column-max` | — | `max(14rem, calc(100svh - 22rem))` | **new** — the board's vertical scroll bound |

**Deviation from the template, stated rather than smuggled:** Protend is built for a
desktop that EvalOS staff do not have. Its sidebar is **400px**, its header **136px**,
and its controls **44–48px** tall. On the 1366×768 laptops this is actually operated
on, that is 28% of the width and 18% of the height spent on chrome before a single case
is drawn.

The adopted scale, and why:

| | Template | EvalOS | Because |
|---|---|---|---|
| Sidebar | 400px | **240px** (13rem ≤1200px) | Eight nav labels fit 240 with room; the widest is "Production board" at ~110px. The board is five 288px columns and every pixel of chrome eats part of one. |
| Header | 136px | **72px** | Holds a 36px control row with breathing room. 136px was empty space above the fold. |
| Controls | 44–48px | **36px** | One height for every pill, select, search field and icon button in the shell and on the board. |
| Column | 320px | **288px** | Three and a half columns visible at 1366 instead of two and a half. |

The radius scale, the tinted canvas, the ambient shadow and the accent are untouched —
those are the template's identity. Its *density* is not. If the roomier scale is wanted
back, sidebar, gutter and header are one token each.

---

## Per-screen migration

Every row: keep logic, keep API, keep routing, keep state. Change classes and internal
markup only.

### Shell — `features/shell/*`

| Component | Change |
|---|---|
| `AppShell` | Wrap in the floating-card layout: canvas background, sidebar `fixed` at the gutter inset with 30px radius, content offset by `sidebar + 2 × gutter`. |
| `LeftNav` | Items → 36px tall pills, `radius: 10px`, 14px/500, icon 20px + 10px gap, active = `--accent-soft` fill with accent text and **no left border**. **Keep the grouped-by-consecutive-run rendering** — Overview / Pipeline / Records / Admin is how three roles stopped having their primary screen listed last. Protend's flat accordion must not replace grouping. |
| `TopBar` | Header at 72px on `--bg-base` with no shadow or border, page title from the nav table (**keep that** — it is what makes `/my-cases` read "My cases"), controls right-aligned as 36px pills. |
| `BrandSwitcher` | Header pill: `--accent-soft` background, `radius: 30px`, 36px tall with 16px side padding. GM-only behaviour unchanged. |
| `DateFilter` | Same pill treatment, sitting beside the brand switcher. |
| `NotificationBell` | Boxicon-style bell in a 36px square, count badge pinned to the outer corner (`-top-1 -right-1`) so it clears the glyph; dropdown becomes `.notification-list` — white card, 30px radius, header + divider + rows. |
| `PlaceholderPage` | Centred inside a `.box`. **Keep `boardPathFor(role)`** — the escape link is gated through `mayReach`, and hardcoding one is a 403 with extra steps. |

### `features/dashboards/RoleDashboard.tsx`

The screen that gains most. Adopt the stat-tile pattern: pastel panel + gradient icon
chip + label + value. Grid 4-up, 2-up ≤1200px, 1-up ≤767px.

**Constraint:** tile *colour* must come from RAG where the tile has a status meaning
(SLA, capacity, liability), and only from Protend's decorative pairs where it does not
(counts, totals). A green "money in" tile that means nothing dilutes the green that
means on-track. Unit 17 is unbuilt, so this is mostly a spec for the tiles when they
land — restyle the placeholder now, build tiles in Unit 17 already in this language.

### `features/board/*` — the highest-risk screen

| Component | Change |
|---|---|
| `BoardView` | Header eyebrow + "N cases in view" in the 36px/secondary hierarchy. **`boardRules.allInsideSla` stays exactly as is** — it is the fix for a header that claimed "all inside SLA" over a board that was 127/150 unknown. |
| `StageColumn` | Column as `.box`: white, 288px wide, `radius: 10px`, 12–16px padding, ambient shadow. **`SlaRail` keeps its four bands including `unknown`, and keeps its `aria-label` with the counts.** Restyle the 3px bar; do not fold `unknown` into `onTrack`. Card list bounded by `--board-column-max` — see the two-axis note below. |
| `CaseCard` | White card, `radius: 10px`, ambient shadow, hover lift. RAG badge → `.order-status` pill geometry with **existing** `--status-*` colours. Keep the "Yours" badge and `isMine`. Keep `tabular-nums` on dates and case codes. |
| `PoolLane` | Same card language, capped at two rows of pills (`max-h-[5.5rem]`) and then scrolling. |

**The board scrolls on both axes, and each axis has an owner.** The column strip owns
horizontal (`overflow-x-auto` on the flex row); each column's card list owns vertical
(`overflow-y-auto` bounded by `--board-column-max`). That split is what keeps the page
heading, the filters, the pool and every column header and SLA rail fixed while cases
move under them — a header that scrolls away takes the instrument with it.

`--board-column-max` subtracts a measured 22rem of chrome from `100svh`. It is a magic
number and it is marked as one in `tokens.css`. The non-magic version is a
viewport-height app frame where the strip is `flex-1 min-h-0`, which means `AppShell`
owning the scroll for every screen in the app — worth doing when a second screen needs
it, not for one board.

"Off the pipeline" is **closed by default**: open, the lane row pushed the stage columns
below the fold, which defeats the bound above.
| `QuickActionDialog` | Bootstrap-modal geometry without Bootstrap: centred, `radius: 30px`, header/body/footer, `--shadow-pop`. `QUICK_ACTIONS` and its field kinds are untouched. |

### `features/case/*`

| Component | Change |
|---|---|
| `CaseDetail` | Two-column layout preserved; each panel becomes a `.box` with a 24px/600 capitalize header. Keep the failure state's `boardPathFor` escape and its "Nothing was changed" copy. |
| `DocumentsPanel` | Card + list rows at 15px rhythm. **Keep the `mayReach` gate on the "Manage the checklist" link** — ungated it 403s for every role but one. |
| `DraftPanel` | Card. `draftLink` vs `driveLink` distinction is business logic — untouched. |
| `ExpertCard` | Row-entity pattern: 45px round avatar, name at 500 capitalize, metadata secondary. |
| `PortalLinkPanel` | Card. Never render a token — status only. |
| `RedactedProfilePanel` | Card. Redaction and the paid gate are logic. |
| `StageActions` | Buttons in the new language; role/stage gating unchanged. |
| `Timeline` | **No template counterpart.** Derive: `.box`, 15px rows, accent rail/dot, actor + relative time secondary at `fs-13`, `tabular-nums` on times. Actor-type distinction (staff vs CLIENT vs EXPERT) must stay visible — it is the point of the Unit 14 audit work. |
| `StrategyNotes` | Textarea in the new form style; the read/write role split is logic. |

### `features/checklist/*`

`ChecklistBoard` → column cards matching the production board. `CaseChecklist` → table
in the new table style (14px/500 headers, 20px cells, status pills). **Keep the
`Unpaid` chip** even though every case now arrives paid — it reads a real column.
Item-status colours map to RAG, not to decorative pairs.

### `features/experts/*`

| Component | Change |
|---|---|
| `ExpertRoster` | The template's table treatment fits directly: `text-nowrap`, avatar+name, status pill, trailing action column, `.table-responsive` with the slim accent scrollbar. |
| `AvailabilityBoard` | Card grid; availability → RAG, and **capacity bands stay 70/90** from `ui-context.md`. |
| `ExpertProfile` | Card sections, possibly `.nav.panel-tabs` for grouping. `payment_detail` stays write-only — no read path, no display. |
| `SheetUpload` / `ImportReport` | New form styling; drop-zone as a dashed `--border-default` panel at card radius. Report keeps its per-row error detail. |
| `ShortlistPanel` | Score breakdown in the stat-tile idiom; the four factors and their weights are logic. |

### `features/auth/LoginPage.tsx`

`user-login.html` is a direct model: centred card on the tinted canvas, generous
radius, large fields, full-width accent button.

### `features/client-portal/*` — **the trap**

**The portal must not receive the shell.** `App.tsx` answers `/portal/` before any
staff-session code runs and outside `AuthProvider`, and it gets no `AppShell`, no nav
and no brand switcher — because a portal token admits one case and there is nothing to
navigate to. Applying the sidebar layout here would be a functional regression dressed
as a visual one.

Style `PortalRoot` / `ClientDraftView` as a **single centred card on the tinted
canvas**: the template's login-page geometry, not its dashboard geometry. Same tokens,
same radius, same shadow, no chrome. When Unit 15 adds `/portal/expert`, it inherits
this treatment.

### `components/Forbidden.tsx`, `pages/NotFound.tsx`

Centred card, `fs-18` secondary copy, one accent action. `Forbidden` keeps
`/dashboard` (reachable by every role); 403 stays a **screen, not a redirect**, so the
refused URL remains visible.

---

## Order of work

1. **Tokens only.** Land the token diff. Every screen shifts colour and radius at once
   with no component edits. Screenshot before/after; this is the cheap 60%.
2. **Shell.** `AppShell`, `LeftNav`, `TopBar` and the three header controls. Once the
   floating sidebar and tinted canvas are in, the app reads as the template.
3. **One reference screen, then approval.** `BoardView` + `CaseCard` + `StageColumn` —
   the most-used screen and the one with the most status semantics. Stop and get sign-off
   before propagating.
4. Case detail and its panels. 5. Checklist. 6. Experts. 7. Login, portal, error pages.
8. **Dark mode, only if wanted.** Protend ships it; EvalOS has none. It is a
   `.dark`-class token override and touches no component — cheap, and cheapest last.

## Definition of done, per screen

- `npm test` green. **No `*Rules.test.ts` file was edited.** If one fails, the
  migration changed behaviour.
- `npm run build` and `npm run lint` clean.
- `git diff --stat` shows no `*Api.ts`, `navigation.ts`, `filtersContext.ts`,
  `session.ts` or `auth.tsx` change.
- Tabular figures still measure equal-width, verified not assumed.
- `:focus-visible` still rings, checked with the keyboard.
- Every RAG colour still comes from `--status-*`.
- Checked at 1440, 1366, 1200, 991 and 767. **1366×768 is the reference screen** — it is
  what the staff run, and it is what the 240/72/36 scale was set against.
- On the board at 1366×768: the page heading, filters, pool and every column header are
  visible without scrolling, and the columns scroll their own cards.

## Open decisions — needed before step 2

| Decision | Options | Recommendation |
|---|---|---|
| **Font** | Keep Inter · adopt Roboto (what Protend renders) · adopt Poppins (what it declares) | **Keep Inter.** Already loaded and measured with real tabular figures. The template's identity lives in its colour, radius and shadow, not its typeface — and its own font wiring is broken. |
| **Icons** | Inline SVG as today · add an icon set | The sidebar needs ~12 icons and the shell a few more. Boxicons via CDN conflicts with the zero-CDN posture; inline SVG for ~15 glyphs costs nothing and adds no dependency. **Stay inline** unless the count grows past ~30. |
| **Sidebar width** | 400px faithful · 320px adapted · 240px | **Settled: 240px** (13rem ≤1200px). 320px was still 23% of a 1366 laptop. Reasoning in the deviation table above. |
| **Dark mode** | Yes · no | Defer. It is additive and touches only tokens. |
| **Charts** | ApexCharts · Chart.js · none yet | This closes the open question `ui-context.md` records for **Unit 17b**. Protend uses **ApexCharts**, so adopting it keeps the chart look consistent with the rest of the language. Decide when 17b starts, not now. |
| **`--accent-primary`** | `#3C21F7` faithful · slightly desaturated | Faithful. It is the template's identity, and it passes contrast on white. |

## Provenance

Extracted from `protend-package/protend/`: `css/style.css` (89 KB, sections 1–9),
`css/responsive.css`, `index.html`, `project.html`, `clients.html`,
`client-details.html`, `board.html`, `new-project.html`, `user-login.html`,
`user-profile.html`, `chart-apex.html`.

Cross-references: `context/ui-context.md` remains the source of truth for RAG
semantics, capacity thresholds and tabular figures — this document defers to it and
never restates a threshold.
