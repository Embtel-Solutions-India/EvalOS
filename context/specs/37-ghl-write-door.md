# Unit 37 — The GHL write door

> **Status: BUILT 2026-09-10** (665 backend tests green, was 659). Programme decisions:
> `00b-ghl-operational-programme.md`.
>
> **This unit ships no feature and no screen.** It adds three methods to one class. It exists as a
> unit of its own because those three methods **reverse invariant 2**, and a reversal that happens
> as step four of a feature ticket is a reversal nobody decided on.

**Phase:** 3 — EvalOS as the operational system
**Depends on:** 36 (the roles that will use it)
**Unlocks:** 38, 39, 40
**Migration:** none
**Gating open questions:** none

---

## 1. What changes, in one paragraph

`GhlHttp` gains `post`, `put` and `delete`. `GhlHttpTest`'s assertion that those verbs are **absent
from the codebase** is rewritten from "there are none" to "there are exactly these three, and every
one of them is audited". `architecture.md` invariant 2 is rewritten. Nothing calls the new verbs
yet — Unit 38 is the first caller.

## 2. Why this is a unit and not a commit

`GhlHttp`'s class comment states the current guarantee:

> **Read-only, and the absence is the guarantee.** There is no `post`, `put` or `delete` here:
> EvalOS reads GHL and writes nothing back. Those verbs existed for the sales desk, which is gone,
> and they went with it rather than staying present-and-unused — a capability nothing calls is one
> somebody reaches for without deciding to. Note what this does *not* rest on: the credential is
> still `opportunities.write` + `contacts.write`, so this is held by code alone, which is why
> `GhlHttpTest` asserts it rather than a comment claiming it.

**That paragraph is doing real work and it is about to be deleted.** The credential has always
permitted writes; the only thing stopping them is a test. Deleting a build-failing guard is exactly
the class of change that should cost a spec, a commit message and a reviewer — not a line in a diff
titled "add sales board".

**This has happened once already.** Unit 29 added the verbs for a sales desk; `V30` removed the
desk and the verbs went with it. The cheap reversal was possible because **no EvalOS row held a
pipeline fact**. That property ends in Unit 38, not here — so Unit 37 is still cheap to reverse,
and it is the last point in the programme where that is true.

## 3. What is added

```java
public <T> T post(Class<T> type, Function<UriBuilder, URI> uri, Object body);
public <T> T put(Class<T> type,  Function<UriBuilder, URI> uri, Object body);
public void  delete(Function<UriBuilder, URI> uri);
```

Three properties they inherit unchanged from `get`, and one they add:

1. **The same shared pacer.** `pace()` is called on the write path too. GHL's 100-requests-per-10-
   seconds is per *location*, not per verb — a write path with its own limiter is the exact bug
   `GhlHttp` was extracted to prevent, arriving from the other direction.
2. **The same error mapping.** `GhlUnavailableException` with the upstream status in the message.
3. **The same not-configured refusal.** No token, no location → the write never leaves the JVM.
4. **New: writes are audited by the caller, and the door does not audit for them.** `GhlHttp` is
   transport (invariant 12 — "webhook transport carries no business logic", and the same reasoning
   applies to this transport). It does not know what a write *means*, so it cannot write a
   meaningful audit row. §5 is how that is enforced rather than hoped for.

**`delete` returns void and takes no body**, matching the HTTP method rather than the pattern of
the other two. There is no `patch`: GHL's API does not use it, and adding a verb no endpoint takes
recreates the "present and unused" problem this unit is being careful about.

## 4. Idempotency

GHL's own operation metadata marks its write operations `idempotencyRequired: true`. **What that
means for us is not decided in this unit** and is deliberately deferred to the first caller —
because the right key depends on what is being written, and inventing a scheme with no caller
produces a scheme the caller then works around.

**Corrected 2026-09-10, during Unit 38.** This section originally named **Unit 38** as the first
caller. It is not: Unit 38 reads opportunities and writes nothing, as its own §8 says. **The first
caller is Unit 39** (creating a contact and an opportunity), and that is where the scheme is
decided. Recorded rather than silently repointed, because two specs disagreeing about who owns a
decision is exactly how the decision ends up owned by nobody.

**What this unit does do** is make sure the question cannot be skipped silently: the three methods
take their URI builder and body from the caller, so a caller that needs an idempotency header sets
one, and Unit 39's spec carries the section that must answer it.

## 5. The test that replaces the guard

`GhlHttpTest` today asserts **no write verb exists**. That assertion is deleted, and it is replaced
rather than dropped — the point of the original was never "zero writes", it was "no write reaches
GHL that nobody decided on".

The replacement is two structural assertions:

- **The verb list is closed.** `GhlHttp` exposes exactly `get`, `post`, `put`, `delete` and the
  three accessors. A fifth verb fails the build, so the next one is also a decision.
- **Every caller of a write verb writes an audit row.** A structural test over the call graph: any
  class calling `post`/`put`/`delete` must also reach `AuditService`. This is the invariant-13
  guarantee for writes that land in another system — **a mutation whose only trace is in GHL is
  invisible to EvalOS forever**, and that is the failure mode of moving the desk into EvalOS.

  `ponytail:` the call-graph check is a structural test in the style of `DomainInvariantsTest`, not
  a proof. It catches the class that forgot; it does not catch a class that audits the wrong thing.
  The upgrade path if that matters is an aspect on the three verbs.

## 6. Invariant impact

- **2 — dies here.** `architecture.md` invariant 2 is rewritten in this unit's commit, not left to
  drift. The new text must keep three things the old one earned: **Handoff A is still the only door
  a case enters custody through** (a won opportunity creates the case, and nothing in this
  programme changes that); **invoicing is still GHL's**, EvalOS reads invoices and raises none of
  its own accounting; and the record of the Unit 29 round trip, because the reasoning about
  reversibility is what made this unit exist.
- **12 — extended by analogy, not amended.** The rule is that transport carries no business logic.
  `GhlHttp` is transport, so it audits nothing and decides nothing. §5's second test is what keeps
  that from meaning "nobody audits".
- **13 — unchanged and load-bearing.** Every GHL write from EvalOS writes an audit row.
- **7 — untouched here.** This unit writes no contact. Unit 39 amends invariant 7.

## 7. What this unit deliberately does not do

- **No caller.** Nothing invokes the new verbs. That is the point: the door and the first thing
  through it are separate decisions, and the door is reversible until Unit 38 stores a row.
- **No new scope request.** `opportunities.write` and `contacts.write` are already granted. The
  three scopes the programme still needs (`invoices.readonly`, `calendars/events.write`,
  `calendars.readonly`) are Units 40 and 41's, and are in `00b` §3.
- **No idempotency scheme** (§4).
- **No retry.** Reads do not retry today and writes must not start: a blind retry on a write with
  no idempotency key is how one opportunity becomes two.

## 8. Acceptance criteria

- [x] `GhlHttp` exposes `post`, `put`, `delete`; each paces through the shared limiter, maps
      failures to `GhlUnavailableException` with the upstream status, and refuses when unconfigured.
- [x] A fifth verb fails the build.
- [x] A class that calls a write verb without reaching `AuditService` fails the build.
- [x] Two `GhlHttp` instances still share one pacer — the existing assertion, unchanged, now
      exercised across a read and a write.
- [x] Writes do **not** retry.
- [x] `architecture.md` invariant 2 is rewritten in this unit's commit, keeping Handoff A, "no
      invoicing of its own", and the Unit 29 round trip.
- [x] The `.serena/memories/` entry stating the old invariant 2 is **edited**, not supplemented.
- [x] `./mvnw verify` green.


## 9. What the build added to this spec

- **`GhlHttp.reads` is renamed `http`.** The field name asserted a property the class no longer
  has, and a lie in a field name outlives every comment correcting it.
- **The failure messages are verb-neutral.** "GHL refused the read with HTTP 500" is wrong on a
  write path, and an operator reading it would look in the wrong place.
- **`DocumentStore`'s javadoc is corrected in the same commit.** It cited `GhlHttp`'s read-only
  position as the precedent for having no `delete`. That citation is now false, and it was the
  kind of stale cross-reference that later gets used to argue the opposite: the note now says
  explicitly that Unit 37 is **not** a precedent for adding delete to the document store, and
  why the two arguments differ (a desk that moved, versus evidence that must not vanish).
- **`writesDoNotRetry` runs a real server.** A JDK `com.sun.net.httpserver.HttpServer` on an
  ephemeral port counts requests and returns 500. Asserting "exactly one POST" is the only way to
  see a retry, which can arrive from the `RestClient` builder, an interceptor or a Spring
  default — reading configuration would miss two of the three. No dependency, no Docker.
- **`theWriteCallerScanBites`** — the audit scan has to be able to fail, or it is decoration. Same
  rule `SegmentIsNotAnAccessKeyTest` applies to its own scan.
- **`readsAndWritesSharePacer`**, which is the criterion's "across a read and a write": a write
  path is the natural place to build a second `RestClient`, and that is the per-location rate-limit
  bug arriving from the other direction.

**The audit rule is a tripwire, not a policy, and the distinction is deliberate.** §5 asked that
every class calling a write verb reach `AuditService`. The caller set is **empty**, because this
unit ships no caller — so the test currently asserts emptiness. That is not a weaker test: the
first unit to write to GHL has to come to this file, and in coming here it meets the requirement
before shipping rather than after an incident. Enforcing a transitive "reaches audit eventually"
across a call graph that does not exist yet would have been a scheme invented for no caller, which
the caller would then work around.