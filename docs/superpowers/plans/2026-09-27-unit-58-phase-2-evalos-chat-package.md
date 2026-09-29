# Unit 58 Phase 2 — `packages/evalos-chat`, the Portal Subset — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A reusable chat package — typed REST client, Ably connection with REST catch-up, pure state reducer, React provider/hooks/components — holding the pieces the client portal needs: inbox, conversation panel, composer, replies, reactions, unread badge.

**Architecture:** `packages/evalos-chat/src` is a **source-only** package: no `node_modules` of its own. The client portal imports it through a Vite/TS alias (`@evalos/chat`), and `resolve.dedupe` makes the portal's own `react` and `ably` the only copies. `core/` has no React and holds all logic, so everything that can break is unit-tested with fakes. `react/` is a thin layer over a `useSyncExternalStore` store. One small backend change makes live events usable by a portal: `GET …/chat/me`, and reactions that carry reactor identity, not just a display name.

**Tech Stack:** TypeScript 6, React 19, ably-js 2.29, Vitest 4 (run from `client-expert/`); Spring Boot backend for the one route and record change.

**Spec:** `context/specs/58-client-portal.md` §4 (the chat subset), `context/specs/57-case-chat.md` §4–§5, §7 (the API, the real-time contract, the package).

## Global Constraints

- The portal subset only: **inbox, conversation panel, composer, replies, reactions, unread badge, the Ably connection and REST catch-up** (spec 58 §4). Not in this phase: search, typing, presence dots, "seen by", edit/delete UI, push, the staff app, the portal pages themselves (phase 3).
- One new runtime dependency: **`ably` (ably-js)**, `^2.29.0`. React is a peer (spec 57 §7).
- Envelope: Ably message data is a JSON object `{ type, conversationId, data }`; types `message.created`, `message.edited`, `message.deleted`, `reactions.changed`, `read.moved`, `members.changed`, `conversation.read_only`, `access.granted`, `access.revoked`, `typing`, `unread.changed` (spec 57 §5).
- Personal channel `chat:user:<KIND>:<uuid>`; a token may subscribe and enter presence on it and **never publish**; writes go through REST (spec 57 §5).
- Without `ABLY_API_KEY` the token route answers **503 `REALTIME_UNAVAILABLE`** and chat must still work over REST (spec 57 §5).
- On reconnect: catch up over REST with `messages?after=<cursor>` and refresh the inbox. A cursor is `"<createdAt>|<id>"` (`MessageService.Cursor`).
- Composer: Enter sends, Shift+Enter breaks a line, reply-to, **4,000-character** limit, an **"Upload a document"** link that calls the app's own document flow (spec 57 §7).
- Reactions: the six — `THUMBS_UP HEART LAUGH CELEBRATE SURPRISED THANKS` (`Reaction.java`).
- Rendering: bodies as text; `http(s)` URLs become links; **no HTML** (spec 57 §7).
- Theming: `--chat-*` CSS variables, mapped by each app (spec 57 §7).
- Viewer mode (`access: 'VIEWER'`) and read-only conversations (`status: 'READ_ONLY'`): no composer, no reactions, a banner (spec 57 §7).

## Review Focus

1. **The REST reply to a send and the live `message.created` for the same message arrive in either order** — the message must appear once, with no double unread count. Pinned in Task 3 (`aMessageArrivingTwiceIsShownOnce`).
2. **Laptop sleeps, messages arrive, it wakes** — reconnect must catch up every open conversation page by page, including one whose list was still empty (it loads the first page instead of asking for `after=undefined`). Pinned in Task 4 (`aReconnectCatchesUpEveryLoadedConversation`).
3. **Ably not configured (503 from the token route)** — chat works over REST, the connector never constructs Ably (so no retry storm), status reads `offline`. Pinned in Task 4 (`noRealtimeKeyMeansRestOnly`).
4. **A message body with `<script>`, HTML or a `javascript:` URL** — shown as text; only `http(s)` becomes a link, and trailing punctuation stays out of the link. Pinned in Task 5 (`onlyHttpLinksAreLinksAndNothingIsHtml`).
5. **A live event built for its author** (`mine: true` on everyone's `message.created`) — the portal must compute "mine" from its own identity, never trust the payload's flag, and must know whether *it* reacted. Pinned in Task 1 (`reactionsCarryWhoReacted`) and Task 3 (`mineIsComputedFromMyIdentityNotThePayload`).

---

## File Structure

| File | Responsibility |
|---|---|
| `backend/…/chat/ChatViews.java` (modify) | `Reactor`, `Me` records; `MessageView.reactions` → `Map<Reaction, List<Reactor>>` |
| `backend/…/chat/MessageService.java` (modify) | Build reactors with kind + id + name |
| `backend/…/web/ChatRoutes.java` (modify) | `GET /me` on all three surfaces |
| `packages/evalos-chat/package.json` | Name and peer contract (`react`, `ably`) |
| `packages/evalos-chat/src/core/types.ts` | Wire types mirroring `ChatViews` |
| `packages/evalos-chat/src/core/api.ts` | Typed REST client over an injected request function; `cursorOf` |
| `packages/evalos-chat/src/core/reducer.ts` | Pure state + `reduce(state, action)` |
| `packages/evalos-chat/src/core/realtime.ts` | Ably connector (constructor injected), personal channel, presence, reconnect signal |
| `packages/evalos-chat/src/core/client.ts` | The store: start, inbox, open, older, catch-up, send, react, read, unread |
| `packages/evalos-chat/src/core/text.ts` | `linkify`, `rowsWithDays` |
| `packages/evalos-chat/src/react/*.tsx` | Provider, hooks, `ChatInbox`, `ConversationView`, `MessageList`, `Composer`, `Reactions`, `ThreadPanel`, `CaseChatPanel`, `UnreadBadge` |
| `packages/evalos-chat/src/chat.css` | Component styles on `--chat-*` variables |
| `packages/evalos-chat/src/index.ts` | Public exports |
| `client-expert/{package.json, vitest.config.ts, client/vite.config.ts, client/tsconfig.app.json}` (modify) | `ably` dependency, alias, dedupe, TS paths, test discovery |

`backend/…` = `backend/src/main/java/com/ie/evalos`. Package tests live beside their files (`*.test.ts`) and run from `client-expert/` with `npm test`.

---

### Task 1: Backend — the caller's identity and who reacted

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/chat/ChatViews.java`, `chat/MessageService.java`, `web/ChatRoutes.java`
- Test: `backend/src/test/java/com/ie/evalos/chat/MessageServiceTest.java`, `backend/src/test/java/com/ie/evalos/web/StaffChatControllerTest.java`; fix constructions in `chat/live/ChatFanoutTest.java`, `chat/push/ChatPushNotifierTest.java`

**Interfaces:**
- Produces:
  - `record ChatViews.Reactor(ParticipantKind kind, UUID id, String name)`
  - `record ChatViews.Me(ParticipantKind kind, UUID id)`
  - `MessageView.reactions` is `Map<Reaction, List<Reactor>>` (JSON `{"HEART":[{"kind":"CLIENT","id":"…","name":"Anita Rao"}]}`)
  - `GET /api/chat/me`, `/api/portal/client/chat/me`, `/api/portal/expert/chat/me` → `{ kind, id }`

- [ ] **Step 1: Write the failing tests**

In `MessageServiceTest`, after `reactingTwiceIsIdempotentAndUnreactingRemovesTheRow` (it reuses the class's `existing(...)` helper; `react` returns the caller's view, built by the same `views(...)` every read uses):

```java
	/** Unit 58 phase 2: a portal must know whether IT reacted, which a display name cannot say. */
	@Test
	void reactionsCarryWhoReacted() {
		Message m = existing(pm, "hi", null);
		when(reactions.findByMessageIdIn(any())).thenReturn(
				List.of(new MessageReaction(brand, m.getId(), ParticipantKind.CLIENT, client.id(), Reaction.THANKS)));

		ChatViews.MessageView view = service.react(client, m.getId(), Reaction.THANKS, true);

		assertThat(view.reactions().get(Reaction.THANKS)).singleElement().satisfies((reactor) -> {
			assertThat(reactor.kind()).isEqualTo(ParticipantKind.CLIENT);
			assertThat(reactor.id()).isEqualTo(client.id());
		});
	}
```

In `StaffChatControllerTest`:

```java
	/** Unit 58 phase 2: the portal computes "mine" from this, since live payloads are built for their author. */
	@Test
	void meNamesTheCallerInChatTerms() throws Exception {
		mockMvc.perform(get("/api/chat/me").header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.kind").value("STAFF"))
				.andExpect(jsonPath("$.data.id").isNotEmpty());
	}
```

Read `web/StaffChatController.who()` first: if it calls `api.staff()` (a mocked `ChatApi` returns null), stub `given(api.staff()).willReturn(new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), UUID.randomUUID(), Role.PROJECT_MANAGER))` in this test; if it builds the identity from `TenantContext`, no stub is needed.

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./mvnw -q test -Dtest='MessageServiceTest,StaffChatControllerTest'`
Expected: compile error (`reactor.kind()` on a `String`) and 404 on `/api/chat/me`.

- [ ] **Step 3: Implement**

`ChatViews`:

```java
	/** One person who reacted (Unit 58 phase 2): identity, so a client can tell its own reaction apart. */
	public record Reactor(ParticipantKind kind, UUID id, String name) {
	}

	/** The caller in chat terms, for a client computing "mine" on live events built for their author. */
	public record Me(ParticipantKind kind, UUID id) {
	}
```

and change `MessageView`'s component to `Map<Reaction, List<Reactor>> reactions`.

`MessageService.views(...)`: the map becomes `Map<UUID, Map<Reaction, List<ChatViews.Reactor>>>` and the add becomes

```java
					.add(new ChatViews.Reactor(r.getReactorKind(), r.getReactorId(),
							names.getOrDefault(r.getReactorKind() + ":" + r.getReactorId(), "")));
```

(`conversationViews` passes `Map.of()` for the last message and needs no change.)

`ChatRoutes`, beside `unread()`:

```java
	/**
	 * Who the caller is in chat terms (Unit 58 phase 2). Live payloads are built once, for their
	 * author, so a client computes "mine" from this rather than from the payload's flag.
	 */
	@GetMapping("/me")
	public ApiResponse<ChatViews.Me> me() {
		ChatIdentity caller = who();
		return ApiResponse.ok(new ChatViews.Me(caller.kind(), caller.id()));
	}
```

Fix the two test constructions of `MessageView` (`ChatFanoutTest:39`, `ChatPushNotifierTest:65`): their `Map.of()` for reactions still compiles; if either passes a `Map<Reaction, List<String>>`, change it to `Map.of()`.

- [ ] **Step 4: Run and commit**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS.

```bash
git add backend/src
git commit -m "feat(chat): GET /me and reactions that say who reacted, for portal clients"
```

---

### Task 2: The package, its wire types and REST client, wired into the client portal

**Files:**
- Create: `packages/evalos-chat/package.json`, `packages/evalos-chat/src/core/types.ts`, `packages/evalos-chat/src/core/api.ts`, `packages/evalos-chat/src/index.ts`
- Test: `packages/evalos-chat/src/core/api.test.ts`
- Modify: `client-expert/package.json` (+ lock), `client-expert/vitest.config.ts`, `client-expert/client/vite.config.ts`, `client-expert/client/tsconfig.app.json`

**Interfaces:**
- Consumes: Task 1's routes.
- Produces:
  - types `ParticipantKind`, `Reaction`, `REACTIONS`, `Me`, `Reactor`, `Participant`, `Message`, `Conversation`, `ConversationType`, `Page<T>`, `Envelope`, `TokenRequest`, `MAX_BODY = 4000`
  - `type Request = <T>(method: Method, path: string, options?: { body?: unknown; params?: Params }) => Promise<T>`
  - `createChatApi(request: Request)` → `{ me, inbox, messages, replies, send, react, read, unread, realtimeToken }`; `type ChatApi`
  - `cursorOf(m: Message): string`

- [ ] **Step 1: Wire the package into `client-expert` first (so the test can be discovered)**

`packages/evalos-chat/package.json`:

```json
{
  "name": "@evalos/chat",
  "private": true,
  "type": "module",
  "description": "EvalOS case chat UI (Unit 57 §7). Source-only: consumers import src/ through an alias and supply react and ably.",
  "peerDependencies": {
    "ably": "^2.29.0",
    "react": "^19.2.0"
  }
}
```

Install the dependency in the portal workspace: `cd client-expert && npm install ably@^2.29.0`. Before installing, check it on OSV: `curl -s -X POST https://api.osv.dev/v1/query -d '{"package":{"name":"ably","ecosystem":"npm"},"version":"2.29.0"}'` — expected `{}`; if it lists advisories, pick the newest clean 2.x and note it.

`client-expert/vitest.config.ts` — add the alias and the package's tests (absolute glob, forward slashes, because the package sits outside this root):

```ts
import path from 'node:path'
import { defineConfig } from 'vitest/config'

const chat = path.resolve(import.meta.dirname, '../packages/evalos-chat/src')

// Tests run once for the whole repo rather than per app: what is tested today
// lives in shared/, which both portals import — and, since Unit 58, the chat package.
export default defineConfig({
  resolve: {
    alias: {
      '@shared': path.resolve(import.meta.dirname, './shared/src'),
      '@evalos/chat': chat,
    },
  },
  test: {
    include: ['{shared,client,expert}/src/**/*.test.{ts,tsx}', `${chat.replaceAll('\\', '/')}/**/*.test.ts`],
  },
})
```

`client-expert/client/vite.config.ts` — in `resolve`:

```ts
    alias: {
      "@": path.resolve(import.meta.dirname, "./src"),
      "@shared": path.resolve(import.meta.dirname, "../shared/src"),
      "@evalos/chat": path.resolve(import.meta.dirname, "../../packages/evalos-chat/src"),
    },
    // The chat package lives outside this app and has no node_modules: its imports of react and
    // ably must resolve to THIS app's copies, or React would run twice (Unit 58 phase 2).
    dedupe: ["react", "react-dom", "ably"],
```

`client-expert/client/tsconfig.app.json` — `paths` and `include`:

```json
    "paths": {
      "@/*": ["src/*"],
      "@shared/*": ["../shared/src/*"],
      "@evalos/chat": ["../../packages/evalos-chat/src/index.ts"],
      "react": ["../node_modules/@types/react"],
      "react/*": ["../node_modules/@types/react/*"],
      "ably": ["../node_modules/ably"]
    }
  },
  "include": ["src", "../shared/src", "../../packages/evalos-chat/src"],
  "exclude": ["../../packages/evalos-chat/src/**/*.test.ts"]
```

(The `react`/`ably` paths are what let `tsc` type the package's imports from this app's `node_modules`, the compile-time twin of `dedupe`.)

- [ ] **Step 2: Write the failing test**

`packages/evalos-chat/src/core/api.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { createChatApi, cursorOf, type Request } from './api'
import type { Message } from './types'

function recorder() {
  const calls: { method: string; path: string; options?: unknown }[] = []
  const request: Request = async <T,>(method: string, path: string, options?: unknown) => {
    calls.push({ method, path, options })
    return undefined as T
  }
  return { calls, api: createChatApi(request) }
}

describe('createChatApi', () => {
  it('speaks the Unit 57 routes', async () => {
    const { calls, api } = recorder()
    await api.me()
    await api.inbox({ caseId: 'c1' })
    await api.messages('v1', { after: '2026-09-27T10:00:00Z|m1' })
    await api.replies('m1')
    await api.send('v1', 'hello', 'm1')
    await api.react('m1', 'HEART', true)
    await api.react('m1', 'HEART', false)
    await api.read('v1', 'm2')
    await api.unread()
    await api.realtimeToken()

    expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual([
      'GET /me',
      'GET /conversations',
      'GET /conversations/v1/messages',
      'GET /messages/m1/replies',
      'POST /conversations/v1/messages',
      'PUT /messages/m1/reactions/HEART',
      'DELETE /messages/m1/reactions/HEART',
      'POST /conversations/v1/read',
      'GET /unread',
      'GET /realtime/token',
    ])
    expect(calls[1].options).toEqual({ params: { caseId: 'c1', limit: 100 } })
    expect(calls[2].options).toEqual({ params: { after: '2026-09-27T10:00:00Z|m1', limit: 50 } })
    expect(calls[4].options).toEqual({ body: { body: 'hello', parentId: 'm1' } })
    expect(calls[7].options).toEqual({ body: { messageId: 'm2' } })
  })

  it('builds the server cursor from a message', () => {
    expect(cursorOf({ id: 'm1', createdAt: '2026-09-27T10:00:00.123Z' } as Message)).toBe('2026-09-27T10:00:00.123Z|m1')
  })
})
```

- [ ] **Step 3: Run to verify failure**

Run: `cd client-expert && npx vitest run ../packages/evalos-chat/src/core/api.test.ts`
Expected: FAIL — cannot resolve `./api`. (If instead "No test files found", the include glob is wrong: fix it before going on, and ledger what worked.)

- [ ] **Step 4: Implement**

`packages/evalos-chat/src/core/types.ts`:

```ts
/** Wire types, mirroring `com.ie.evalos.chat.ChatViews` (Unit 57 §4). */

export type ParticipantKind = 'STAFF' | 'CLIENT' | 'EXPERT'
export type ConversationType = 'CLIENT' | 'INTERNAL' | 'EXPERT'
export type Reaction = 'THUMBS_UP' | 'HEART' | 'LAUGH' | 'CELEBRATE' | 'SURPRISED' | 'THANKS'

/** The six, in display order. */
export const REACTIONS: Record<Reaction, string> = {
  THUMBS_UP: '👍',
  HEART: '❤️',
  LAUGH: '😂',
  CELEBRATE: '🎉',
  SURPRISED: '😮',
  THANKS: '🙏',
}

/** `MessageService.MAX_BODY`. */
export const MAX_BODY = 4000

export type Me = { kind: ParticipantKind; id: string }
export type Reactor = { kind: ParticipantKind; id: string; name: string }
export type Participant = { kind: ParticipantKind; id: string; role: string; name: string }

export type Message = {
  id: string
  conversationId: string
  authorKind: ParticipantKind
  authorId: string
  authorName: string | null
  body: string
  parentId: string | null
  replyCount: number
  createdAt: string
  editedAt: string | null
  deleted: boolean
  reactions: Partial<Record<Reaction, Reactor[]>>
  /** Trusted only on a REST reply; a live event carries its author's value. See `reducer.own`. */
  mine: boolean
}

export type Conversation = {
  id: string
  caseId: string
  caseCode: string
  serviceType: string | null
  stage: string
  type: ConversationType
  status: 'ACTIVE' | 'READ_ONLY'
  access: 'MEMBER' | 'VIEWER'
  unread: number
  lastMessage: Message | null
  participants: Participant[]
  lastMessageAt: string | null
}

export type Page<T> = { items: T[]; nextCursor: string | null }

/** The Ably message data (Unit 57 §5). */
export type Envelope = { type: string; conversationId: string; data: unknown }

/** An Ably TokenRequest, as `GET realtime/token` answers (`AblyToken`). */
export type TokenRequest = {
  keyName: string
  clientId: string
  capability: string
  ttl: number
  timestamp: number
  nonce: string
  mac: string
}
```

`packages/evalos-chat/src/core/api.ts`:

```ts
import type { Conversation, ConversationType, Me, Message, Page, Reaction, TokenRequest } from './types'

export type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'
export type Params = Record<string, string | number | undefined>

/**
 * The app's own HTTP call, already pointed at its chat surface (`/api/chat`,
 * `/api/portal/client/chat`, `/api/portal/expert/chat`) and unwrapping EvalOS's `{ success, data }`.
 * Injected so the package never knows about tokens or axios.
 */
export type Request = <T>(method: Method, path: string, options?: { body?: unknown; params?: Params }) => Promise<T>

export type InboxParams = { caseId?: string; type?: ConversationType }

export function createChatApi(request: Request) {
  return {
    me: () => request<Me>('GET', '/me'),
    inbox: (params: InboxParams = {}) =>
      request<Page<Conversation>>('GET', '/conversations', { params: { ...params, limit: 100 } }),
    messages: (conversationId: string, cursor: { before?: string; after?: string } = {}) =>
      request<Page<Message>>('GET', `/conversations/${conversationId}/messages`, { params: { ...cursor, limit: 50 } }),
    replies: (messageId: string) => request<Message[]>('GET', `/messages/${messageId}/replies`),
    send: (conversationId: string, body: string, parentId?: string) =>
      request<Message>('POST', `/conversations/${conversationId}/messages`, { body: { body, parentId: parentId ?? null } }),
    react: (messageId: string, reaction: Reaction, on: boolean) =>
      request<Message>(on ? 'PUT' : 'DELETE', `/messages/${messageId}/reactions/${reaction}`),
    read: (conversationId: string, messageId: string) =>
      request<void>('POST', `/conversations/${conversationId}/read`, { body: { messageId } }),
    unread: () => request<number>('GET', '/unread'),
    realtimeToken: () => request<TokenRequest>('GET', '/realtime/token'),
  }
}

export type ChatApi = ReturnType<typeof createChatApi>

/** The server's keyset cursor, `MessageService.Cursor`: `"<createdAt>|<id>"`. */
export function cursorOf(message: Message): string {
  return `${message.createdAt}|${message.id}`
}
```

Fix the recorder's param list if TS complains that `parentId: null` vs `'m1'` in the test — the test passes `'m1'`, so the expectation holds.

`packages/evalos-chat/src/index.ts` (grows in later tasks):

```ts
export * from './core/types'
export * from './core/api'
```

- [ ] **Step 5: Run the test and the portal build**

Run: `cd client-expert && npx vitest run ../packages/evalos-chat/src/core/api.test.ts`
Expected: PASS (2).

Run: `cd client-expert && npm run build:client`
Expected: `tsc -b` and `vite build` succeed (the package is now type-checked by the client build).

- [ ] **Step 6: Commit**

```bash
git add packages/evalos-chat client-expert/package.json client-expert/package-lock.json client-expert/vitest.config.ts client-expert/client/vite.config.ts client-expert/client/tsconfig.app.json
git commit -m "feat(chat-ui): the evalos-chat package, its wire types and REST client, wired into the client portal"
```

---

### Task 3: The reducer

**Files:**
- Create: `packages/evalos-chat/src/core/reducer.ts`
- Test: `packages/evalos-chat/src/core/reducer.test.ts`
- Modify: `packages/evalos-chat/src/index.ts`

**Interfaces:**
- Consumes: `types.ts` (Task 2).
- Produces:
  - `type ChatState = { me: Me | null; inboxLoaded: boolean; error: string | null; conversations: Record<string, Conversation>; order: string[]; messages: Record<string, Message[]>; olderCursor: Record<string, string | null>; replies: Record<string, Message[]>; unreadTotal: number; realtime: RealtimeStatus }`
  - `type RealtimeStatus = 'connecting' | 'live' | 'offline'`
  - `const initialState: ChatState`
  - `type Action` (below), `reduce(state: ChatState, action: Action): ChatState`
  - `reactedByMe(message: Message, reaction: Reaction, me: Me | null): boolean`

- [ ] **Step 1: Write the failing tests**

`packages/evalos-chat/src/core/reducer.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { initialState, reactedByMe, reduce, type ChatState } from './reducer'
import type { Conversation, Message } from './types'

const me = { kind: 'CLIENT' as const, id: 'client-1' }

function msg(id: string, over: Partial<Message> = {}): Message {
  return {
    id,
    conversationId: 'v1',
    authorKind: 'STAFF',
    authorId: 'staff-1',
    authorName: 'Cam',
    body: `body ${id}`,
    parentId: null,
    replyCount: 0,
    createdAt: `2026-09-27T10:00:0${id.slice(-1)}Z`,
    editedAt: null,
    deleted: false,
    reactions: {},
    mine: false,
    ...over,
  }
}

function conv(over: Partial<Conversation> = {}): Conversation {
  return {
    id: 'v1',
    caseId: 'c1',
    caseCode: 'IE-1042',
    serviceType: 'EXPERT_OPINION_LETTER',
    stage: 'CLIENT_REVIEW',
    type: 'CLIENT',
    status: 'ACTIVE',
    access: 'MEMBER',
    unread: 0,
    lastMessage: null,
    participants: [],
    lastMessageAt: null,
    ...over,
  }
}

/** Me known, one conversation in the inbox, its first page (server order: newest first) loaded. */
function opened(): ChatState {
  let s = reduce(initialState, { type: 'me', me })
  s = reduce(s, { type: 'inbox', conversations: [conv()] })
  return reduce(s, { type: 'page', conversationId: 'v1', items: [msg('m2'), msg('m1')], nextCursor: 'cur-1' })
}

const created = (m: Message) => ({ type: 'event' as const, envelope: { type: 'message.created', conversationId: 'v1', data: m } })

describe('reduce', () => {
  it('shows a page oldest first and remembers where older history starts', () => {
    const s = opened()
    expect(s.messages.v1.map((m) => m.id)).toEqual(['m1', 'm2'])
    expect(s.olderCursor.v1).toBe('cur-1')
    expect(s.inboxLoaded).toBe(true)
  })

  it('puts an older page in front without repeating anything', () => {
    const s = reduce(opened(), { type: 'page', conversationId: 'v1', items: [msg('m1'), msg('m0')], nextCursor: null })
    expect(s.messages.v1.map((m) => m.id)).toEqual(['m0', 'm1', 'm2'])
    expect(s.olderCursor.v1).toBeNull()
  })

  /** Review Focus 1: the REST reply and the live event for one send, in either order. */
  it('aMessageArrivingTwiceIsShownOnce', () => {
    const mine = msg('m3', { authorKind: 'CLIENT', authorId: 'client-1' })
    const eventFirst = reduce(reduce(opened(), created(mine)), { type: 'upsert', message: mine })
    const restFirst = reduce(reduce(opened(), { type: 'upsert', message: mine }), created(mine))
    for (const s of [eventFirst, restFirst]) {
      expect(s.messages.v1.map((m) => m.id)).toEqual(['m1', 'm2', 'm3'])
      expect(s.conversations.v1.unread).toBe(0)
    }
  })

  it('counts a message from someone else as unread and floats its conversation to the top', () => {
    let s = reduce(opened(), { type: 'inbox', conversations: [conv({ id: 'v0', caseId: 'c0' }), conv()] })
    s = reduce(s, created(msg('m3')))
    expect(s.conversations.v1.unread).toBe(1)
    expect(s.conversations.v1.lastMessage?.id).toBe('m3')
    expect(s.order[0]).toBe('v1')
  })

  /** Review Focus 5: live payloads are built for their author. */
  it('mineIsComputedFromMyIdentityNotThePayload', () => {
    const fromStaffClaimingMine = msg('m3', { mine: true })
    const s = reduce(opened(), created(fromStaffClaimingMine))
    expect(s.messages.v1.at(-1)?.mine).toBe(false)
    expect(s.conversations.v1.unread).toBe(1)
  })

  it('does not invent history for a conversation that was never opened', () => {
    let s = reduce(initialState, { type: 'me', me })
    s = reduce(s, { type: 'inbox', conversations: [conv()] })
    s = reduce(s, created(msg('m3')))
    expect(s.messages.v1).toBeUndefined()
    expect(s.conversations.v1.unread).toBe(1)
  })

  it('files a reply under its parent and counts it once', () => {
    const reply = msg('m4', { parentId: 'm1' })
    let s = reduce(opened(), { type: 'replies', parentId: 'm1', items: [] })
    s = reduce(reduce(s, created(reply)), created(reply))
    expect(s.replies.m1.map((m) => m.id)).toEqual(['m4'])
    expect(s.messages.v1[0].replyCount).toBe(1)
    expect(s.messages.v1.map((m) => m.id)).toEqual(['m1', 'm2'])
  })

  it('replaces an edited, deleted or re-reacted message in place and ignores one it never loaded', () => {
    const edited = msg('m1', { body: 'fixed', editedAt: '2026-09-27T11:00:00Z' })
    let s = reduce(opened(), { type: 'event', envelope: { type: 'message.edited', conversationId: 'v1', data: edited } })
    expect(s.messages.v1[0].body).toBe('fixed')
    s = reduce(s, { type: 'event', envelope: { type: 'reactions.changed', conversationId: 'v1', data: msg('m9') } })
    expect(s.messages.v1.map((m) => m.id)).toEqual(['m1', 'm2'])
  })

  it('clears unread when I read, and not when someone else does', () => {
    let s = reduce(opened(), created(msg('m3')))
    s = reduce(s, { type: 'event', envelope: { type: 'read.moved', conversationId: 'v1', data: { kind: 'STAFF', id: 'staff-1', name: 'Cam', lastReadMessageId: 'm3' } } })
    expect(s.conversations.v1.unread).toBe(1)
    s = reduce(s, { type: 'event', envelope: { type: 'read.moved', conversationId: 'v1', data: { kind: 'CLIENT', id: 'client-1', name: 'Anita', lastReadMessageId: 'm3' } } })
    expect(s.conversations.v1.unread).toBe(0)
  })

  it('freezes a conversation that became read-only', () => {
    const s = reduce(opened(), { type: 'event', envelope: { type: 'conversation.read_only', conversationId: 'v1', data: null } })
    expect(s.conversations.v1.status).toBe('READ_ONLY')
  })

  it('knows whether I reacted', () => {
    const m = msg('m1', { reactions: { HEART: [{ kind: 'CLIENT', id: 'client-1', name: 'Anita' }], THANKS: [{ kind: 'STAFF', id: 'staff-1', name: 'Cam' }] } })
    expect(reactedByMe(m, 'HEART', me)).toBe(true)
    expect(reactedByMe(m, 'THANKS', me)).toBe(false)
    expect(reactedByMe(m, 'LAUGH', null)).toBe(false)
  })
})
```

- [ ] **Step 2: Run to verify failure**

Run: `cd client-expert && npx vitest run ../packages/evalos-chat/src/core/reducer.test.ts`
Expected: FAIL — cannot resolve `./reducer`.

- [ ] **Step 3: Implement**

`packages/evalos-chat/src/core/reducer.ts`:

```ts
import type { Conversation, Envelope, Me, Message, Reaction } from './types'

export type RealtimeStatus = 'connecting' | 'live' | 'offline'

export type ChatState = {
  me: Me | null
  inboxLoaded: boolean
  error: string | null
  conversations: Record<string, Conversation>
  /** Inbox order, most recent activity first. */
  order: string[]
  /** Top-level messages per conversation, oldest first. Absent = never opened. */
  messages: Record<string, Message[]>
  /** Where older history starts; null = there is none. */
  olderCursor: Record<string, string | null>
  /** Replies per parent message, oldest first. */
  replies: Record<string, Message[]>
  unreadTotal: number
  realtime: RealtimeStatus
}

export const initialState: ChatState = {
  me: null,
  inboxLoaded: false,
  error: null,
  conversations: {},
  order: [],
  messages: {},
  olderCursor: {},
  replies: {},
  unreadTotal: 0,
  realtime: 'connecting',
}

export type Action =
  | { type: 'me'; me: Me }
  | { type: 'inbox'; conversations: Conversation[] }
  /** A page from the server, newest first — the first page or older history. */
  | { type: 'page'; conversationId: string; items: Message[]; nextCursor: string | null }
  /** Catch-up after a reconnect, oldest first. */
  | { type: 'newer'; conversationId: string; items: Message[] }
  | { type: 'replies'; parentId: string; items: Message[] }
  /** A REST reply (send, react): the caller's own view of one message. */
  | { type: 'upsert'; message: Message }
  | { type: 'read'; conversationId: string }
  | { type: 'unread'; total: number }
  | { type: 'realtime'; status: RealtimeStatus }
  | { type: 'error'; message: string | null }
  | { type: 'event'; envelope: Envelope }

export function reactedByMe(message: Message, reaction: Reaction, me: Me | null): boolean {
  return !!me && (message.reactions[reaction] ?? []).some((r) => r.kind === me.kind && r.id === me.id)
}

export function reduce(state: ChatState, action: Action): ChatState {
  switch (action.type) {
    case 'me':
      return { ...state, me: action.me }
    case 'inbox': {
      const conversations = Object.fromEntries(action.conversations.map((c) => [c.id, c]))
      return { ...state, inboxLoaded: true, error: null, conversations, order: action.conversations.map((c) => c.id) }
    }
    case 'page': {
      const existing = state.messages[action.conversationId] ?? []
      const known = new Set(existing.map((m) => m.id))
      const older = [...action.items].reverse().filter((m) => !known.has(m.id)).map((m) => own(state, m))
      return {
        ...state,
        messages: { ...state.messages, [action.conversationId]: [...older, ...existing] },
        olderCursor: { ...state.olderCursor, [action.conversationId]: action.nextCursor },
      }
    }
    case 'newer':
      return action.items.reduce((s, m) => upsert(s, m, 'append').state, state)
    case 'replies':
      return { ...state, replies: { ...state.replies, [action.parentId]: action.items.map((m) => own(state, m)) } }
    case 'upsert':
      return upsert(state, action.message, 'append').state
    case 'read':
      return patchConversation(state, action.conversationId, { unread: 0 })
    case 'unread':
      return { ...state, unreadTotal: action.total }
    case 'realtime':
      return { ...state, realtime: action.status }
    case 'error':
      return { ...state, error: action.message }
    case 'event':
      return onEvent(state, action.envelope)
  }
}

function onEvent(state: ChatState, envelope: Envelope): ChatState {
  switch (envelope.type) {
    case 'message.created': {
      const { state: next, added } = upsert(state, envelope.data as Message, 'append')
      const message = own(state, envelope.data as Message)
      if (!added || message.mine) return next
      const c = next.conversations[envelope.conversationId]
      return c ? patchConversation(next, c.id, { unread: c.unread + 1 }) : next
    }
    case 'message.edited':
    case 'message.deleted':
    case 'reactions.changed':
      return upsert(state, envelope.data as Message, 'replace-only').state
    case 'read.moved': {
      const reader = envelope.data as { kind: string; id: string }
      const mine = state.me && reader.kind === state.me.kind && reader.id === state.me.id
      return mine ? patchConversation(state, envelope.conversationId, { unread: 0 }) : state
    }
    case 'conversation.read_only':
      return patchConversation(state, envelope.conversationId, { status: 'READ_ONLY' })
    default:
      // members / access / unread / typing: the client refetches (see client.ts) or ignores.
      return state
  }
}

/** "Mine" from my identity: a live payload carries its author's value (Review Focus 5). */
function own(state: ChatState, m: Message): Message {
  if (!state.me) return m
  const mine = m.authorKind === state.me.kind && m.authorId === state.me.id
  return mine === m.mine ? m : { ...m, mine }
}

function upsert(state: ChatState, raw: Message, mode: 'append' | 'replace-only'): { state: ChatState; added: boolean } {
  const m = own(state, raw)
  if (m.parentId) {
    const thread = state.replies[m.parentId]
    const at = thread?.findIndex((r) => r.id === m.id) ?? -1
    if (at >= 0) {
      const next = [...thread!]
      next[at] = m
      return { state: { ...state, replies: { ...state.replies, [m.parentId]: next } }, added: false }
    }
    if (mode === 'replace-only') return { state, added: false }
    let s: ChatState = thread ? { ...state, replies: { ...state.replies, [m.parentId]: [...thread, m] } } : state
    s = bumpReplyCount(s, m)
    return { state: touch(s, m), added: true }
  }
  const list = state.messages[m.conversationId]
  const at = list?.findIndex((x) => x.id === m.id) ?? -1
  if (at >= 0) {
    const next = [...list!]
    next[at] = m
    return { state: { ...state, messages: { ...state.messages, [m.conversationId]: next } }, added: false }
  }
  if (mode === 'replace-only') return { state, added: false }
  // Never start a list with only the newest message: an unopened conversation loads its page on open.
  const s = list ? { ...state, messages: { ...state.messages, [m.conversationId]: [...list, m] } } : state
  return { state: touch(s, m), added: true }
}

/** A reply is counted once, on first sight — the REST reply and the event cannot both count it. */
function bumpReplyCount(state: ChatState, reply: Message): ChatState {
  const list = state.messages[reply.conversationId]
  const at = list?.findIndex((x) => x.id === reply.parentId) ?? -1
  if (at < 0) return state
  const next = [...list!]
  next[at] = { ...next[at], replyCount: next[at].replyCount + 1 }
  return { ...state, messages: { ...state.messages, [reply.conversationId]: next } }
}

/** New activity: the inbox row shows it and moves to the top. */
function touch(state: ChatState, m: Message): ChatState {
  const c = state.conversations[m.conversationId]
  if (!c) return state
  const s = patchConversation(state, c.id, { lastMessage: m, lastMessageAt: m.createdAt })
  return { ...s, order: [c.id, ...s.order.filter((id) => id !== c.id)] }
}

function patchConversation(state: ChatState, id: string, patch: Partial<Conversation>): ChatState {
  const c = state.conversations[id]
  return c ? { ...state, conversations: { ...state.conversations, [id]: { ...c, ...patch } } } : state
}
```

Note on `message.created` for a message I sent: `upsert` may add it (event first) — `own` marks it mine, so no unread. Add `export * from './core/reducer'` to `index.ts`.

- [ ] **Step 4: Run and commit**

Run: `cd client-expert && npx vitest run ../packages/evalos-chat/src/core/reducer.test.ts`
Expected: PASS (11).

```bash
git add packages/evalos-chat
git commit -m "feat(chat-ui): the chat reducer — pages, live events, replies, reactions, unread, mine from my identity"
```

---

### Task 4: The Ably connector and the chat client

**Files:**
- Create: `packages/evalos-chat/src/core/realtime.ts`, `packages/evalos-chat/src/core/client.ts`
- Test: `packages/evalos-chat/src/core/realtime.test.ts`, `packages/evalos-chat/src/core/client.test.ts`
- Modify: `packages/evalos-chat/src/index.ts`

**Interfaces:**
- Consumes: `ChatApi`, `cursorOf` (Task 2); `reduce`, `initialState`, `reactedByMe`, `ChatState`, `RealtimeStatus` (Task 3).
- Produces:
  - `type RealtimeHandlers = { onEvent(e: Envelope): void; onReconnect(): void; onStatus(s: RealtimeStatus): void }`
  - `type Realtime = { start(handlers: RealtimeHandlers): Promise<() => void> }`
  - `ablyRealtime(Realtime: AblyRealtimeCtor, fetchToken: () => Promise<TokenRequest>): Realtime`; `channelOf(clientId: string): string`
  - `createChatClient(api: ChatApi, realtime: Realtime | null)` → `{ getState, subscribe, start(inbox?: InboxParams), stop, refreshInbox, openConversation, closeConversation, loadOlder, loadReplies, send, toggleReaction, catchUp }`; `type ChatClient`

- [ ] **Step 1: Write the failing realtime tests**

`packages/evalos-chat/src/core/realtime.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest'
import { ablyRealtime, channelOf } from './realtime'
import type { Envelope, TokenRequest } from './types'

const token: TokenRequest = { keyName: 'k', clientId: 'CLIENT:c1', capability: '{}', ttl: 3_600_000, timestamp: 1, nonce: 'n', mac: 'm' }

/** Just enough of ably-js's Realtime for the connector: auth callback, one channel, connection events. */
class FakeRealtime {
  static last: FakeRealtime | null = null
  options: any
  subscribed: ((m: { data: unknown }) => void) | null = null
  entered = false
  closed = false
  connectionListener: ((c: { current: string }) => void) | null = null
  channelName = ''
  constructor(options: any) {
    this.options = options
    FakeRealtime.last = this
  }
  channels = {
    get: (name: string) => {
      this.channelName = name
      return {
        subscribe: async (cb: (m: { data: unknown }) => void) => {
          this.subscribed = cb
        },
        presence: {
          enter: async () => {
            this.entered = true
          },
        },
      }
    },
  }
  connection = { on: (cb: (c: { current: string }) => void) => (this.connectionListener = cb) }
  close() {
    this.closed = true
  }
}

function handlers() {
  return { onEvent: vi.fn(), onReconnect: vi.fn(), onStatus: vi.fn() }
}

describe('ablyRealtime', () => {
  /** Review Focus 3: no key on the server means REST only, and Ably is never constructed. */
  it('noRealtimeKeyMeansRestOnly', async () => {
    FakeRealtime.last = null
    const h = handlers()
    await ablyRealtime(FakeRealtime as never, () => Promise.reject(new Error('503'))).start(h)
    expect(FakeRealtime.last).toBeNull()
    expect(h.onStatus).toHaveBeenCalledWith('offline')
  })

  it('subscribes to my own channel, enters presence and relays envelopes', async () => {
    const h = handlers()
    const fetchToken = vi.fn().mockResolvedValue(token)
    const stop = await ablyRealtime(FakeRealtime as never, fetchToken).start(h)
    const fake = FakeRealtime.last!
    expect(fake.channelName).toBe('chat:user:CLIENT:c1')
    expect(fake.entered).toBe(true)
    const envelope: Envelope = { type: 'message.created', conversationId: 'v1', data: {} }
    fake.subscribed!({ data: envelope })
    expect(h.onEvent).toHaveBeenCalledWith(envelope)
    stop()
    expect(fake.closed).toBe(true)
  })

  it('reuses the token it fetched first, then asks again when Ably does', async () => {
    const fetchToken = vi.fn().mockResolvedValue(token)
    await ablyRealtime(FakeRealtime as never, fetchToken).start(handlers())
    const auth = FakeRealtime.last!.options.authCallback
    const got: unknown[] = []
    await new Promise<void>((done) => auth({}, (_e: unknown, t: unknown) => (got.push(t), done())))
    expect(fetchToken).toHaveBeenCalledTimes(1)
    await new Promise<void>((done) => auth({}, (_e: unknown, t: unknown) => (got.push(t), done())))
    expect(fetchToken).toHaveBeenCalledTimes(2)
    expect(got).toEqual([token, token])
  })

  it('reports live and offline, and signals a reconnect only after the first connection', async () => {
    const h = handlers()
    await ablyRealtime(FakeRealtime as never, vi.fn().mockResolvedValue(token)).start(h)
    const on = FakeRealtime.last!.connectionListener!
    on({ current: 'connected' })
    expect(h.onReconnect).not.toHaveBeenCalled()
    on({ current: 'disconnected' })
    on({ current: 'connected' })
    expect(h.onReconnect).toHaveBeenCalledTimes(1)
    expect(h.onStatus.mock.calls.map((c) => c[0])).toEqual(['live', 'offline', 'live'])
  })

  it('names the personal channel the way the backend does', () => {
    expect(channelOf('STAFF:abc')).toBe('chat:user:STAFF:abc')
  })
})
```

- [ ] **Step 2: Write the failing client tests**

`packages/evalos-chat/src/core/client.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest'
import { createChatClient } from './client'
import type { ChatApi } from './api'
import type { Realtime, RealtimeHandlers } from './realtime'
import type { Conversation, Message, Page } from './types'

const me = { kind: 'CLIENT' as const, id: 'client-1' }

function msg(id: string, conversationId = 'v1', over: Partial<Message> = {}): Message {
  return { id, conversationId, authorKind: 'STAFF', authorId: 'staff-1', authorName: 'Cam', body: id, parentId: null, replyCount: 0, createdAt: `2026-09-27T10:00:0${id.slice(-1)}Z`, editedAt: null, deleted: false, reactions: {}, mine: false, ...over }
}
function conv(id: string, over: Partial<Conversation> = {}): Conversation {
  return { id, caseId: `case-${id}`, caseCode: 'IE-1', serviceType: null, stage: 'CLIENT_REVIEW', type: 'CLIENT', status: 'ACTIVE', access: 'MEMBER', unread: 0, lastMessage: null, participants: [], lastMessageAt: null, ...over }
}
const page = (items: Message[], nextCursor: string | null = null): Page<Message> => ({ items, nextCursor })

function fakeApi(over: Partial<ChatApi> = {}): ChatApi {
  return {
    me: vi.fn().mockResolvedValue(me),
    inbox: vi.fn().mockResolvedValue({ items: [conv('v1'), conv('v2')], nextCursor: null }),
    messages: vi.fn().mockResolvedValue(page([])),
    replies: vi.fn().mockResolvedValue([]),
    send: vi.fn(),
    react: vi.fn(),
    read: vi.fn().mockResolvedValue(undefined),
    unread: vi.fn().mockResolvedValue(0),
    realtimeToken: vi.fn(),
    ...over,
  } as ChatApi
}

function fakeRealtime() {
  let h: RealtimeHandlers | null = null
  const realtime: Realtime = {
    start: async (handlers) => {
      h = handlers
      handlers.onStatus('live')
      return () => {}
    },
  }
  return { realtime, handlers: () => h! }
}

describe('createChatClient', () => {
  it('starts with who I am, the inbox and the unread total', async () => {
    const api = fakeApi({ unread: vi.fn().mockResolvedValue(3) })
    const client = createChatClient(api, null)
    await client.start({ type: 'CLIENT' })
    const s = client.getState()
    expect(s.me).toEqual(me)
    expect(s.order).toEqual(['v1', 'v2'])
    expect(s.unreadTotal).toBe(3)
    expect(s.realtime).toBe('offline')
    expect(api.inbox).toHaveBeenCalledWith({ type: 'CLIENT' })
  })

  it('reports a failed start instead of throwing', async () => {
    const client = createChatClient(fakeApi({ me: vi.fn().mockRejectedValue(new Error('boom')) }), null)
    await client.start()
    expect(client.getState().error).toBe('Could not load your conversations.')
  })

  it('opens a conversation and marks what it shows as read', async () => {
    const api = fakeApi({
      inbox: vi.fn().mockResolvedValue({ items: [conv('v1', { unread: 2 })], nextCursor: null }),
      messages: vi.fn().mockResolvedValue(page([msg('m2'), msg('m1')], 'older')),
    })
    const client = createChatClient(api, null)
    await client.start()
    await client.openConversation('v1')
    expect(client.getState().messages.v1.map((m) => m.id)).toEqual(['m1', 'm2'])
    expect(api.read).toHaveBeenCalledWith('v1', 'm2')
    expect(client.getState().conversations.v1.unread).toBe(0)
  })

  /** Review Focus 2: every loaded conversation catches up page by page; an empty one loads its first page. */
  it('aReconnectCatchesUpEveryLoadedConversation', async () => {
    const messages = vi.fn()
      .mockResolvedValueOnce(page([msg('m1')]))            // open v1
      .mockResolvedValueOnce(page([]))                     // open v2 (empty)
      .mockResolvedValueOnce(page([msg('m2')], 'next'))    // v1 after m1, page 1
      .mockResolvedValueOnce(page([msg('m3')]))            // v1 after m2, page 2
      .mockResolvedValueOnce(page([msg('m4', 'v2')]))      // v2 first page
    const api = fakeApi({ messages })
    const rt = fakeRealtime()
    const client = createChatClient(api, rt.realtime)
    await client.start()
    await client.openConversation('v1')
    await client.openConversation('v2')

    rt.handlers().onReconnect()
    await vi.waitFor(() => expect(messages).toHaveBeenCalledTimes(5))
    await vi.waitFor(() => expect(client.getState().messages.v2.map((m) => m.id)).toEqual(['m4']))

    expect(messages.mock.calls[2]).toEqual(['v1', { after: '2026-09-27T10:00:01Z|m1' }])
    expect(messages.mock.calls[3]).toEqual(['v1', { after: 'next' }])
    expect(messages.mock.calls[4]).toEqual(['v2'])
    expect(client.getState().messages.v1.map((m) => m.id)).toEqual(['m1', 'm2', 'm3'])
    expect(api.inbox).toHaveBeenCalledTimes(2)
  })

  it('refreshes the unread total and the inbox when the server says so', async () => {
    const api = fakeApi()
    const rt = fakeRealtime()
    const client = createChatClient(api, rt.realtime)
    await client.start()
    rt.handlers().onEvent({ type: 'unread.changed', conversationId: 'v1', data: null })
    rt.handlers().onEvent({ type: 'access.revoked', conversationId: 'v1', data: null })
    await vi.waitFor(() => expect(api.unread).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(api.inbox).toHaveBeenCalledTimes(2))
  })

  it('marks a live message read while its conversation is on screen, and not after closing it', async () => {
    const api = fakeApi({ messages: vi.fn().mockResolvedValue(page([msg('m1')])) })
    const rt = fakeRealtime()
    const client = createChatClient(api, rt.realtime)
    await client.start()
    await client.openConversation('v1')
    rt.handlers().onEvent({ type: 'message.created', conversationId: 'v1', data: msg('m2') })
    await vi.waitFor(() => expect(api.read).toHaveBeenCalledWith('v1', 'm2'))
    client.closeConversation('v1')
    rt.handlers().onEvent({ type: 'message.created', conversationId: 'v1', data: msg('m3') })
    await Promise.resolve()
    expect(api.read).not.toHaveBeenCalledWith('v1', 'm3')
  })

  it('sends trimmed text, refuses blank and over-long text, and toggles my reaction', async () => {
    const sent = msg('m9', 'v1', { authorKind: 'CLIENT', authorId: 'client-1', body: 'hi' })
    const reacted = { ...sent, reactions: { HEART: [{ kind: 'CLIENT' as const, id: 'client-1', name: 'Anita' }] } }
    const api = fakeApi({
      messages: vi.fn().mockResolvedValue(page([])),
      send: vi.fn().mockResolvedValue(sent),
      react: vi.fn().mockResolvedValue(reacted),
    })
    const client = createChatClient(api, null)
    await client.start()
    await client.openConversation('v1')

    await client.send('v1', '  hi  ')
    expect(api.send).toHaveBeenCalledWith('v1', 'hi', undefined)
    await client.send('v1', '   ')
    expect(api.send).toHaveBeenCalledTimes(1)
    await expect(client.send('v1', 'x'.repeat(4001))).rejects.toThrow('4,000')

    await client.toggleReaction(sent, 'HEART')
    expect(api.react).toHaveBeenCalledWith('m9', 'HEART', true)
    await client.toggleReaction(client.getState().messages.v1[0], 'HEART')
    expect(api.react).toHaveBeenLastCalledWith('m9', 'HEART', false)
  })
})
```

- [ ] **Step 3: Run to verify failure**

Run: `cd client-expert && npx vitest run ../packages/evalos-chat/src/core/realtime.test.ts ../packages/evalos-chat/src/core/client.test.ts`
Expected: FAIL — cannot resolve `./realtime` / `./client`.

- [ ] **Step 4: Implement**

`packages/evalos-chat/src/core/realtime.ts`:

```ts
import type * as Ably from 'ably'
import type { RealtimeStatus } from './reducer'
import type { Envelope, TokenRequest } from './types'

export type RealtimeHandlers = {
  onEvent(envelope: Envelope): void
  onReconnect(): void
  onStatus(status: RealtimeStatus): void
}

export type Realtime = { start(handlers: RealtimeHandlers): Promise<() => void> }

/** ably-js's `Realtime` constructor, passed in by the app so this package never imports Ably at runtime. */
export type AblyRealtimeCtor = new (options: Ably.ClientOptions) => Ably.Realtime

/** The backend's personal channel (`ChatChannels.personal`); a token's clientId is `KIND:uuid`. */
export function channelOf(clientId: string): string {
  return `chat:user:${clientId}`
}

/**
 * Live delivery over Ably (Unit 57 §5): subscribe and enter presence on my own channel, never
 * publish. The token is fetched once up front: if the server has no Ably key (503) this answers
 * REST-only without ever constructing Ably, so nothing retries in a loop.
 */
export function ablyRealtime(RealtimeClass: AblyRealtimeCtor, fetchToken: () => Promise<TokenRequest>): Realtime {
  return {
    async start({ onEvent, onReconnect, onStatus }) {
      let first: TokenRequest
      try {
        first = await fetchToken()
      } catch {
        onStatus('offline')
        return () => {}
      }
      let prefetched: TokenRequest | null = first
      const client = new RealtimeClass({
        clientId: first.clientId,
        authCallback: (_params, callback) => {
          const ready = prefetched
          prefetched = null
          ;(ready ? Promise.resolve(ready) : fetchToken()).then(
            (t) => callback(null, t as Ably.TokenRequest),
            (e: unknown) => callback(e instanceof Error ? e.message : String(e), null),
          )
        },
      })
      const channel = client.channels.get(channelOf(first.clientId))
      void channel.subscribe((message) => onEvent(message.data as Envelope))
      // Presence is how the backend decides between a live update and a push (§6).
      void channel.presence.enter().catch(() => {})
      let connectedBefore = false
      client.connection.on((change) => {
        if (change.current === 'connected') {
          onStatus('live')
          if (connectedBefore) onReconnect()
          connectedBefore = true
        } else if (change.current === 'disconnected' || change.current === 'suspended' || change.current === 'failed') {
          onStatus('offline')
        }
      })
      return () => client.close()
    },
  }
}
```

`packages/evalos-chat/src/core/client.ts`:

```ts
import { cursorOf, type ChatApi, type InboxParams } from './api'
import { initialState, reactedByMe, reduce, type Action, type ChatState } from './reducer'
import type { Realtime } from './realtime'
import { MAX_BODY, type Envelope, type Message, type Reaction } from './types'

/**
 * The chat store (Unit 57 §7): REST for every read and write, Ably for live events, REST again to
 * catch up after a reconnect. Framework-free — the React layer subscribes to it.
 */
export function createChatClient(api: ChatApi, realtime: Realtime | null) {
  let state: ChatState = initialState
  const listeners = new Set<() => void>()
  let inboxParams: InboxParams = {}
  let onScreen: string | null = null
  let stopRealtime: (() => void) | null = null

  function dispatch(action: Action) {
    state = reduce(state, action)
    listeners.forEach((listener) => listener())
  }

  async function refreshInbox() {
    const page = await api.inbox(inboxParams)
    dispatch({ type: 'inbox', conversations: page.items })
  }

  async function refreshUnread() {
    dispatch({ type: 'unread', total: await api.unread() })
  }

  async function start(params: InboxParams = {}) {
    inboxParams = params
    try {
      dispatch({ type: 'me', me: await api.me() })
      await Promise.all([refreshInbox(), refreshUnread()])
    } catch {
      dispatch({ type: 'error', message: 'Could not load your conversations.' })
      return
    }
    if (!realtime) {
      dispatch({ type: 'realtime', status: 'offline' })
      return
    }
    stopRealtime = await realtime.start({
      onEvent,
      onReconnect: () => void catchUp(),
      onStatus: (status) => dispatch({ type: 'realtime', status }),
    })
  }

  function onEvent(envelope: Envelope) {
    dispatch({ type: 'event', envelope })
    switch (envelope.type) {
      case 'unread.changed':
        void refreshUnread()
        break
      case 'members.changed':
      case 'access.granted':
      case 'access.revoked':
        void refreshInbox()
        break
      case 'message.created':
        if (envelope.conversationId === onScreen) void markRead(envelope.conversationId)
        break
    }
  }

  async function openConversation(id: string) {
    onScreen = id
    if (!state.messages[id]) {
      const page = await api.messages(id)
      dispatch({ type: 'page', conversationId: id, items: page.items, nextCursor: page.nextCursor })
    }
    await markRead(id)
  }

  function closeConversation(id: string) {
    if (onScreen === id) onScreen = null
  }

  async function loadOlder(id: string) {
    const before = state.olderCursor[id]
    if (!before) return
    const page = await api.messages(id, { before })
    dispatch({ type: 'page', conversationId: id, items: page.items, nextCursor: page.nextCursor })
  }

  async function loadReplies(parentId: string) {
    dispatch({ type: 'replies', parentId, items: await api.replies(parentId) })
  }

  /** After a reconnect: the inbox, the total, and every conversation already on the page (§5). */
  async function catchUp() {
    await Promise.all([refreshInbox(), refreshUnread()])
    for (const id of Object.keys(state.messages)) {
      const list = state.messages[id]
      const last = list[list.length - 1]
      if (!last) {
        const page = await api.messages(id)
        dispatch({ type: 'page', conversationId: id, items: page.items, nextCursor: page.nextCursor })
        continue
      }
      let after: string | null = cursorOf(last)
      while (after) {
        const page = await api.messages(id, { after })
        dispatch({ type: 'newer', conversationId: id, items: page.items })
        after = page.nextCursor
      }
    }
    if (onScreen) await markRead(onScreen)
  }

  /** Moves my watermark to the newest message on screen — only when there is something unread. */
  async function markRead(id: string) {
    const list = state.messages[id]
    const last = list?.[list.length - 1]
    if (!last || (state.conversations[id]?.unread ?? 0) === 0) return
    await api.read(id, last.id)
    dispatch({ type: 'read', conversationId: id })
    await refreshUnread()
  }

  async function send(id: string, body: string, parentId?: string) {
    const text = body.trim()
    if (!text) return
    if (text.length > MAX_BODY) throw new Error('A message is at most 4,000 characters.')
    dispatch({ type: 'upsert', message: await api.send(id, text, parentId) })
  }

  async function toggleReaction(message: Message, reaction: Reaction) {
    const on = !reactedByMe(message, reaction, state.me)
    dispatch({ type: 'upsert', message: await api.react(message.id, reaction, on) })
  }

  return {
    getState: () => state,
    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    start,
    stop: () => stopRealtime?.(),
    refreshInbox,
    openConversation,
    closeConversation,
    loadOlder,
    loadReplies,
    send,
    toggleReaction,
    catchUp,
  }
}

export type ChatClient = ReturnType<typeof createChatClient>
```

Note the test "opens a conversation and marks what it shows as read" relies on `markRead` running when the inbox row says `unread: 2`; the other open tests have `unread: 0` and correctly send nothing — except "marks a live message read while its conversation is on screen", where the live `message.created` from staff bumps unread to 1 first (the reducer runs before `markRead`).

Add to `index.ts`: `export * from './core/realtime'` and `export * from './core/client'`.

- [ ] **Step 5: Run, type-check and commit**

Run: `cd client-expert && npx vitest run ../packages/evalos-chat/src/core`
Expected: PASS (all core tests).

Run: `cd client-expert && npm run build:client`
Expected: success — `realtime.ts` type-checks against the installed `ably` types. If `Ably.ClientOptions`'s `authCallback` signature differs in 2.29 (e.g. the callback's first argument type), adjust the cast in `authCallback` only and ledger it.

```bash
git add packages/evalos-chat
git commit -m "feat(chat-ui): Ably connector with REST-only fallback, and the chat client with reconnect catch-up"
```

---

### Task 5: Text rendering helpers

**Files:**
- Create: `packages/evalos-chat/src/core/text.ts`
- Test: `packages/evalos-chat/src/core/text.test.ts`
- Modify: `packages/evalos-chat/src/index.ts`

**Interfaces:**
- Produces:
  - `type Part = { text: string; href?: string }`; `linkify(text: string): Part[]`
  - `type Row = { kind: 'day'; key: string; iso: string } | { kind: 'message'; message: Message }`; `rowsWithDays(messages: Message[]): Row[]`

- [ ] **Step 1: Write the failing test**

`packages/evalos-chat/src/core/text.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { linkify, rowsWithDays } from './text'
import type { Message } from './types'

describe('linkify', () => {
  /** Review Focus 4. */
  it('onlyHttpLinksAreLinksAndNothingIsHtml', () => {
    expect(linkify('<script>alert(1)</script> javascript:alert(1)')).toEqual([
      { text: '<script>alert(1)</script> javascript:alert(1)' },
    ])
    expect(linkify('See https://example.com/a?b=1, then http://x.test.')).toEqual([
      { text: 'See ' },
      { text: 'https://example.com/a?b=1', href: 'https://example.com/a?b=1' },
      { text: ', then ' },
      { text: 'http://x.test', href: 'http://x.test' },
      { text: '.' },
    ])
  })

  it('leaves plain text alone', () => {
    expect(linkify('no links here')).toEqual([{ text: 'no links here' }])
    expect(linkify('')).toEqual([])
  })
})

describe('rowsWithDays', () => {
  const at = (id: string, createdAt: string) => ({ id, createdAt }) as Message

  it('puts a day row before the first message of each local day', () => {
    const rows = rowsWithDays([at('a', '2026-09-26T09:00:00'), at('b', '2026-09-26T18:00:00'), at('c', '2026-09-27T08:00:00')])
    expect(rows.map((r) => (r.kind === 'day' ? `day:${r.key}` : r.message.id))).toEqual([
      'day:2026-09-26', 'a', 'b', 'day:2026-09-27', 'c',
    ])
  })
})
```

(The timestamps have no zone on purpose: `new Date('…T09:00:00')` is local time, so the day key is stable in any test machine's zone.)

- [ ] **Step 2: Run to verify failure**

Run: `cd client-expert && npx vitest run ../packages/evalos-chat/src/core/text.test.ts`
Expected: FAIL — cannot resolve `./text`.

- [ ] **Step 3: Implement**

`packages/evalos-chat/src/core/text.ts`:

```ts
import type { Message } from './types'

export type Part = { text: string; href?: string }

const URL = /\bhttps?:\/\/[^\s<>"']+/gi
const TRAILING = /[.,!?;:)\]}]+$/

/**
 * A body as text parts; only http(s) URLs become links (spec 57 §7: no HTML, ever). Rendered by
 * React as text nodes, so markup in a body stays visible characters.
 */
export function linkify(text: string): Part[] {
  const parts: Part[] = []
  let last = 0
  for (const match of text.matchAll(URL)) {
    const url = match[0].replace(TRAILING, '')
    const start = match.index ?? 0
    if (start > last) parts.push({ text: text.slice(last, start) })
    parts.push({ text: url, href: url })
    last = start + url.length
  }
  if (last < text.length) parts.push({ text: text.slice(last) })
  return parts
}

export type Row = { kind: 'day'; key: string; iso: string } | { kind: 'message'; message: Message }

/** Messages with a day row before the first of each local day; the view formats `iso` for its locale. */
export function rowsWithDays(messages: Message[]): Row[] {
  const rows: Row[] = []
  let current = ''
  for (const message of messages) {
    const d = new Date(message.createdAt)
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    if (key !== current) {
      rows.push({ kind: 'day', key, iso: message.createdAt })
      current = key
    }
    rows.push({ kind: 'message', message })
  }
  return rows
}
```

Add `export * from './core/text'` to `index.ts`.

- [ ] **Step 4: Run and commit**

Run: `cd client-expert && npx vitest run ../packages/evalos-chat/src/core/text.test.ts`
Expected: PASS (3).

```bash
git add packages/evalos-chat
git commit -m "feat(chat-ui): text-only message rendering with safe links, and day separators"
```

---

### Task 6: The React layer and styles

**Files:**
- Create: `packages/evalos-chat/src/react/ChatProvider.tsx`, `react/ChatInbox.tsx`, `react/ConversationView.tsx`, `react/MessageList.tsx`, `react/Composer.tsx`, `react/Reactions.tsx`, `react/ThreadPanel.tsx`, `react/CaseChatPanel.tsx`, `react/UnreadBadge.tsx`, `packages/evalos-chat/src/chat.css`
- Modify: `packages/evalos-chat/src/index.ts`

**Interfaces:**
- Consumes: `ChatClient` (Task 4), `ChatState`, `reactedByMe` (Task 3), `linkify`, `rowsWithDays` (Task 5), `REACTIONS`, `MAX_BODY`, `Message`, `ConversationType` (Task 2).
- Produces (all exported from `index.ts`):
  - `ChatProvider({ client, inbox?, children })` — starts the client on mount, stops on unmount
  - `useChatClient(): ChatClient`, `useChat<T>(select: (s: ChatState) => T): T`
  - `ChatInbox({ onOpen(conversationId: string), selectedId?: string })`
  - `ConversationView({ conversationId, onUploadDocument?: () => void })`
  - `CaseChatPanel({ caseId, type?: ConversationType, onUploadDocument? })`
  - `UnreadBadge()`; `MessageList`, `Composer`, `Reactions`, `ThreadPanel` (used internally, exported for the staff app later)

There is no component test harness in the portals (no Testing Library); the deliverable is verified by the client build type-checking every component and by the logic tests above, which carry the behaviour. Keep components thin: every decision lives in `core/`.

- [ ] **Step 1: Provider and hooks**

`packages/evalos-chat/src/react/ChatProvider.tsx`:

```tsx
import { createContext, useContext, useEffect, useSyncExternalStore, type ReactNode } from 'react'
import type { InboxParams } from '../core/api'
import type { ChatClient } from '../core/client'
import type { ChatState } from '../core/reducer'

const ChatContext = createContext<ChatClient | null>(null)

/** Starts the chat client for the subtree and stops it on unmount. Create the client once, outside render. */
export function ChatProvider({ client, inbox, children }: { client: ChatClient; inbox?: InboxParams; children: ReactNode }) {
  useEffect(() => {
    void client.start(inbox)
    return () => client.stop()
    // The inbox filter is fixed for a provider's life; a new filter is a new provider.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client])
  return <ChatContext.Provider value={client}>{children}</ChatContext.Provider>
}

export function useChatClient(): ChatClient {
  const client = useContext(ChatContext)
  if (!client) throw new Error('useChatClient needs a <ChatProvider> above it')
  return client
}

/** A slice of chat state. `select` must return state it was given (not a new object), or React re-renders forever. */
export function useChat<T>(select: (state: ChatState) => T): T {
  const client = useChatClient()
  return useSyncExternalStore(client.subscribe, () => select(client.getState()))
}
```

- [ ] **Step 2: Leaf components**

`packages/evalos-chat/src/react/UnreadBadge.tsx`:

```tsx
import { useChat } from './ChatProvider'

/** The nav badge: total unread across every conversation the caller can open. */
export function UnreadBadge() {
  const total = useChat((s) => s.unreadTotal)
  if (total === 0) return null
  return (
    <span className="ec-badge" aria-label={`${total} unread messages`}>
      {total > 99 ? '99+' : total}
    </span>
  )
}
```

`packages/evalos-chat/src/react/Reactions.tsx`:

```tsx
import { useState } from 'react'
import { reactedByMe } from '../core/reducer'
import { REACTIONS, type Message, type Reaction } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'

/** The six reactions: counts for any given, a picker when the caller may react. */
export function Reactions({ message, disabled }: { message: Message; disabled: boolean }) {
  const client = useChatClient()
  const me = useChat((s) => s.me)
  const [picking, setPicking] = useState(false)
  const given = (Object.keys(REACTIONS) as Reaction[]).filter((r) => (message.reactions[r]?.length ?? 0) > 0)

  return (
    <div className="ec-reactions">
      {given.map((r) => (
        <button
          key={r}
          type="button"
          className={reactedByMe(message, r, me) ? 'ec-reaction ec-reaction--mine' : 'ec-reaction'}
          disabled={disabled}
          title={message.reactions[r]!.map((p) => p.name).join(', ')}
          onClick={() => void client.toggleReaction(message, r)}
        >
          {REACTIONS[r]} {message.reactions[r]!.length}
        </button>
      ))}
      {!disabled && (
        <button type="button" className="ec-reaction ec-reaction--add" aria-label="Add a reaction" onClick={() => setPicking(!picking)}>
          +
        </button>
      )}
      {picking &&
        (Object.keys(REACTIONS) as Reaction[]).map((r) => (
          <button
            key={`pick-${r}`}
            type="button"
            className="ec-reaction"
            onClick={() => {
              setPicking(false)
              void client.toggleReaction(message, r)
            }}
          >
            {REACTIONS[r]}
          </button>
        ))}
    </div>
  )
}
```

`packages/evalos-chat/src/react/Composer.tsx`:

```tsx
import { useState, type KeyboardEvent } from 'react'
import { MAX_BODY, type Message } from '../core/types'
import { useChatClient } from './ChatProvider'

/** Enter sends, Shift+Enter breaks a line; a reply names its parent; documents go through the app's own flow. */
export function Composer({
  conversationId,
  replyTo,
  onCancelReply,
  onUploadDocument,
}: {
  conversationId: string
  replyTo?: Message | null
  onCancelReply?: () => void
  onUploadDocument?: () => void
}) {
  const client = useChatClient()
  const [body, setBody] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function send() {
    if (!body.trim() || sending) return
    setSending(true)
    setError(null)
    try {
      await client.send(conversationId, body, replyTo?.id)
      setBody('')
      onCancelReply?.()
    } catch (e) {
      setError(e instanceof Error && e.message.includes('4,000') ? e.message : 'Your message was not sent. Try again.')
    } finally {
      setSending(false)
    }
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      void send()
    }
  }

  return (
    <div className="ec-composer">
      {replyTo && (
        <p className="ec-composer__reply">
          Replying to {replyTo.mine ? 'yourself' : (replyTo.authorName ?? 'a message')}
          <button type="button" onClick={onCancelReply} aria-label="Cancel reply">×</button>
        </p>
      )}
      <textarea
        className="ec-composer__input"
        rows={2}
        maxLength={MAX_BODY}
        value={body}
        placeholder="Write a message"
        aria-label="Message"
        onChange={(e) => setBody(e.target.value)}
        onKeyDown={onKeyDown}
      />
      <div className="ec-composer__bar">
        {onUploadDocument && (
          <button type="button" className="ec-link" onClick={onUploadDocument}>
            Upload a document
          </button>
        )}
        <span className="ec-muted">{body.length > MAX_BODY - 200 ? `${body.length}/${MAX_BODY}` : ''}</span>
        <button type="button" className="ec-send" disabled={!body.trim() || sending} onClick={() => void send()}>
          Send
        </button>
      </div>
      {error && <p className="ec-error" role="alert">{error}</p>}
    </div>
  )
}
```

- [ ] **Step 3: Message list and thread**

`packages/evalos-chat/src/react/MessageList.tsx`:

```tsx
import { Fragment, useState } from 'react'
import { linkify, rowsWithDays } from '../core/text'
import type { Message } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'
import { Reactions } from './Reactions'
import { ThreadPanel } from './ThreadPanel'

const EMPTY: Message[] = []

export function MessageBody({ message }: { message: Message }) {
  if (message.deleted) return <p className="ec-body ec-muted">Message deleted</p>
  return (
    <p className="ec-body">
      {linkify(message.body).map((part, i) =>
        part.href ? (
          <a key={i} href={part.href} target="_blank" rel="noopener noreferrer nofollow">
            {part.text}
          </a>
        ) : (
          <Fragment key={i}>{part.text}</Fragment>
        ),
      )}
      {message.editedAt && <span className="ec-muted"> (edited)</span>}
    </p>
  )
}

/** One conversation's history: earlier on request, day separators, reactions, replies inline. */
export function MessageList({ conversationId, readOnly, onReply }: { conversationId: string; readOnly: boolean; onReply(m: Message): void }) {
  const client = useChatClient()
  const messages = useChat((s) => s.messages[conversationId] ?? EMPTY)
  const older = useChat((s) => s.olderCursor[conversationId])
  const [thread, setThread] = useState<string | null>(null)

  if (messages.length === 0) return <p className="ec-empty">No messages yet.</p>

  return (
    <div className="ec-list">
      {older && (
        <button type="button" className="ec-link ec-older" onClick={() => void client.loadOlder(conversationId)}>
          Show earlier messages
        </button>
      )}
      {rowsWithDays(messages).map((row) =>
        row.kind === 'day' ? (
          <p key={`day-${row.key}`} className="ec-day">
            {new Date(row.iso).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })}
          </p>
        ) : (
          <div key={row.message.id} className={row.message.mine ? 'ec-message ec-message--mine' : 'ec-message'}>
            <p className="ec-meta">
              <strong>{row.message.mine ? 'You' : (row.message.authorName ?? 'Unknown')}</strong>{' '}
              <span className="ec-muted">{new Date(row.message.createdAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}</span>
            </p>
            <MessageBody message={row.message} />
            {!row.message.deleted && <Reactions message={row.message} disabled={readOnly} />}
            <p className="ec-actions">
              {!readOnly && !row.message.deleted && (
                <button type="button" className="ec-link" onClick={() => onReply(row.message)}>Reply</button>
              )}
              {row.message.replyCount > 0 && (
                <button type="button" className="ec-link" onClick={() => setThread(thread === row.message.id ? null : row.message.id)}>
                  {row.message.replyCount} {row.message.replyCount === 1 ? 'reply' : 'replies'}
                </button>
              )}
            </p>
            {thread === row.message.id && <ThreadPanel parent={row.message} readOnly={readOnly} />}
          </div>
        ),
      )}
    </div>
  )
}
```

`packages/evalos-chat/src/react/ThreadPanel.tsx`:

```tsx
import { useEffect } from 'react'
import type { Message } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'
import { Composer } from './Composer'
import { MessageBody } from './MessageList'
import { Reactions } from './Reactions'

const EMPTY: Message[] = []

/** The replies to one message, loaded when opened; a reply composer unless read-only. */
export function ThreadPanel({ parent, readOnly }: { parent: Message; readOnly: boolean }) {
  const client = useChatClient()
  const replies = useChat((s) => s.replies[parent.id] ?? EMPTY)

  useEffect(() => {
    void client.loadReplies(parent.id)
  }, [client, parent.id])

  return (
    <div className="ec-thread">
      {replies.map((reply) => (
        <div key={reply.id} className="ec-message ec-message--reply">
          <p className="ec-meta">
            <strong>{reply.mine ? 'You' : (reply.authorName ?? 'Unknown')}</strong>
          </p>
          <MessageBody message={reply} />
          {!reply.deleted && <Reactions message={reply} disabled={readOnly} />}
        </div>
      ))}
      {!readOnly && <Composer conversationId={parent.conversationId} replyTo={parent} />}
    </div>
  )
}
```

(`MessageList` and `ThreadPanel` import each other; ES modules resolve this because neither uses the other at module-evaluation time.)

- [ ] **Step 4: Conversation, inbox and case panel**

`packages/evalos-chat/src/react/ConversationView.tsx`:

```tsx
import { useEffect, useState } from 'react'
import type { Message } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'
import { Composer } from './Composer'
import { MessageList } from './MessageList'

/** One conversation: case header, read-only / oversight banner, history, composer. */
export function ConversationView({ conversationId, onUploadDocument }: { conversationId: string; onUploadDocument?: () => void }) {
  const client = useChatClient()
  const conversation = useChat((s) => s.conversations[conversationId])
  const realtime = useChat((s) => s.realtime)
  const [replyTo, setReplyTo] = useState<Message | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    setFailed(false)
    client.openConversation(conversationId).catch(() => setFailed(true))
    return () => client.closeConversation(conversationId)
  }, [client, conversationId])

  if (!conversation) return <p className="ec-empty">This conversation is not available.</p>
  const viewer = conversation.access === 'VIEWER'
  const readOnly = viewer || conversation.status === 'READ_ONLY'

  return (
    <section className="ec-conversation">
      <header className="ec-header">
        <strong>{conversation.caseCode}</strong>
        {conversation.serviceType && <span className="ec-muted"> · {conversation.serviceType.replaceAll('_', ' ').toLowerCase()}</span>}
      </header>
      {viewer && <p className="ec-banner">Oversight — read only</p>}
      {!viewer && conversation.status === 'READ_ONLY' && <p className="ec-banner">This case is closed. The conversation is kept as its history.</p>}
      {realtime === 'offline' && <p className="ec-banner ec-muted">Live updates are paused; new messages appear when you reopen this.</p>}
      {failed ? (
        <p className="ec-error" role="alert">
          Could not load messages.{' '}
          <button type="button" className="ec-link" onClick={() => client.openConversation(conversationId).then(() => setFailed(false), () => setFailed(true))}>
            Try again
          </button>
        </p>
      ) : (
        <MessageList conversationId={conversationId} readOnly={readOnly} onReply={setReplyTo} />
      )}
      {!readOnly && (
        <Composer conversationId={conversationId} replyTo={replyTo} onCancelReply={() => setReplyTo(null)} onUploadDocument={onUploadDocument} />
      )}
    </section>
  )
}
```

`packages/evalos-chat/src/react/ChatInbox.tsx`:

```tsx
import { useMemo } from 'react'
import type { Conversation } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'

const TYPE_LABEL: Record<Conversation['type'], string> = { CLIENT: 'Case team', INTERNAL: 'Internal', EXPERT: 'Expert' }

/** Conversations grouped by case, most recent activity first, each with its unread count. */
export function ChatInbox({ onOpen, selectedId }: { onOpen(conversationId: string): void; selectedId?: string }) {
  const client = useChatClient()
  const loaded = useChat((s) => s.inboxLoaded)
  const error = useChat((s) => s.error)
  const order = useChat((s) => s.order)
  const conversations = useChat((s) => s.conversations)

  const groups = useMemo(() => {
    const byCase = new Map<string, Conversation[]>()
    for (const id of order) {
      const c = conversations[id]
      if (c) byCase.set(c.caseCode, [...(byCase.get(c.caseCode) ?? []), c])
    }
    return [...byCase.entries()]
  }, [order, conversations])

  if (error) {
    return (
      <p className="ec-error" role="alert">
        {error}{' '}
        <button type="button" className="ec-link" onClick={() => void client.start()}>Try again</button>
      </p>
    )
  }
  if (!loaded) return <p className="ec-empty">Loading conversations…</p>
  if (groups.length === 0) return <p className="ec-empty">No conversations yet. One opens with each case.</p>

  return (
    <nav className="ec-inbox" aria-label="Conversations">
      {groups.map(([caseCode, list]) => (
        <div key={caseCode} className="ec-inbox__case">
          <p className="ec-inbox__title">{caseCode}</p>
          {list.map((c) => (
            <button
              key={c.id}
              type="button"
              className={c.id === selectedId ? 'ec-inbox__row ec-inbox__row--selected' : 'ec-inbox__row'}
              onClick={() => onOpen(c.id)}
            >
              <span className="ec-inbox__type">{TYPE_LABEL[c.type]}</span>
              <span className="ec-inbox__preview ec-muted">
                {c.lastMessage ? (c.lastMessage.deleted ? 'Message deleted' : c.lastMessage.body) : 'No messages yet'}
              </span>
              {c.unread > 0 && <span className="ec-badge">{c.unread}</span>}
            </button>
          ))}
        </div>
      ))}
    </nav>
  )
}
```

`packages/evalos-chat/src/react/CaseChatPanel.tsx`:

```tsx
import type { ConversationType } from '../core/types'
import { useChat } from './ChatProvider'
import { ConversationView } from './ConversationView'

/** The case page's right-hand panel: that case's conversation of one type. */
export function CaseChatPanel({ caseId, type = 'CLIENT', onUploadDocument }: { caseId: string; type?: ConversationType; onUploadDocument?: () => void }) {
  const loaded = useChat((s) => s.inboxLoaded)
  const conversationId = useChat((s) => s.order.find((id) => s.conversations[id]?.caseId === caseId && s.conversations[id]?.type === type))

  if (!loaded) return <p className="ec-empty">Loading…</p>
  if (!conversationId) return <p className="ec-empty">The conversation for this case opens once the case team is assigned.</p>
  return <ConversationView conversationId={conversationId} onUploadDocument={onUploadDocument} />
}
```

(`useChat` here returns a string or `undefined` — a primitive, so it is safe for `useSyncExternalStore`.)

- [ ] **Step 5: Styles and exports**

`packages/evalos-chat/src/chat.css`:

```css
/* EvalOS chat (Unit 57 §7). Every colour is a --chat-* variable; each app maps them to its tokens. */
:root {
  --chat-bg: #ffffff;
  --chat-surface: #f7f7f8;
  --chat-border: #e4e4e7;
  --chat-text: #18181b;
  --chat-muted: #71717a;
  --chat-accent: #2563eb;
  --chat-accent-text: #ffffff;
  --chat-mine-bg: #eff6ff;
  --chat-danger: #dc2626;
  --chat-radius: 8px;
}

.ec-inbox, .ec-conversation { color: var(--chat-text); background: var(--chat-bg); display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.ec-inbox__case { display: flex; flex-direction: column; gap: 2px; }
.ec-inbox__title { font-size: 12px; font-weight: 600; color: var(--chat-muted); margin: 8px 0 2px; }
.ec-inbox__row { display: grid; grid-template-columns: auto 1fr auto; gap: 8px; align-items: center; text-align: left; padding: 8px; border-radius: var(--chat-radius); border: 1px solid transparent; background: none; cursor: pointer; }
.ec-inbox__row--selected { border-color: var(--chat-border); background: var(--chat-surface); }
.ec-inbox__type { font-weight: 500; }
.ec-inbox__preview { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ec-header { padding-bottom: 8px; border-bottom: 1px solid var(--chat-border); }
.ec-banner { margin: 0; padding: 6px 8px; font-size: 13px; background: var(--chat-surface); border-radius: var(--chat-radius); }
.ec-list { display: flex; flex-direction: column; gap: 10px; overflow-y: auto; flex: 1; min-height: 0; }
.ec-day { align-self: center; font-size: 12px; color: var(--chat-muted); margin: 6px 0; }
.ec-message { padding: 8px 10px; border-radius: var(--chat-radius); background: var(--chat-surface); max-width: 85%; }
.ec-message--mine { align-self: flex-end; background: var(--chat-mine-bg); }
.ec-message--reply { max-width: 100%; }
.ec-meta { margin: 0 0 2px; font-size: 13px; }
.ec-body { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.ec-body a { color: var(--chat-accent); }
.ec-actions { margin: 4px 0 0; display: flex; gap: 12px; font-size: 13px; }
.ec-thread { margin-top: 8px; padding-left: 10px; border-left: 2px solid var(--chat-border); display: flex; flex-direction: column; gap: 6px; }
.ec-reactions { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
.ec-reaction { font-size: 13px; padding: 1px 6px; border-radius: 999px; border: 1px solid var(--chat-border); background: var(--chat-bg); cursor: pointer; }
.ec-reaction--mine { border-color: var(--chat-accent); }
.ec-composer { display: flex; flex-direction: column; gap: 4px; border-top: 1px solid var(--chat-border); padding-top: 8px; }
.ec-composer__input { width: 100%; resize: vertical; padding: 8px; border-radius: var(--chat-radius); border: 1px solid var(--chat-border); font: inherit; color: inherit; background: var(--chat-bg); }
.ec-composer__bar { display: flex; align-items: center; gap: 8px; }
.ec-composer__bar .ec-send { margin-left: auto; }
.ec-composer__reply { margin: 0; font-size: 13px; color: var(--chat-muted); display: flex; gap: 6px; align-items: center; }
.ec-send { padding: 6px 14px; border: 0; border-radius: var(--chat-radius); background: var(--chat-accent); color: var(--chat-accent-text); cursor: pointer; }
.ec-send:disabled { opacity: 0.5; cursor: default; }
.ec-link { background: none; border: 0; padding: 0; color: var(--chat-accent); cursor: pointer; font: inherit; }
.ec-older { align-self: center; }
.ec-muted { color: var(--chat-muted); }
.ec-empty { color: var(--chat-muted); font-size: 14px; }
.ec-error { color: var(--chat-danger); font-size: 14px; }
.ec-badge { min-width: 18px; padding: 0 5px; border-radius: 999px; background: var(--chat-accent); color: var(--chat-accent-text); font-size: 11px; font-weight: 600; text-align: center; line-height: 18px; }
```

`packages/evalos-chat/src/index.ts` final form:

```ts
export * from './core/types'
export * from './core/api'
export * from './core/reducer'
export * from './core/realtime'
export * from './core/client'
export * from './core/text'
export { ChatProvider, useChat, useChatClient } from './react/ChatProvider'
export { ChatInbox } from './react/ChatInbox'
export { ConversationView } from './react/ConversationView'
export { CaseChatPanel } from './react/CaseChatPanel'
export { MessageList, MessageBody } from './react/MessageList'
export { Composer } from './react/Composer'
export { Reactions } from './react/Reactions'
export { ThreadPanel } from './react/ThreadPanel'
export { UnreadBadge } from './react/UnreadBadge'
```

(The stylesheet is imported by the app — `import '@evalos/chat/chat.css'` needs `"@evalos/chat/*": ["../../packages/evalos-chat/src/*"]` in `paths` and the alias already covers the Vite side; add that `paths` line now.)

- [ ] **Step 6: Type-check, test, commit**

Run: `cd client-expert && npm test && npm run build:client && npm run lint`
Expected: all tests pass; the client build type-checks the package's components (under this app's `noUnusedLocals`/`noUnusedParameters`); oxlint reports no errors in the package. If the `eslint-disable` comment in `ChatProvider` is flagged as unknown by oxlint, drop the comment (oxlint does not enforce exhaustive-deps by default) and ledger it.

```bash
git add packages/evalos-chat client-expert/client/tsconfig.app.json
git commit -m "feat(chat-ui): React provider, inbox, conversation, composer, replies, reactions, unread badge and styles"
```

---

### Task 7: Docs and memories

**Files:**
- Modify: `context/specs/58-client-portal.md` (status line; §8 phase 2 BUILT; note the `me` route and reactor identity)
- Modify: `context/specs/57-case-chat.md` §4 (add `GET me`; reactions carry `{ kind, id, name }`), §7 (consumed through an alias + `dedupe`, not a `file:` dependency; the portal subset built)
- Modify: `.claude/implementation-status.md` (Unit 58 row → phase 2 built, with evidence; Unit 57 row: portal subset of the package exists), `.claude/project-context.md` (repo layout gains `packages/evalos-chat`)
- Modify: `.serena/memories/implementation_status.md`, `.serena/memories/project_context.md`

- [ ] **Step 1: Edits (edits, not notes beside the old text)**
  - Spec 58 status: "phases 1–2 BUILT 2026-09-xx; phases 3–4 not built". §8 item 2 gains "(built: `packages/evalos-chat`, the portal subset — no search, typing, presence, seen-by, edit/delete UI)".
  - Spec 57 §4 table: add `| GET me | the caller as { kind, id }, so a client computes "mine" on live events built for their author |`; in the message description say reactions list `{ kind, id, name }` per reactor.
  - Spec 57 §7 first paragraph: "A **source-only** local package imported through a Vite/TS alias (`@evalos/chat`), with `resolve.dedupe` making the app's `react` and `ably` the only copies — no `file:` dependency and no `node_modules` of its own. React is a peer; the app passes ably-js's `Realtime` constructor in."
  - Implementation status: evidence = `api.test.ts`, `reducer.test.ts`, `realtime.test.ts`, `client.test.ts`, `text.test.ts`, `MessageServiceTest#reactionsCarryWhoReacted`, `StaffChatControllerTest#meNamesTheCallerInChatTerms`; gap = no page mounts it yet (phase 3), no component tests (no Testing Library in the portals), never exercised against a real Ably app.
  - Project context: add `packages/evalos-chat/` beside `frontend/` and `client-expert/` in the layout, one line.

- [ ] **Step 2: Verify and commit**

Run: `grep -n "file:" context/specs/57-case-chat.md` — expected: no line still saying the package is consumed through a `file:` dependency.

```bash
git add context .claude .serena
git commit -m "docs(unit-58): phase 2 built — the evalos-chat package, GET me, reactor identity"
```
