# Unit 57 — Case chat: the EvalOS Chat Service

**Decided 2026-09-25 by the business, in a brainstorming session.** Every case has three live
conversations, run by EvalOS itself — Spring Boot, WebSocket/STOMP and PostgreSQL. **No external
chat platform.** Supersedes Unit 56 (`56-live-chat-setup.md`, the Stream token setup), whose code is
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
| 6 | Platform? | **In-house**: WebSocket/STOMP + PostgreSQL. Stream is dropped |
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
- **`push_subscriptions`** (phase 5) — `id`, `brand_id`, `subscriber_kind`, `subscriber_id`,
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

## 5. Real-time — WebSocket/STOMP

- **Endpoint `/ws`**, STOMP over native WebSocket, allowed origins = the staff app plus
  `EVALOS_PORTAL_ORIGINS`. The nginx configs in front of the staff app and portals proxy `/ws`
  with the `Upgrade`/`Connection` headers. Heartbeats 10s/10s.
- **`CONNECT`** carries `Authorization: Bearer <staff JWT>` or `X-Portal-Token`; a channel
  interceptor validates it with `JwtService` / the portal-access service and attaches the
  `ChatIdentity`. Invalid → `ERROR` and close. A subscription attempted after the credential
  expires is refused.
- **Writes go through REST; STOMP only fans out**, after commit. The one client-to-server frame is
  typing.
- **`/user/queue/chat`** — per identity, reaching every open session: message created / edited /
  deleted, reaction changed, read watermark moved, typing, participants changed, read-only,
  unread count changed, access granted or revoked. **Recipients are computed per event from current
  membership**, so a removed member stops receiving at once.
- **`/topic/view.conversations.{id}`** — viewers only, checked by `ChatAccess` at `SUBSCRIBE`.
- **`SEND /app/conversations.{id}.typing`** — members only, throttled to one per second per person,
  relayed to the other members, never stored.
- **Presence** — an in-memory count of open sessions per identity; online = at least one. Changes go
  to the members of that identity's conversations.
- **Reconnect** — the client backs off and reconnects, then catches up over REST (`after=`) for
  open conversations and refreshes the inbox. The database is the record, so nothing is lost.
- ponytail: the in-memory broker and presence registry hold for **one backend instance**, which is
  the deployment today. Two instances need Spring's STOMP broker relay (RabbitMQ) and a shared
  presence store.

## 6. Push — phase 5 (D37)

On a new message, every member except the author with **no open session** gets a web push
("New message — <case reference>, <conversation type>"; never the message text, which may be
confidential on a lock screen). VAPID keys from the environment (`EVALOS_PUSH_VAPID_PUBLIC`,
`EVALOS_PUSH_VAPID_PRIVATE`, `EVALOS_PUSH_SUBJECT`); a service worker in each app; subscribe and
unsubscribe routes on each surface. The permission prompt is an in-inbox card, never automatic.
Chat does not write to the notification bell; it has its own unread badge. The server-side web-push
library is chosen during planning. On iOS, web push works only for a portal added to the home
screen — a browser rule.

## 7. Frontend — `packages/evalos-chat`

A local package consumed by `frontend/` and `client-expert/` through a `file:` dependency and a
Vite alias. React is a peer dependency; the one new runtime dependency is `@stomp/stompjs`.

- **`core/`** (no React) — `createChatClient({ apiBase, wsUrl, credentials })`: typed REST client,
  STOMP connection, event stream, reconnect catch-up, and pure reducers that fold events into state.
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
- Controllers on all three surfaces; STOMP: bad token refused, foreign subscription refused, an
  event reaches every session of a member and none of a removed one.
- Package — reducers and reconnect catch-up; each app type-checks and builds.

## 10. Phases — each one shippable

1. **Backend core** — migration, `ChatMembership`, `ChatAccess`, `ConversationService`, lifecycle
   listener and `CASE_MANAGER_REASSIGNED`, sweep with backfill, `MessageService`, REST on three
   surfaces. Remove the Unit 56 Stream setup (`StreamChat`, `ChatTokenService`, `ChatController`,
   `ChatUnavailableException`, their tests, the `evalos.stream` config and compose variables).
2. **Real-time** — STOMP endpoint, auth interceptor, fan-out, typing, presence, nginx `/ws`.
3. **`packages/evalos-chat`** and the staff app.
4. **Client portal** case page and inbox; **expert portal** panel and inbox.
5. **Web push** (D37).

## 11. Decisions this edits (in the step that builds them)

- **D50** — rewritten from "Stream Chat" to this service.
- **D19c** — Sales still reads no case data, but **takes part in the Client and Internal
  conversations** of cases from their pipeline.
- **D37** — push is built in phase 5.
- **Open item h** — settled: the client and the expert never share a conversation.
- **Q13** — closed by §0.
- `data-model.md`, `workflows.md`, `implementation-status.md` and the Serena memories follow each
  phase.

## 12. Not in this unit

Attachments of any kind; link previews; chat before a case exists (a request has no case team);
email or SMS notifications (invariant 14); moderation beyond own-message edit/delete; a message
retention sweep (messages are case correspondence, kept with the case under the Document Retention
Policy); an expert-portal case list; more than one backend instance.
