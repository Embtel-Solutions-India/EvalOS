# Unit 46 — The desks move onto the mirror

`00c` §3: *"Sales and Marketing boards read EvalOS rows, never GHL."* And the other half, which
that line does not say but `implementation-status.md` has carried since 45c: **the desks still
write to GHL synchronously.**

Depends on 45 (complete). Hands to 48, which turns the sync off and asks whether the business
still runs.

---

## 1. The read half is a deletion

`OpportunityBoardService.forCaller` already draws mirror rows — `deals.onPipelines(mine)`, since
44d. What it also does is `mine.forEach(this::refillIfStale)`: **a GHL read on the request path**,
once per pipeline, every time anybody opens a board.

That call was right when it was written. Nothing else kept the mirror current, so a board that did
not refill showed whatever the last refill left. **45d changed the premise**: webhooks absorb a
change within seconds of it happening, and `MIRROR_DELTA` (15m) is the floor under them for
pipelines nobody is looking at.

So the refill goes, and with it `evalos.ghl.board-cache-ttl`. The board becomes what §3 asks for:
EvalOS rows, never GHL — **on load, on refetch, and on moving a card**. A browser reload reads the
mirror like everything else, which means a lead created in GHL appears only once the sweep or a
manual sync has brought it in. That is the design, not a gap, and §1.2 is how the screen says so.

### 1.1 The sweep is the cadence now, so it runs every 5 minutes

`MIRROR_DELTA` was 15 minutes as a *floor under webhooks*. With the refill gone it is one of only
two things that put a new GHL lead in front of a salesperson, so it became a cadence somebody waits
on: **5 minutes**, with `delta-ttl` at 4 so clock jitter between two passes cannot silently double
the age.

It stays cheap. `refreshIfStale` is a no-op for a pipeline refreshed inside the TTL, and the manual
button below covers the case of not wanting to wait at all.

### 1.2 The screen says how old it is, and stops pretending when it does not know

- **`lastSyncedAt`** — when the sync last confirmed these pipelines against GHL. **Null when it
  never has**, and null travels as null: the old code substituted `Instant.now()`, which told the
  reader the board was current at the one moment nothing had ever been read. A null is `stale` by
  definition, because not knowing is not the same as being fresh.
- **`stale`** — true past `evalos.ghl.board-stale-after`, **5 minutes: one missed pass**. The
  business set that over a proposed 15 on 2026-09-17, and the reasoning is that this flag is the
  only thing telling a salesperson new GHL leads have stopped arriving — being told one cycle late
  is the point of having it. The cost is that the threshold equals the sweep interval, so a pass
  that runs long shows the banner briefly before clearing itself; if that becomes noise the fix is
  6m, not a slower sweep. The board draws a **"Sync delayed"** banner rather than a stamp to
  interpret, because the consequence is specific and worth spelling out: new GHL deals are not
  arriving on this screen.
- **`POST /api/opportunities/board/refresh`** — the Refresh button. It **reconciles the mirror and
  then draws from it**; it is not a live board read, and the distinction is the whole of this unit.
  Only the caller's own pipelines, so one desk's refresh cannot spend GHL's shared budget on
  pipelines they cannot see, and behind a 30-second floor so a double-click costs one read.

**It gets faster, not staler.** Today a salesperson dragging a card waits for a GHL round trip, a
refill, and a redraw. From §2 the desk writes the mirror row first, so the board redraws from local
state immediately and the push happens behind them.

**`lastSynced` stays and matters more.** The board already prints when its copy was last confirmed
against GHL; it is now the only staleness signal a reader has.

---

## 2. The write half: the mirror first, the outbox after

Every desk edit becomes the same three steps:

```
edit the mirror row locally  →  enqueue the push  →  return the local row
```

No GHL call on the request path. The drain (`SYNC_OUTBOX`, 2m) sends it.

| Desk action | Today | After |
|---|---|---|
| `SalesDeskService.update` (rename / re-price) | `ghl.updateOpportunity` | local edit + `UPSERT` |
| `SalesDeskService.moveToStage` | `ghl.moveStage` | local edit + `UPSERT` |
| `SalesDeskService.close` (won / lost / abandoned) | `ghl.setStatus` | local edit + `CLOSE` |
| `MarketingLeadService.value` | `ghl.updateOpportunity` | local edit + `UPSERT` |

**The four editable fields are exactly 45e's shared set** — `name`, `amount`, `ghlStageId`,
`status` — and that is not a coincidence. A desk edit stamps `local_updated_at`, which is what
makes the mirror defend it against a sync that arrives before the push lands. 45e built the
defence; this unit is the first thing that uses it in anger.

### 2.1 What stays synchronous, and why

**A create stays a create.** `SalesDeskService.createDeal` and `MarketingLeadService.openLead`
still call GHL inline.

- **The outbox stores an id, never a payload** (`45` §2C.2). A desk create carries an expected
  close date and custom field values; the mirror row holds neither, so queueing one would mean
  either a payload column — reopening the decision that section settled — or silently dropping
  fields the salesperson typed.
- **Custom fields are tier 2, which is Unit 47.** Mirroring them here would be reaching into the
  next unit to finish this one.
- The salesperson is sitting in front of the screen and needs the deal back. A create is the one
  desk action where waiting is the expected experience.

**The contact upsert stays synchronous** for the same shaped reason: `sync_outbox.entity_type` is
`OPPORTUNITY`, and giving contacts a queue is a unit, not a step.

**Follow-ups stay synchronous.** A GHL task is tier 3 (Unit 47). Nothing mirrors it, so there is no
local row for an outbox entry to name.

### 2.2 The cost, stated

**A won deal reaches GHL within one drain rather than within one request** — up to two minutes.
Handoff A fires from GHL's `opportunity.won`, so the case appears that much later.

This is a real regression in latency and it is taken deliberately, because the thing it buys is
larger: **a win is no longer lost to a GHL outage.** Today `close` throws, the salesperson sees a
502, and whether they retry is up to them. `SalesDeskService`'s own javadoc already tells the screen
to show a pending state and warns that the case arrives "a moment later"; this makes the moment
longer, not new.

### 2.3 A successful push confirms the edit

`Opportunity.pushedToGhl()` clears `local_updated_at` after the drain sends an `UPSERT` or a
`CLOSE`. Without it, 46 would introduce the freeze 45e was careful to avoid: every desk edit sets
the flag, and a location whose `updatedAt` comes back null would then have EvalOS defending that
row's four shared fields **for ever**, because 45e reads a null GHL timestamp as a conflict. A
create already had this through `linkGhl`; an update did not, because until this unit nothing
edited a row locally.

---

## 3. What this unit does not do

- **No new intent.** `UPSERT` and `CLOSE` cover all four edits. `UPSERT` grows the stage — it was
  passing `null` — so that "make GHL agree with the EvalOS row" is true of the whole row rather
  than of its name and value.
- **No contact, task, note or calendar queue.** Unit 47.
- **No GM dashboard change.** `GmOverviewService` still reads GHL live for the won figures. It is
  not a desk, and the figures it wants (won inside a date window) are the one read the mirror
  cannot serve until `last_status_change_at` is trusted — which is 48's evidence to gather.
- **Q1 (`MarketingLeadService.upsertOpportunity`) is not resolved here, and its gate moves.** It was
  written as "decide before Unit 46 moves the desks onto the mirror". Upsert lives in `openLead`,
  which is a *create*, and §2.1 leaves creates synchronous — so nothing in this unit turns on the
  answer. It is now gated on whichever unit moves creates onto the queue. The recommendation is
  unchanged: keep upsert for a genuine lead capture, and document it as the one exception.

---

## 4. Acceptance

1. Opening a board makes **no GHL request at all** — not a refill, not a page.
2. A board with no mirrored rows draws empty rather than fetching.
3. `update`, `moveToStage`, `close` and Marketing's `value` make no GHL request on the request
   path, and each leaves exactly one pending outbox row.
4. Each of them returns the **edited local row**, so the screen shows the new value immediately.
5. Two edits to one deal inside a drain interval collapse onto one queued row (45c's dedupe).
6. A desk edit stamps `local_updated_at`, and the mirror defends it against a sync carrying an
   older — or a null — GHL timestamp (45e).
7. A successful push clears `local_updated_at`; a failed one leaves it set.
8. `close` still creates no case. Handoff A stays the only door (invariant 8).
9. `createDeal` and `openLead` still reach GHL inline, and still refuse when GHL refuses.
