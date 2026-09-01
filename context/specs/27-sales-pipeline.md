# Unit 27 - Sales: the sales pipeline (GM)

> **Status: built.** Backend, frontend, config, tests and docs landed together.
>
> Spec written alongside the code, per the process Unit 26 established. It is
> short for the same reason Unit 26's was, and more so: Unit 24 built the
> machinery, Unit 26 proved it generalised to a second pipeline, and this points
> a third screen at a third one. **The interesting part of this unit is not the
> feature - it is one invisible character in a pipeline name.** See below.

## Why this exists

The GM could see both **acquisition** funnels (paid search, email) and none of
the **selling** that happens after them. The same GHL location runs the sales
team's own working pipeline - *Aditya's pipeline* - and it was invisible from
inside EvalOS for no reason other than that no unit had asked for it.

Unit 26 answered "is a second reading of a pipeline in this location allowed?"
with *yes, on the same terms*. This unit asks nothing new: it is a third reading
on those same terms. Invariant 2 is untouched - see *The line this does not
cross*.

## What it builds

- `GET /api/marketing/sales-pipeline` - GM-only, `range` in
  `today|week|month|year`, payload identical to the two funnel routes.
- `/sales/pipeline` in the nav ("Sales pipeline"), under a **new `Sales` nav
  group**, GM-only.
- `evalos.ghl.sales-pipeline-name`, defaulting to `Aditya's pipeline`.
- `Funnel.SALES` in `MarketingPipelineService`; no new service, no new component,
  no new code path.

## Total cost of the third pipeline

A property, an enum constant, a route method, a nav entry, a route-table line,
and one union-type member in `marketingApi.ts`. **No new class on either side.**

That is the return on Unit 26's shape, and it is worth stating plainly because it
was the explicit instruction left in `application.yml` at the time - *"if a third
funnel is ever asked for, add a third property and a third constant; do not turn
this into a map keyed by whatever GHL happens to call a pipeline this quarter."*
The instruction was followed and it held. The comment is now updated to say
"three names" and to record that the prediction was tested.

## Why it is under Sales and not Marketing

The nav heading is the one place this unit makes a real decision rather than
copying one.

The two existing screens are **campaign funnels**: leads a channel produced,
grouped by where they got to. This is a **salesperson's working pipeline**. It
carries stages the marketing funnels do not - `Meeting booked`, `Invoice sent`,
`Refund` - and filing it under Marketing would present three sets of numbers as
comparable channel results, which is the one thing they are not.

It sits **between Marketing and Pipeline** because that is the order of the
business: a campaign produces a lead, sales closes it, and EvalOS takes custody
at Handoff A - everything in the `Pipeline` group is after that point.

**What did *not* follow the heading:** the API route stays under
`/api/marketing/`, on `MarketingController`, in `marketingApi.ts`. A
`SalesController` holding one method that called
`MarketingPipelineService.forCaller`, or a `salesApi.ts` duplicating every type
to rename one string, would split one integration across two doors to fix a
word. The naming debt is real and is smaller than the split. Recorded here so it
is a decision and not an oversight.

## What reading the live pipeline revealed

**The pipeline is named `Aditya's··pipeline` - with two spaces.**

`GhlPipelineClient.pipelineNamed` matched with `equalsIgnoreCase`, so the
single-space spelling that any human would type into an environment variable did
not match, and the screen would have answered 502.

That 502 is the *right direction* to fail in - Unit 24 chose name-matching over
id-matching precisely so a rename in GHL breaks loudly instead of showing an
empty funnel that reads as a bad month. But it is a failure whose cause is
**invisible in both places anybody would look**: the configured value and GHL's
value render identically side by side, and no message could say "these differ by
a space you cannot see".

The available responses were:

1. **Paste the double space into the three yml profiles.** Rejected. It is a trap
   with a comment on it: correct only until an editor, a linter, a shell, a
   deployment template or a well-meaning reviewer normalises whitespace, and it
   fails as an unexplained 502 when one does.
2. **Configure by pipeline id instead.** Rejected for this pipeline alone -
   it would make one of three funnels behave differently from its siblings and
   silently lose the rename-detection Unit 24 chose deliberately.
3. **Normalise whitespace in the comparison.** Taken.

So `pipelineNamed` now compares names with edges trimmed and internal whitespace
runs collapsed. Configuration holds the name a human would write.

**What is deliberately not normalised:** case was already handled, and nothing
else is - no punctuation stripping, no apostrophe folding, no fuzzy distance. A
name differing by a real character is a different pipeline and must still fail
loudly, because that is the entire reason matching is by name. `Adityas pipeline`
(no apostrophe) is asserted to still fail, as the guard on that boundary.

The fix is in the **shared client**, so it applies to all three funnels rather
than to the one that exposed it. The next pipeline with a stray space costs
nobody an afternoon.

## Stage semantics: three new stage names, zero special cases

This pipeline has nine stages against the marketing funnels' six, and three of
them are new to EvalOS: `Meeting booked`, `Invoice sent`, `Refund`.

None is special-cased. `MarketingPipelineService.Outcome` reads a stage's
**name** and recognises only GHL's own status words (`Won`, `Lost`,
`Abandoned`); everything else is `OPEN`. So all three are `OPEN`, for exactly the
reason `Cold` already was.

`Refund` is the tempting one and was declined: an outcome constant for it would
put a vocabulary in EvalOS that the pipeline's owner can rename in GHL tomorrow,
and a refund is a **money** event that belongs to the payment record rather than
to a funnel's shape. If refund reporting is ever wanted, it is a question about
payments, not about this screen.

## The line this does not cross - unchanged from Units 24 and 26

> **Superseded in part by Unit 29** (`29-sales-desk.md`, specced not built). This section
> remains an accurate record of what *this* unit does and does not do, and Unit 29 does not
> change one line of Unit 27's code. But it is no longer the standing architectural rule:
> Unit 29 amends invariant 2 so that EvalOS may **operate** this pipeline. The half of this
> section that survives is the last one — **nothing is persisted, and there must not be a
> `ghl_opportunity` table** — which Unit 29 promotes from an incidental decision to the
> load-bearing one, because a remote control with no copy has nothing to fall out of date.

Reading is not running, and reading a *sales* pipeline is still not selling.
Nothing creates a lead, moves a stage, prices a deal or sends anything; the
credential is `opportunities.readonly` and the client has no write method.
**Nothing is persisted** - no `ghl_opportunity` table, and there must not be.
This is a third window, not a sales module.

## Scoping: GM-only, for the reason that has not changed

Still one global `evalos.ghl.location-id` with no link to a brand, so the figures
cannot be attributed to a brand, let alone narrowed to one. The endpoint takes no
`brandId` because none would narrow anything.

**This is the unit where that rule was most likely to be dropped**, because "sales
is not marketing" invites the assumption that the marketing scoping exception does
not apply. It does: same location, same unattributable brand, same door. The
nav test asserts all three paths are GM-only in one loop, with the reasoning
written where someone would go to add a fourth.

**Unit 25a now re-scopes three screens, not two.**

## Verification

- `MarketingControllerTest` - the sales route delegates `Funnel.SALES` (asserted
  on the funnel the service receives, not on the body: three routes returning
  identically-shaped payloads make a copy-paste invisible), and is GM-only with
  every other role 403 and anonymous 401.
- `MarketingPipelineServiceTest` - the cache-collision test now holds all three
  funnels at once. Deliberately not a separate SALES test: the failure guarded
  against is a *collision*, and a test of SALES in isolation would pass with the
  funnel key dropped from the cache lookup entirely.
- `GhlPipelineClientHttpTest` - fixture carries the real double-space name; the
  lookup passes the single-space one. Plus the boundary case: a name differing by
  a real character still fails.
- `GhlPipelineClientLiveTest` - **ran green against live GHL.** The single-space
  configured name resolved to `tj2agZ90S1LQgCpDAoKi` with all nine stages
  `[Meeting booked, New Lead, Warm, Hot, Invoice sent, Won, Cold, Lost, Refund]`.
  This is the assertion that matters: the unit test proves the normalisation
  against a fixture *we* wrote, and only a real call proves the fixture matches
  what GHL actually returns.
- Full suites: 468 backend tests, 118 frontend tests, `tsc --noEmit` clean.

Depends on: 24, 26.
