# Unit 57 — Case chat: the EvalOS Chat Service

**Decided 2026-09-25 by the business, in a brainstorming session.** Every case has three live
conversations. EvalOS owns the data and every rule (Spring Boot + PostgreSQL); **Ably relays live
updates** (2026-09-26); web push reaches anyone without the app open. No chat platform owns the data. Supersedes Unit 56 (`56-live-chat-setup.md`, the Stream token setup), whose code is
removed in phase 1. **Status: SPECCED 2026-09-25 — not built.**

## 0. What was decided, and by whom

Every row below is a business answer from the session, not an inference.

| # | Question | Answer |
|---|---|---|
| 1 | Which Sales people are in a case's conversations? | Every active **SALES** member holding the pipeline the case's deal came from (`team_member_pipeline`, not revoked) |
| 2 | GM and Brand Manager? | **Read-only viewers**, never members |
| 3 | When does the expert join? | **At offer** (`OFFERED`); removed on decline or timeout |
| 4 | Internal conversation? | Sales (pipeline holders) + the case's PM / Coordinator / Case Manager + the brand's ENMs |
| 5 | When a case ends? | **Read-only at `CLOSED`**; `DELIVERED` stays open |
| 6 | Platform? | **In-house** data and rules (Spring Boot + PostgreSQL); **Ably relays live updates** (2026-09-26 — building WebSocket infrastructure judged too costly). Stream is dropped |
| 7 | Edit and delete? | **Own messages only.** An edit shows "edited"; a delete leaves a "message deleted" placeholder; the original text is kept in `audit_event`. Nobody deletes another person's message |
| 8 | Files? | **None. Text only.** Documents have their own flow; the composer links to it |
| 9 | Push? | **Yes** — web push, the push D37 already owes |
| 10 | Where the chat sits | **Client portal:** a new case page with the case on the left and its conversation on the right. **Expert portal:** the same on `/case`. **Staff:** a **Conversations** sidebar item and a **Chat** tab on the case screen |

## 1. Conversations and members

Three conversations per case, created with the case.

| Type | Current members | `member_role` labels |
|---|---|---|
| `CLIENT` | the client (`client_account` of the case's contact, once one exists), pipeline Sales, the case's PM / Coordinator / Case Manager | `CLIENT`, `SALES`, `PM`, `COORDINATOR`, `CASE_MANAGER` |
| `INTERNAL` | pipeline Sales, PM / Coordinator / Case Manager, every active ENM of the brand | `SALES`, `PM`, `COORDINATOR`, `CASE_MANAGER`, `ENM` |
| `EXPERT` | PM / Coordinator / Case Manager, every active ENM of the brand, the expert whose offer is `OFFERED` or `ACCEPTED` | `PM`, `COORDINATOR`, `CASE_MANAGER`, `ENM`, `EXPERT` |

- **The client never shares a conversation with the expert**, which also settles open item h: the
  client learns nothing of the expert from chat.
- **Pipeline Sales** = active `team_member` rows with role `SALES` holding, unrevoked, the pipeline of
  the case's opportunity (`evalos_case.ghl_opportunity_id` → `opportunity.pipeline_id`). A case
  whose opportunity the mirror does not hold has no Sales members until it does.
- **Reassignment** moves membership and keeps history: the leaver's row gets `left_at` and they
  lose access; the arriver gets a new row and sees the whole history.
- **Viewers:** GM (every brand) and Brand Manager (own brand) may read any conversation in scope
  and do nothing else — no posting, no reactions, no read receipts. They hold no member row.
- **Access for everyone else** is current membership. A former member has none.

## 2. Data model — one Flyway migration

All tables carry `brand_id`; every query filters on it.

- **`conversations`** — `id`, `brand_id`, `case_id` → `evalos_case`, `type`
  (`CLIENT`/`INTERNAL`/`EXPERT`), `status` (`ACTIVE`/`READ_ONLY`), `created_at`, `read_only_at`,
  `last_message_at`. `UNIQUE (case_id, type)`.
- **`conversation_members`** — `id`, `brand_id`, `conversation_id`, `member_kind`
  (`STAFF`/`CLIENT`/`EXPERT`), `member_id` (`team_member.id` / `client_account.id` / `expert.id`),
  `member_role`, `joined_at`, `left_at`, `left_reason` (`REASSIGNED`, `OFFER_DECLINED`,
  `OFFER_TIMED_OUT`, `OFFER_SUPERSEDED`, `PIPELINE_REVOKED`, `ROLE_CHANGED`, `DEACTIVATED`).
  Partial unique index on `(conversation_id, member_kind, member_id) WHERE left_at IS NULL`. **A
  trigger refuses `DELETE`**, and `UPDATE` may only set `left_at`/`left_reason` on a row where they
  are null — the table is the membership history.
- **`messages`** — `id`, `brand_id`, `conversation_id`, `author_kind`, `author_id`, `body` (max
  4,000 characters), `parent_message_id` (a thread reply; replies are one level deep),
  `created_at`, `edited_at`, `deleted_at`. A delete clears `body` and stamps `deleted_at`.
  Generated `search tsvector` (`simple` config) with a GIN index. Index on
  `(conversation_id, created_at, id)` for keyset paging.
- **`message_reactions`** — `message_id`, `brand_id`, `reactor_kind`, `reactor_id`, `emoji` (one
  of six), `created_at`. Unique per message, reactor and emoji.
- **`message_reads`** — `conversation_id`, `brand_id`, `reader_kind`, `reader_id`,
  `last_read_message_id`, `last_read_at`. One row per member per conversation: the read watermark
  behind both "seen by" and unread counts.
- **`push_subscriptions`** — `id`, `brand_id`, `subscriber_kind`, `subscriber_id`,
  `endpoint` (unique), `p256dh`, `auth`, `created_at`, `last_success_at`. Deleted when the push
  service answers 404/410.

Unread count for a member = messages in the conversation from other people, not deleted, after
their watermark.

## 3. Backend module — `com.ie.evalos.chat`

- **`ChatIdentity(kind, id, brandId, role)`** — resolved once per request from the staff session
  (`TenantContext`) or the portal token (`PortalPrincipal`: a client resolves to its
  `client_account`, exactly as `ClientApplicationService.account` does; an expert to `expertId`).
- **`ChatMembership`** — pure rules: a case and its assignments → the expected `(kind, id, role)`
  set per type. No I/O.
- **`ConversationService`** — `ensureConversations(case)`, `syncMembers(case)` (diff expected
  against current rows; stamp leavers, insert arrivers, write `CHAT_MEMBER_ADDED` /
  `CHAT_MEMBER_REMOVED` audit rows), `makeReadOnly(case)`.
- **`ChatAccess`** — `MEMBER` / `VIEWER` / `NONE` for an identity and a conversation. Current
  member → `MEMBER`; GM → `VIEWER`; Brand Manager in the conversation's brand → `VIEWER`; anything
  else → `NONE`. **A client may only ever reach `CLIENT` conversations and an expert only `EXPERT`**
  — checked by type as well as by membership. `READ_ONLY` refuses every write.
- **`MessageService`** — send, edit own, delete own, react, mark read, history (keyset), thread
  replies, search, unread totals. Writes happen in one transaction, then an after-commit event
  feeds the real-time layer.
- **`ChatLifecycleListener`** — `@TransactionalEventListener(AFTER_COMMIT)` on `CaseEvent`, each
  handled in its own transaction so a chat failure can never undo a case change:
  `CASE_CREATED` → ensure + sync; `PM_ASSIGNED`, `COORDINATOR_ASSIGNED`, `EXPERT_ASSIGNED`,
  `EXPERT_ACCEPTED`, `EXPERT_DECLINED`, `EXPERT_TIMED_OUT`, and **a new `CASE_MANAGER_REASSIGNED`**
  (published by `reassignCaseManager`, which publishes nothing today) → sync; the case reaching
  `CLOSED` → read-only. A failure is logged and left to the sweep.
- **`ChatReconcileSweep`** — `CHAT_RECONCILE`, hourly, on `SweepRunner`. For every case not yet
  read-only: ensure the three conversations (**this backfills every existing case on its first
  run**), sync members, make `CLOSED` cases read-only. It is what catches changes no event
  announces: a revoked pipeline, a new or deactivated ENM, a client who creates their portal account
  after the case exists, a listener that failed.

## 4. REST API

The same operations on three surfaces, each a thin controller over the same services:
`/api/chat/…` (staff session), `/api/portal/client/chat/…` and `/api/portal/expert/chat/…` (portal
token). Staff routes admit every role — access is decided per conversation by `ChatAccess`, so a
role with no conversations simply gets an empty inbox.

| Route | Does |
|---|---|
| `GET conversations?caseId=&type=&status=` | the inbox: case context (reference, service, stage, status), type, access (`MEMBER`/`VIEWER`), unread count, last message, current participants |
| `GET conversations/{id}` | one conversation's metadata and participants |
| `GET conversations/{id}/messages?before=&after=&limit=` | keyset page of top-level messages with reply counts, newest first; `after` is the reconnect catch-up |
| `GET messages/{id}/replies` | a thread |
| `POST conversations/{id}/messages` `{ body, parentId? }` | send (members only) |
| `PUT messages/{id}` `{ body }` / `DELETE messages/{id}` | edit / delete your own |
| `PUT messages/{id}/reactions/{emoji}` / `DELETE …` | react / un-react |
| `POST conversations/{id}/read` `{ messageId }` | move your watermark forward (never back) |
| `GET search?q=&caseId=&type=` | full-text search over conversations the caller can open |
| `GET unread` | total unread, for the nav badge |
| `GET presence?ids=` | online state of a conversation's participants |

The portal chain's CORS methods gain **`PUT`** back, and `ClientApplicationRoutesTest`'s preflight
lists follow.

## 5. Real-time — Ably relays, EvalOS decides

**Decided 2026-09-26:** live delivery runs on Ably rather than a self-hosted WebSocket server.
Ably never holds the record: every message is committed in PostgreSQL before it is published, and
an app that missed something catches up over REST.

- **One private channel per person** — `chat:user:<STAFF|CLIENT|EXPERT>:<uuid>`. The backend
  publishes each committed change into the channel of **every current member**, recipients read per
  event, so somebody reassigned off a case receives nothing after they leave.
- **Tokens** come from `GET realtime/token` on each surface (an Ably TokenRequest, one hour,
  refreshed by ably-js through the same route). A token may **subscribe and enter presence on its
  own channel only; no token may publish.** A Brand Manager may also subscribe to
  `chat:view:<brandId>:*` and the GM to `chat:view:*` — the view channels the backend publishes each
  conversation's events to, for read-only oversight.
- **Writes go through REST**; typing too (`POST conversations/{id}/typing`, relayed by the backend,
  one per three seconds per person, never stored).
- **Presence:** each app enters presence on its own channel; "online" = present there, read by the
  backend when deciding on a push and for `GET presence?ids=`.
- **Envelope:** the Ably message name is the event type, the data is
  `{ type, conversationId, data }`, with `type` one of `message.created`, `message.edited`,
  `message.deleted`, `reactions.changed`, `read.moved`, `members.changed`,
  `conversation.read_only`, `access.granted`, `access.revoked`, `typing`, `unread.changed`.
- **Reconnect:** ably-js reconnects on its own; on reconnect the app catches up over REST
  (`messages?after=`) and refreshes the inbox.
- **Without `ABLY_API_KEY`**, chat still works over REST without live updates; the token route
  answers 503 `REALTIME_UNAVAILABLE`.
- **Cost at today's volume** (checked 2026-09-26): 100–150 cases a month at 5–7 days each is about
  35 open cases and 30–70 people connected at the busiest moment — inside Ably's free plan
  (200 connections, 200 channels, 6M messages a month); Standard is $29/month plus usage. Closed
  cases cost nothing: they are history in PostgreSQL.

## 6. Notifications and push — built with the backend (D37)

**Decided 2026-09-25:** real-time delivery and push are part of the first release, not later phases.

| Recipient state | What they get |
|---|---|
| Has the app open on the conversation | the message, live (§5) |
| Has the app open elsewhere | the message event plus an **in-app toast** and the unread badge, live |
| Has **no app open** (not present on their Ably channel) | a **web push** from the browser |

- **Everyone is covered:** staff (all three conversation types, Internal included), clients and
  experts. A team message reaches an offline client or expert as a push; their reply reaches the
  team instantly, and any team member who is offline gets a push.
- **The push says who and where, never what:** "New message from <name> — <case reference>"
  (the team is named "Your case team" to a client). A lock screen is not private. One notification
  per conversation — later messages replace it (the push `tag` is the conversation id) rather than
  stacking.
- **Opening it** goes to the conversation: staff → the case's Chat tab; client → `/cases/:caseId`;
  expert → `/case`.
- **Mechanics:** standard Web Push with VAPID (`EVALOS_PUSH_VAPID_PUBLIC`,
  `EVALOS_PUSH_VAPID_PRIVATE`, `EVALOS_PUSH_SUBJECT`); library `nl.martijndwars:web-push` 5.1.2;
  `push_subscriptions` in the same migration as the chat tables; a service worker in each app;
  `GET push/public-key`, `POST push/subscriptions`, `DELETE push/subscriptions` on each surface.
  Sent off the request thread. A 404/410 from the push service deletes the subscription.
  Unconfigured VAPID keys disable push with a startup warning; chat still works.
- **Permission** is asked from an in-inbox card, never automatically. On iOS, web push works only
  for a portal added to the home screen — a browser rule.
- Chat does not write to the notification bell; it has its own badge and toast.

## 7. Frontend — `packages/evalos-chat`

A local package consumed by `frontend/` and `client-expert/` through a `file:` dependency and a
Vite alias. React is a peer dependency; the one new runtime dependency is `ably` (ably-js).

- **`core/`** (no React) — `createChatClient({ apiBase, wsUrl, credentials })`: typed REST client,
  Ably connection (token via `authCallback` to `realtime/token`, presence on the person's own
  channel), event stream, REST catch-up on reconnect, and pure reducers that fold events into state.
- **`react/`** — `ChatProvider`; hooks `useInbox`, `useConversation`, `useMessages`, `useThread`,
  `useTyping`, `usePresence`, `useUnreadTotal`; components:
  - `ChatInbox` — grouped by case, type tabs, unread badges, search, filters (type, status);
  - `ConversationView` — case header (reference, service, stage, read-only banner), participants
    with role labels and online dots, message list, composer;
  - `MessageList` — scroll up for history, date separators, "edited" / "message deleted",
    "seen by" under the latest message, typing indicator;
  - `Composer` — Enter sends, Shift+Enter breaks a line, reply-to, 4,000-character limit, an
    **"Upload a document"** link that opens the app's own document flow for the case;
  - `ThreadPanel`, `Reactions` (six emoji);
  - `CaseChatPanel` — case id and allowed types in, a tabbed panel out;
  - loading, empty and error states with retry; viewer mode (no composer, no reactions, an
    "oversight — read only" banner); responsive — three panes on desktop, one at a time with Back
    on mobile.
- **Theming** — `--chat-*` CSS variables, mapped by each app to its own tokens.
- **Rendering** — message bodies as text; URLs become links; no HTML.

| App | Placement | Shows |
|---|---|---|
| Staff | **Conversations** in the sidebar (full-width `ChatInbox`) and a **Chat** tab on the case screen (`CaseChatPanel`) | members: their conversations; Sales: the Client and Internal conversations of their pipeline's cases and nothing else of the case; GM / Brand Manager: everything in scope, read-only |
| Client portal | a new **`/cases/:caseId`** page — case on the left, its Client conversation on the right (a **Messages** tab on phones) — plus **Messages** in the nav as the cross-case inbox. Dashboard case rows open the case page; `/draft` keeps its route | Client Communication only, grouped by the client's cases |
| Expert portal | the existing **`/case`** page gains the right-hand panel, plus **Messages** in the nav | Expert Communication only, for cases with an open or accepted offer |

## 8. Errors and security

| Situation | Answer |
|---|---|
| Not your conversation, or you have left it | 403, the same whether or not it exists |
| Writing to a read-only conversation | 409 `CONVERSATION_READ_ONLY`; the composer says why |
| Editing or deleting someone else's message | 403 |
| Empty body, or over 4,000 characters | 400 |
| More than 30 messages a minute from one person | 429 |
| Socket drops | "Reconnecting…" strip; sending still works over REST; catch-up on reconnect |
| Lifecycle listener fails | logged; the case change stands; the sweep repairs it |

- The browser has no route that creates a conversation or changes membership.
- Every REST call and every socket subscription passes through `ChatAccess`.

## 9. Tests

- `ChatMembershipTest` — each type × role; PM / Coordinator / Case Manager reassigned; Sales
  pipeline revoked; ENM deactivated; expert offered → declined / timed out / superseded; client with
  no account yet.
- `ChatAccessTest` — member / viewer / none across brands; client ⇏ `INTERNAL`/`EXPERT`; expert ⇏
  `CLIENT`; former member ⇏ anything; read-only refuses writes.
- Lifecycle — case created → three conversations; reassignment keeps history and moves membership;
  `CLOSED` → read-only; the sweep backfills and repairs; a failing listener leaves the case change
  committed.
- Real Postgres (`LocalPostgresIntegrationTest` harness) — keyset paging with equal timestamps,
  search, unread from watermarks, the member-row trigger.
- Controllers on all three surfaces; Ably: every token's capability names only its own channel and
  never `publish`; an event is published to every current member's channel and none of a removed
  one's; a manual check against a development Ably app.
- Package — reducers and reconnect catch-up; each app type-checks and builds.

## 10. Phases — each one shippable

1. **Backend, live** — migration (chat tables + `push_subscriptions`), membership, access,
   lifecycle and `CASE_MANAGER_REASSIGNED`, sweep with backfill, messages, REST on three surfaces,
   **Ably tokens and per-member publishing, typing and presence, web push sending**.
   Removes the Unit 56 Stream setup.
2. **`packages/evalos-chat` and the staff app** — inbox, Chat tab, toast, ably-js connection, service
   worker and push opt-in.
3. **Client and expert portals** — client case page and inbox, expert panel and inbox, service
   workers and push opt-in.

## 11. Decisions this edits (in the step that builds them)

- **D50** — rewritten from "Stream Chat" to this service.
- **D19c** — Sales still reads no case data, but **takes part in the Client and Internal
  conversations** of cases from their pipeline.
- **D37** — web push is built in phase 1 (sending) and phases 2–3 (subscribing in each app).
- **Open item h** — settled: the client and the expert never share a conversation.
- **Q13** — closed by §0.
- `data-model.md`, `workflows.md`, `implementation-status.md` and the Serena memories follow each
  phase.

## 12. Not in this unit

Attachments of any kind; link previews; chat before a case exists (a request has no case team);
email or SMS notifications (invariant 14); moderation beyond own-message edit/delete; a message
retention sweep (messages are case correspondence, kept with the case under the Document Retention
Policy); an expert-portal case list.
