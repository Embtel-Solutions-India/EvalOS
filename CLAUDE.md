## EvalOS — Application Building Context

EvalOS is the back-of-house production CRM for a **multi-brand** credential-
evaluation business (brands include International Evaluations and XpertsPortal).
It takes custody the moment a contact is created in GoHighLevel (GHL), records
the payment against that case, and owns it through signed delivery and expert
payout.

**This is changing, and the change is decided (2026-09-10).** The line above
used to end "GHL remains the front of house (leads, sales, invoicing, review
campaigns). EvalOS never does marketing, sales, or invoicing." Across
**Units 36–41** EvalOS becomes the **interface Sales and Marketing work in**,
so that they never open GHL. GHL stays the CRM, the pipeline engine, the
automation engine and the invoice/QuickBooks integration **underneath** —
it stops being the front *desk* and remains the front *office*.

Read **`context/specs/00b-ghl-operational-programme.md`** before touching
anything in that programme: it carries the truth model (GHL owns the
opportunity, EvalOS owns the note stream), the single-brand ceiling, and the
ledger of which invariants die and where. **Invariant 2 is still live until
Unit 37 ships** — until then, EvalOS reads GHL and writes nothing back, and
`GhlHttpTest` fails the build if that changes.

Two things the pivot does **not** touch: **invoicing is still GHL's** (EvalOS
reads invoices, raises none), and **a case is still born only of a won
opportunity** through Handoff A.

**A second programme follows it, and it is bigger (decided 2026-09-11/12).**
`context/specs/00c-ghl-independence-programme.md` (Units 42–49) makes EvalOS
hold an **id-faithful mirror of GHL** — same pipeline, stage, contact and
opportunity ids on both sides — sync it both ways, and **keep working when the
sync is switched off**. Read it before touching the mirror, the sync engine, or
the client portal's front door.

- **Units 42 and 43 are the client's sign-in and signup**, and they do not wait
  for the rest of the programme. **The Client Portal now has accounts** — a
  welcome screen, email-first sign-in, and password set/reset — which reverses
  `34-portal-frontend-wiring.md` D1 in writing.
- **EvalOS now sends email**, for authentication only. Invariant 14 is amended,
  not deleted: two messages, and any other mail is a new decision.
- **IE's GHL sub-account was replaced on 2026-09-11** — location
  `WY6bW2xUCI8Tz8gw7aLJ`, fresh, with no contact migration. Every
  `ghl_contact_id` EvalOS holds names a contact that no longer exists. The
  checklist, and what that breaks, is `00c` §1.

The authoritative design is the **EvalOS Technical Design Document (v1.1)**. The
context files below are the working build context and must stay consistent with
it. Read them in order before implementing or making any architectural decision:

1. `context/project-overview.md` — product definition, goals, the case
   lifecycle, features, and scope
2. `context/architecture.md` — stack, multi-tenancy, boundaries, storage model,
   the three handoffs, and invariants
3. `context/ui-context.md` — surfaces, RAG status colors, typography, components
4. `context/code-standards.md` — implementation rules and conventions
5. `context/ai-workflow-rules.md` — workflow, scoping rules, delivery approach
6. `context/progress-tracker.md` — current phase, decisions, open questions

The full, ordered unit list lives in `context/specs/00-build-plan.md`.
Individual unit specs live alongside it as `context/specs/NN-name.md` and are
generated just before each unit is built.

Two rules that override convenience everywhere:

- **Brand-scoped by default.** Every scoped query filters by `brand_id`
  (plus team/assignee where applicable). A query without brand scoping is a bug.
- **Append-only truth.** Audit and assignment history are never updated or
  deleted.

Update `context/progress-tracker.md` after each meaningful implementation
change. If implementation changes the architecture, scope, or standards, update
the relevant context file (and the TDD if a decision changes) before continuing.

In the same step, update the affected **Serena memory** in `.serena/memories/`
so the next session inherits the current picture. When a decision changes, edit
the memory that states the old one — never leave a contradicting note beside it.
`context/ai-workflow-rules.md` maps each kind of change to its memory.
