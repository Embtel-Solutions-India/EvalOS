# Unit 70a — The staff app reads through TanStack Query

**Decided 2026-10-01 by the business (D68, amended).** **Status: phase 1 BUILT 2026-10-01; phase 2
not started.** Spec 70 §4 builds on this unit instead of a hand-written reload hook.

## 0. Before this unit

- The portals read through `@tanstack/react-query` v5; **the staff app did not.** Each of its ~39
  screens fetched once in a `useEffect` (55 GET call sites) and never re-read. After an action a
  screen reloaded only itself, so a neighbouring panel, the board and the bell stayed stale, and
  nothing re-read when a person came back to the tab.
- The bell's unread count was read once, on mount.

## 1. Decisions

| # | Question | Answer |
|---|---|---|
| 1 | Which library? | `@tanstack/react-query`, the version the portals already use. One `QueryClient` in `lib/queryClient.ts`, provided in `main.tsx`. |
| 2 | Defaults | `staleTime: 0` and `refetchOnWindowFocus: true` (a staff screen re-reads whenever the tab comes back), `retry: 1`. A re-read keeps the current data on screen: a screen shows its loading state only when it has no data yet. |
| 3 | How does one action refresh the others? | **In one place, not per action.** The `api` instance's response interceptor invalidates every case-shaped query after any successful non-GET request. Only queries on screen re-read; the rest are marked stale and re-read when next shown. A new write cannot forget to refresh anything. `/chat/**` is excluded (typing, read marks and presence post often and change no case data), and so is the sign-in route. |
| 4 | Which queries are case-shaped? | Every key whose first element is in `CASE_KEYS`: `case`, `board`, `checklists`, `notifications`, `draft-review`, `pm-notes`. Adding a screen means naming its key with one of them. |
| 5 | Keys | `['case', id]`, `['case', id, 'timeline']`, `['case', id, 'documents', kind]`, `['case', id, 'checklist']`, `['case', id, 'drafts']`, `['case', id, 'draft-comments', draftId]`, `['board', dueBefore, brand]` (the production board and all four queue pages share it, via `useBoard`), `['checklists', 'board', brand]`, `['draft-review', brand]`, `['pm-notes', brand]`, `['notifications', 'count']`, `['notifications', 'list']`. |
| 6 | The bell | `['notifications', 'count']` also refetches every 60 s (`refetchInterval`), so a person assigned by somebody else sees the badge without reloading. The list is read when the panel opens. |
| 7 | Other people's changes | Covered now by focus and the bell's interval. Real-time push is spec 70: its `case.changed` event becomes `invalidateQueries({ queryKey: ['case', caseId] })` plus the lists, one call. |
| 8 | Sign-in, sign-out and 401 | `queryClient.clear()` (in `auth.tsx`, which owns the session's one `onTokenCleared` slot), so the next person on a shared browser never sees the last one's cache. |

## 2. Phase 1 (built): the screens the business reported

The bell; the case page (`CaseDetail` with its timeline, `DocumentList`, `DocumentsPanel`,
`DraftComments`, `DraftHistory`); the production board (`BoardView`); the queues (`InboxPage`,
`DraftQueuePage`, `DeliveryQueuePage`, `MyDraftsPage`, `ExpertAssignmentPage`, `PmNotesPage`); the
checklist (`CaseChecklist`, `ChecklistBoard`).

## 3. Phase 2 (not started)

Payouts, experts, meetings, dashboards and the remaining one-off fetches: the same pattern, a key
per read and nothing else, since the interceptor already invalidates.

## 4. Verified

In Chrome, 2026-10-01, against a current backend: the PC's open board, with no reload, gained a case
a PM assigned through the API, and the bell went 0 → 1 ("You are the coordinator on IE-2026-4806"), on
the tab's return (`visibilitychange`); marking it read cleared the badge through the interceptor alone;
the case page rendered every panel. Note: TanStack v5's focus manager listens on `window`, not `focus`.

## 5. Tests

`queryClient.test.ts`: a successful POST/PUT/PATCH/DELETE invalidates the case-shaped keys and
leaves others; a GET, a `/chat/` write and a sign-in invalidate nothing. A failed write never reaches
`afterRequest`: the interceptor calls it on the success path only.
