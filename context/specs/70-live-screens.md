# Unit 70 — Screens that update themselves

**Decided 2026-10-01 by the business (D68).** **Status: BUILT 2026-10-01** (see
`implementation-status.md` item 15). §8's cause was the Ably key lacking publish (40160); the GM's
wildcard brand channel cannot be subscribed, so (2026-10-02) the GM's token names each brand's
channel instead; `CaseLiveHibernateTest` (Postgres) is written.

## 0. Today, before this unit

- **Only chat is live.** `ChatFanout` relays chat changes over Ably into each member's private
  channel (Unit 57 §5). Nothing else in EvalOS is ever pushed to a browser.
- **The staff app loads each screen once.** Every page and panel fetches in a `useEffect` on mount
  (`CaseDetail`, `DocumentList`, `DocumentsPanel`, the queue pages, `ChecklistBoard`, the payouts
  pages, …). Nothing refetches afterwards, so a change somebody else makes appears only after a
  reload. **The bell's unread count is read once on mount too** (`NotificationBell.tsx:37`), so a
  PM told "a draft is waiting" does not see the badge until they reload.
- **The portals use react-query** (`staleTime: 60_000`), so they refetch on window focus, but
  only once the data is more than a minute old, and never while the tab stays in front.
- Example: a CM uploads a draft → the PM's bell and draft queue stay stale; a PC adds a checklist
  item and presses Send → the client, if the portal is already open, sees nothing new until a
  reload or a refocus more than a minute later.

## 1. Decisions inside this unit

| # | Question | Answer |
|---|---|---|
| 1 | What is pushed? | **A signal, never data.** `case.changed {caseId}` and `notifications.changed {}`. The screen re-reads over the REST route it already uses, so scoping, redaction and brand checks stay where they are (invariant: Ably relays, PostgreSQL is the record, D50). |
| 2 | How is a write detected? | **Hibernate, not per call site.** An in-transaction `PostInsert`/`PostUpdate`/`PostDelete` listener sees every case-owned entity (§2) and adds its case id to a set bound to the transaction; **after commit** each id is published once. A rolled-back write publishes nothing. New write paths are covered without being remembered. |
| 3 | Who receives `case.changed`? | **Staff:** a new per-brand channel `live:brand:{brandId}` (GM: every brand). **Client:** the case's client account's existing private channel. **Expert:** the case's expert's and every open-offer expert's private channel. |
| 4 | Is a brand channel a leak? | It tells a staff member of that brand that *some case id* changed, even one they are not assigned. The id is not the content, and the re-read goes through the scoped REST route, which still refuses it. Accepted; the alternative is a lookup of every possible viewer per write. |
| 5 | Who receives `notifications.changed`? | The recipient, on their own staff channel, after the `notification` rows commit. |
| 6 | What does a screen do with it? | Re-reads **in the background**: the current data stays on screen until the new data arrives (no spinner, no lost selection, no closed dialog). Debounced 500 ms, so a transaction touching ten checklist items is one re-read. |
| 7 | And when Ably is down? | Every live screen also re-reads when the tab becomes visible again and when the realtime connection comes back (the chat client already detects the reconnect). There is no polling. |
| 8 | What is out of scope? | The opportunity board and anything else mirrored from GHL (it has its own sync stamp and Refresh, Unit 46); the dashboards and metrics (focus re-read only); chat itself (already live). |

## 2. Backend

### 2.1 Case-owned entities

Anything that implements the new `CaseOwned` interface (`UUID liveCaseId()`):

| Entity | `liveCaseId()` |
|---|---|
| `Case` | `getId()` |
| `CaseDocument` | `getCaseId()` (uploads, drafts, delivered files) |
| `DocumentChecklistItem` | `getCaseId()` (add, status, send) |
| `ExpertCaseOffer` | `getCaseId()` (offer, accept, decline, evidence request) |
| `PayoutLedger` | `getCaseId()` |
| `DraftComment` | none of its own; the listener records its `documentId` and `CaseLive` resolves the case after commit with `CaseDocumentRepository.findById`, kept only when the document's `brandId` equals the comment's (a listener has no caller whose scope applies, as `CaseMailListener`) |

`AuditEvent` is **not** a source: its `object_id` is a case id only for `CASE` rows, and every
audited case change already writes one of the entities above.

**Bulk and native writes bypass Hibernate's listeners.** Checked 2026-10-01, two JPQL bulk
updates write a table in §2.1, both in `PayoutLedgerRepository`: `attachToPayment` (ENM records a
transfer) and `confirmForPayment` (the expert confirms it). `PayoutService` calls
`CaseLive.touched` for each affected row's case after each of them (it already holds the rows, or
reads them with `findByPaymentId`). The only other `@Modifying` query is
`NotificationRepository.markAllReadFor`, which §2.3 covers. **Any future bulk write to a table in
§2.1 must call `CaseLive.touched(brandId, caseId)` itself.** Add that sentence to
`code-standards.md`.

### 2.2 `chat/live/CaseLive` (new, the only publisher)

- `touched(UUID brandId, UUID caseId)` — called from the Hibernate listener inside the
  transaction. It adds the id to a `TransactionSynchronizationManager`-bound set, registering one
  `afterCommit` synchronization on first use. Outside a transaction it publishes immediately.
- `afterCommit` hands the set to the existing `chatFanoutExecutor` (never the request thread,
  review I6 of Unit 57) and, per case, publishes `case.changed` (`ChatEnvelope`, `conversationId`
  null, `data {caseId}`) to:
  - `live:brand:{brandId}`;
  - `ChatChannels.personal(CLIENT, account.id)` for
    `ClientAccountRepository.findByBrandIdAndContactId(brand, case.contactId)`, when present;
  - `ChatChannels.personal(EXPERT, id)` for `case.expertId` and each `OFFERED` offer's expert.
- Does nothing when `ChatRealtime.enabled()` is false. A failed publish is logged and dropped
  (`ChatRealtime.publish` already does this).
- Registered with `EventListenerRegistry` from a `HibernatePropertiesCustomizer` bean
  (`hibernate.integrator_provider` is not needed).

### 2.3 Notifications

`NotificationService.create` registers an `afterCommit` that publishes `notifications.changed` to
`ChatChannels.personal(STAFF, recipient)` for each recipient. `markRead` and `markAllRead` publish
too, so a second open tab clears its badge.

### 2.4 Token capability

`ChatChannels.capability` gains, for **staff only**:
- GM: `live:brand:{id}` → `subscribe` for **every brand, named** (`ChatApi.realtimeToken` lists them; a wildcard cannot be subscribed);
- everyone else on staff: `live:brand:{their brandId}` → `subscribe`.

Portal tokens are unchanged: they already subscribe to their own private channel.
`ChatChannels.liveBrand(UUID)` names the channel. Nothing may publish from a browser, as today.

## 3. The chat package (`packages/evalos-chat`)

- `realtime.ts`: for staff, also subscribe to `live:brand:{brandId}`. The brand comes from a
  capability key starting `live:brand:` in the token, so the package learns no new configuration.
  A GM's token names every brand, so they subscribe to all of them.
- `client.ts`: an envelope whose type is `case.changed` or `notifications.changed` goes to a new
  `onLive(listener)` subscription and **not** to the reducer (it already ignores unknown types; this
  makes that explicit). `onLive` also fires `{ type: 'reconnected' }` from the existing
  `onReconnect`.

## 4. Staff app

**Rewritten 2026-10-01: builds on Unit 70a** (`70a-staff-app-on-query.md`). The staff app reads
through TanStack Query, and every screen's read sits under a case-shaped key, so there is no
hand-written reload hook to wire into each component.

- `frontend/src/lib/live.ts`: one listener on the chat client's `onLive`. `case.changed {caseId}`
  → `queryClient.invalidateQueries({ queryKey: ['case', caseId] })` plus the list keys (`board`,
  `checklists`, `draft-review`, `pm-notes`); `notifications.changed` → `['notifications']`;
  `reconnected` → every case-shaped key (the missed window).
- **Background reload** and **tab-focus re-read** already hold (70a §1): a re-read keeps the data
  on screen.
- **Not while someone is typing:** query re-reads replace data under an open form. A dialog whose
  fields are seeded from a query copies them into local state when it opens (the pattern the
  quick-action dialog already follows), so a re-read never overwrites what somebody is typing.
- Phase 2 of 70a (payouts, experts, meetings, dashboards) lands before this, or those screens
  keep their mount-only reads.

## 5. Portals

- One `LiveInvalidate` component inside each portal's `ChatProvider`: on `case.changed` it calls
  `queryClient.invalidateQueries({ queryKey: ['portal'] })` (client) or `['expert-portal']`
  (expert), and all queries on reconnect. react-query already keeps the old data while refetching.
- Both `QueryClient`s set `refetchOnWindowFocus: 'always'`, so a focus re-reads regardless of the
  60 s `staleTime`.

## 6. Tests

- `CaseLiveTest`: two writes to one case in one transaction → one publish; a rollback → none;
  client and expert channels resolved; nothing when Ably is off.
- `CaseLiveHibernateTest` (Postgres, `LocalPostgresIntegrationTest` style): saving each entity in
  §2.1, a draft comment included, publishes its case id after commit.
- `ChatChannelsTest`: the staff capability names its own brand's live channel; GM gets `*`; a
  client or expert capability does not change.
- `PayoutServiceTest`: recording and confirming a payment touch every settled row's case.
- `NotificationServiceTest`: `create` publishes `notifications.changed` per recipient after commit.
- `client.test.ts`: `case.changed` reaches `onLive` and leaves the reducer state unchanged.
- `live.test.ts` (staff): debounce, the per-case filter, visibility, paused.

## 7. Docs this unit changes when built

`architecture.md` (the live channel beside chat's), `workflows.md` §5 and §7 (CURRENT
IMPLEMENTATION), `implementation-status.md` (the row this spec adds), `code-standards.md` (§2.1's
bulk-write rule), and the matching Serena memories.

## 8. Before building: prove Ably in production

Chat is not live in production today although `ABLY_API_KEY` is set (reported 2026-10-01). This
unit rides the same connection, so find that cause first: `GET …/chat/realtime/token` status, the
websocket to Ably and any Ably error code in the console, and `Ably publish … failed` in the
backend log.
