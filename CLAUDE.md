## EvalOS — Application Building Context

EvalOS is the back-of-house production CRM for a **multi-brand** credential-
evaluation business (brands include International Evaluations and XpertsPortal).
It takes custody the moment a contact is created in GoHighLevel (GHL), records
the payment against that case, and owns it through signed delivery and expert
payout. GHL remains the front of
house (leads, sales, invoicing, review campaigns). EvalOS never does marketing,
sales, or invoicing.

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
