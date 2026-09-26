# Unit 57 Phase 1 — Case Chat Backend Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every case has three conversations (Client, Internal, Expert) whose membership EvalOS computes from case assignments; messages reach every connected participant instantly through Ably, and participants who are not connected get a browser push.

**Architecture:** A `com.ie.evalos.chat` package. A pure `ChatMembership` turns a `CaseRoster` (loaded by `ChatRosterLoader`) into expected participants; `ConversationService` applies the difference to append-only member rows; `ChatAccess` decides MEMBER / VIEWER / NONE; `MessageService` owns messages, reactions, reads and search. An after-commit `ChatLifecycleListener` and an hourly `ChatReconcileSweep` keep conversations in step with cases. Thin controllers expose the same operations on the staff, client-portal and expert-portal surfaces. Every committed change is published as `ChatChanged`; after commit, `ChatFanout` publishes it through Ably into each current member's private channel and `ChatPushNotifier` sends a Web Push to members with no app open. PostgreSQL is the record; Ably only relays.

**Tech Stack:** Java 21 / Spring Boot 3.5, Spring Data JPA, JdbcTemplate, Flyway (PostgreSQL), Ably (`io.ably:ably-java` 1.2.53, REST publish + token requests), `nl.martijndwars:web-push` 5.1.2, JUnit 5, Mockito, AssertJ, MockMvc.

**Spec:** `context/specs/57-case-chat.md` (Unit 57), phase 1 of 3. Phase 2 (package + staff app) and phase 3 (portals) get their own plans after this ships.

## Global Constraints

- Brand-scoped by default: every query filters by `brand_id` (CLAUDE.md). A query without brand scoping is a bug.
- Append-only truth: `conversation_members` rows are never deleted; leaving stamps `left_at`. Audit rows are never updated or deleted.
- Schema changes are new Flyway migrations in `backend/src/main/resources/db/migration/`; applied migrations are never edited. This unit takes **`V69`**. (Spec 55's pending `answers` drop moves to `V70` — Task 9 edits spec 55.)
- Conversation types: `CLIENT`, `INTERNAL`, `EXPERT`. Statuses: `ACTIVE`, `READ_ONLY`.
- Membership (spec §1): CLIENT = client + pipeline Sales + PM/Coordinator/Case Manager; INTERNAL = pipeline Sales + PM/Coordinator/Case Manager + brand ENMs; EXPERT = PM/Coordinator/Case Manager + brand ENMs + expert with an `OFFERED` or `ACCEPTED` offer.
- Viewers: GM (every brand) and BRAND_MANAGER (own brand) — read only, no member rows, no reactions, no read receipts.
- A client may only reach `CLIENT` conversations; an expert only `EXPERT` ones.
- Text only. Message body 1–4,000 characters after `strip()`. Replies one level deep.
- Edit and delete own messages only; delete clears `body`, stamps `deleted_at`; the original text goes to `audit_event`.
- Read-only at `CLOSED`; `DELIVERED` stays open. Writes to a read-only conversation → 409 `CONVERSATION_READ_ONLY`.
- Rate limit: 30 messages per minute per identity → 429.
- Reactions: exactly `THUMBS_UP`, `HEART`, `LAUGH`, `CELEBRATE`, `SURPRISED`, `THANKS`.
- Not your conversation (or you left it) → 403 with the same message whether or not it exists.
- Real-time: writes go through REST; Ably only relays, after commit, into **one private channel per person** (`chat:user:<KIND>:<uuid>`); no token may publish. Envelope `type` strings are fixed by Task 10 and are the contract phases 2–3 code against.
- Push: only to members with no open session, never the author; the payload names who and where, **never the message text**; one notification per conversation (`tag` = conversation id).
- The message rate limiter and typing throttle are in memory (one backend instance) and marked `ponytail:`; Ably itself removes the one-instance limit for delivery.
- Credentials `ABLY_API_KEY` and `EVALOS_PUSH_VAPID_PRIVATE` never carry defaults (`ConfigSecretsTest`). Without `ABLY_API_KEY` chat works over REST without live updates.
- Code style: tabs, Javadoc explaining *why*, as in the surrounding code. Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Tests run with `./mvnw -q test -Dtest=<Class>` from `backend/`. The real-Postgres tests run only when a local Postgres is reachable (`LocalPostgresIntegrationTest`'s `@EnabledIf("postgresIsUsable")` pattern).

## Review Focus

1. **Two messages with the same `created_at`** — paging must neither skip nor repeat either one. → Task 7, `pagingIsStableForEqualTimestamps` (Postgres).
2. **A case whose opportunity the mirror does not hold** (portal case whose deal never mirrored, or a manual case) — Sales set is empty, nothing throws. → Task 3, `noMirroredOpportunityMeansNoSales`.
3. **A person who is both a pipeline Sales holder and the case's Case Manager** (or any two roles) — exactly one current member row, labelled by the more specific case role. → Task 3, `onePersonHoldingTwoRolesIsOneMember`.
4. **The sweep running while a listener is syncing the same case** — the partial unique index must turn a double insert into a no-op, not a 500 or a duplicate. → Task 4, `concurrentSyncInsertsOneRow` (Postgres).
5. **A viewer (GM) tries to post, react or mark read** — refused with 403, never recorded. → Task 7, `aViewerCannotWrite`.

---

## File Structure

```
backend/src/main/resources/db/migration/V69__case_chat.sql           Task 2
backend/src/main/java/com/ie/evalos/chat/
  ConversationType.java  ConversationStatus.java  ParticipantKind.java
  ChatRole.java  LeftReason.java  Reaction.java                        Task 2 (enums)
  Conversation.java  ConversationMember.java  Message.java
  MessageReaction.java  MessageRead.java                               Task 2 (entities)
  ConversationRepository.java  ConversationMemberRepository.java
  MessageRepository.java  MessageReactionRepository.java
  MessageReadRepository.java                                           Task 2
  CaseRoster.java  ChatRosterLoader.java  ChatMembership.java          Task 3
  ExpectedMember.java                                                  Task 3
  ConversationService.java  ChatChanged.java                           Task 4
  ChatIdentity.java  ChatAccess.java  ChatAccessLevel.java             Task 5
  ChatLifecycleListener.java                                           Task 6
  ChatReconcileSweep.java (in com.ie.evalos.job)                       Task 6
  MessageService.java  ChatViews.java  ChatInboxQuery.java
  ChatRateLimiter.java  ConversationReadOnlyException.java             Task 7
  ChatApi.java (shared controller logic)                               Task 8
  MembersChanged.java                                                  Task 10
  live/AblySettings.java  live/ChatRealtime.java  live/AblyToken.java
  live/ChatChannels.java  live/RealtimeUnavailableException.java       Task 9
  live/ChatEnvelope.java  live/ChatFanout.java  live/ChatPresence.java
  live/ChatTyping.java                                                 Task 10
  push/PushSettings.java  push/PushSubscription.java
  push/PushSubscriptionRepository.java  push/PushSender.java
  push/ChatPushNotifier.java                                           Task 11
backend/src/main/java/com/ie/evalos/web/
  StaffChatController.java  ClientChatController.java
  ExpertChatController.java                                            Task 8
```

Removed in Task 1: `integration/StreamChat.java`, `integration/ChatUnavailableException.java`, `service/ChatTokenService.java`, `web/ChatController.java`, their three tests.

---

### Task 1: Remove the Unit 56 Stream setup

**Files:**
- Delete: `backend/src/main/java/com/ie/evalos/integration/StreamChat.java`
- Delete: `backend/src/main/java/com/ie/evalos/integration/ChatUnavailableException.java`
- Delete: `backend/src/main/java/com/ie/evalos/service/ChatTokenService.java`
- Delete: `backend/src/main/java/com/ie/evalos/web/ChatController.java`
- Delete: `backend/src/test/java/com/ie/evalos/integration/StreamChatTest.java`
- Delete: `backend/src/test/java/com/ie/evalos/service/ChatTokenServiceTest.java`
- Delete: `backend/src/test/java/com/ie/evalos/web/ChatControllerTest.java`
- Delete: `context/specs/56-live-chat-setup.md`
- Modify: `backend/src/main/java/com/ie/evalos/common/ApiExceptionHandler.java` (remove `onChatUnavailable`)
- Modify: `backend/src/main/resources/application.yml` (remove the `evalos.stream` block)
- Modify: `docker-compose.yml` (remove the two `STREAM_*` lines and their comment)

**Interfaces:** Produces nothing; removes `StreamChat`, `ChatTokenService`, `ChatController`, `ChatUnavailableException` so later tasks can reuse the `Chat*` names.

These files are **uncommitted** in the working tree (Unit 56 was never committed), so `git rm` will refuse them; delete them from disk instead.

- [ ] **Step 1: Delete the files**

```bash
cd backend
rm src/main/java/com/ie/evalos/integration/StreamChat.java \
   src/main/java/com/ie/evalos/integration/ChatUnavailableException.java \
   src/main/java/com/ie/evalos/service/ChatTokenService.java \
   src/main/java/com/ie/evalos/web/ChatController.java \
   src/test/java/com/ie/evalos/integration/StreamChatTest.java \
   src/test/java/com/ie/evalos/service/ChatTokenServiceTest.java \
   src/test/java/com/ie/evalos/web/ChatControllerTest.java \
   ../context/specs/56-live-chat-setup.md
```

- [ ] **Step 2: Remove the 503 handler** — delete this block from `ApiExceptionHandler.java`:

```java
	/** No Stream app is configured here (Unit 56). Nothing is wrong with the request; chat is not set up. */
	@ExceptionHandler(com.ie.evalos.integration.ChatUnavailableException.class)
	public ResponseEntity<ApiResponse<Void>> onChatUnavailable(com.ie.evalos.integration.ChatUnavailableException ex) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(ApiResponse.error("CHAT_UNAVAILABLE", ex.getMessage()));
	}

```

- [ ] **Step 3: Remove config** — delete from `application.yml` the whole `  stream:` block under `evalos:` (the comment lines plus `api-key`, `api-secret`, `token-ttl`), and from `docker-compose.yml` the lines:

```yaml

      # Live chat (Unit 56). Empty = chat not set up; the token routes answer 503.
      STREAM_API_KEY: ${STREAM_API_KEY:-}
      STREAM_API_SECRET: ${STREAM_API_SECRET:-}
```

- [ ] **Step 4: Verify nothing references Stream**

Run: `grep -rn "StreamChat\|ChatTokenService\|STREAM_API\|evalos.stream\|ChatUnavailable" backend/src docker-compose.yml`
Expected: no output.

- [ ] **Step 5: Compile and run the suite that guards config**

Run: `./mvnw -q test -Dtest=ConfigSecretsTest,SweepRegistrationTest`
Expected: PASS.

- [ ] **Step 6: Rewrite the Unit 56 docs** — in `.claude/current-decisions.md` replace the whole `- **D50.**` bullet with:

```markdown
- **D50.** **Case chat is an EvalOS-owned service** (Unit 57, `57-case-chat.md`, 2026-09-25,
  business decision; it replaced a Stream Chat setup, Unit 56, that was never committed). Every
  case has three conversations — Client (client, pipeline Sales, PM/Coordinator/Case Manager),
  Internal (pipeline Sales, PM/Coordinator/Case Manager, brand ENMs) and Expert
  (PM/Coordinator/Case Manager, brand ENMs, the expert from offer). EvalOS computes membership from
  assignments and never lets a browser create a conversation or change a member. GM and Brand
  Manager read as viewers. Text only; read-only at `CLOSED`. Spring Boot and PostgreSQL hold every
  message and rule; **Ably relays live updates only** (one private channel per person, publish never
  granted to a browser); web push for anyone without the app open. No chat platform owns the data.
```

In `.claude/open-decisions.md` delete the whole `**Q13 — Live chat: who talks to whom, and who sees what?**` entry (through its `_Gates:_` line) and add to the intro paragraph that already lists resolved items: `Q13 (live chat) was resolved on 2026-09-25 and left for D50.` Delete the `| **Live chat setup (Unit 56)** |` row from `.claude/implementation-status.md`. In `.serena/memories/current_decisions.md` replace the `**D50 (2026-09-25, Unit 56): live chat is Stream Chat.**` paragraph with a three-line summary of the new D50; in `.serena/memories/open_decisions.md` delete item `13.`; in `.serena/memories/implementation_status.md` delete the `**2026-09-25 — Unit 56 live chat SETUP only.**` paragraph.

- [ ] **Step 7: Commit**

```bash
git add -A backend/src docker-compose.yml context/specs .claude .serena
git commit -m "chore(chat): drop the Stream setup; case chat becomes EvalOS-owned (D50)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Schema, entities and repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V69__case_chat.sql`
- Create: the six enums and five entities and five repositories listed in File Structure
- Modify: `backend/src/main/java/com/ie/evalos/domain/AuditAction.java` (add chat actions)
- Test: `backend/src/test/java/com/ie/evalos/repository/LocalPostgresIntegrationTest.java` (add tests)

**Interfaces:**
- Produces enums: `ConversationType { CLIENT, INTERNAL, EXPERT }`, `ConversationStatus { ACTIVE, READ_ONLY }`, `ParticipantKind { STAFF, CLIENT, EXPERT }`, `ChatRole { CLIENT, SALES, PM, COORDINATOR, CASE_MANAGER, ENM, EXPERT }`, `LeftReason { REASSIGNED, OFFER_DECLINED, OFFER_TIMED_OUT, OFFER_SUPERSEDED, PIPELINE_REVOKED, ROLE_CHANGED, DEACTIVATED, ACCOUNT_REMOVED }`, `Reaction { THUMBS_UP, HEART, LAUGH, CELEBRATE, SURPRISED, THANKS }`.
- Produces entities (all `extends ScopedEntity`, so `getId()`, `getBrandId()`, `getCreatedAt()`): `Conversation(brandId, caseId, type)`, `ConversationMember(brandId, conversationId, kind, memberId, role)` with `leave(LeftReason)`, `Message(brandId, conversationId, authorKind, authorId, body, parentMessageId)` with `edit(String)` / `delete()`, `MessageReaction(brandId, messageId, kind, reactorId, reaction)`, `MessageRead`.
- Produces repositories (methods exactly as written in Step 5).
- Produces `AuditAction` values `CHAT_MEMBER_ADDED`, `CHAT_MEMBER_REMOVED`, `CHAT_MESSAGE_EDITED`, `CHAT_MESSAGE_DELETED`, `CHAT_READ_ONLY`.

- [ ] **Step 1: Write the failing Postgres tests** — add to `LocalPostgresIntegrationTest` (it already autowires `JdbcTemplate jdbc` and has seeded brand/case helpers; use the existing seeded case id constant the class uses for other case tests — search the file for a `UUID` constant of a seeded `evalos_case` and reuse it as `SEEDED_CASE`, and the brand constant as `BRAND_IE`):

```java
	@Test
	void aConversationMemberRowCannotBeDeleted() {
		UUID conversation = UUID.randomUUID();
		jdbc.update("INSERT INTO conversations (id, brand_id, case_id, type, status, created_at) "
				+ "VALUES (?, ?, ?, 'INTERNAL', 'ACTIVE', now())", conversation, BRAND_IE, SEEDED_CASE);
		UUID member = UUID.randomUUID();
		jdbc.update("INSERT INTO conversation_members (id, brand_id, conversation_id, member_kind, member_id, "
				+ "member_role, created_at) VALUES (?, ?, ?, 'STAFF', ?, 'PM', now())",
				member, BRAND_IE, conversation, UUID.randomUUID());

		assertThatThrownBy(() -> jdbc.update("DELETE FROM conversation_members WHERE id = ?", member))
				.hasMessageContaining("never deleted");
		jdbc.update("DELETE FROM conversations WHERE id = ?", conversation);
	}

	@Test
	void aMemberRowMayOnlyBeStampedLeftOnce() {
		UUID conversation = UUID.randomUUID();
		jdbc.update("INSERT INTO conversations (id, brand_id, case_id, type, status, created_at) "
				+ "VALUES (?, ?, ?, 'EXPERT', 'ACTIVE', now())", conversation, BRAND_IE, SEEDED_CASE);
		UUID member = UUID.randomUUID();
		jdbc.update("INSERT INTO conversation_members (id, brand_id, conversation_id, member_kind, member_id, "
				+ "member_role, created_at) VALUES (?, ?, ?, 'EXPERT', ?, 'EXPERT', now())",
				member, BRAND_IE, conversation, UUID.randomUUID());

		jdbc.update("UPDATE conversation_members SET left_at = now(), left_reason = 'OFFER_DECLINED' WHERE id = ?", member);
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE conversation_members SET left_at = NULL, left_reason = NULL WHERE id = ?", member))
				.hasMessageContaining("only stamp left_at");
	}

	@Test
	void aCaseHasAtMostOneConversationPerType() {
		UUID first = UUID.randomUUID();
		jdbc.update("INSERT INTO conversations (id, brand_id, case_id, type, status, created_at) "
				+ "VALUES (?, ?, ?, 'CLIENT', 'ACTIVE', now())", first, BRAND_IE, SEEDED_CASE);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO conversations (id, brand_id, case_id, type, status, "
				+ "created_at) VALUES (?, ?, ?, 'CLIENT', 'ACTIVE', now())", UUID.randomUUID(), BRAND_IE, SEEDED_CASE))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}
```

Note: the trigger forbids deleting member rows, so these tests leave rows behind in `evalos_test`. Each test inserts its own conversation with a fresh id and a type the others do not use on `SEEDED_CASE`; `aCaseHasAtMostOneConversationPerType` must use a type no other test inserts (`CLIENT`), and on a re-run it will find its own previous row — so make it idempotent by first running `jdbc.update("DELETE FROM conversations WHERE case_id = ? AND type = 'CLIENT' AND NOT EXISTS (SELECT 1 FROM conversation_members m WHERE m.conversation_id = conversations.id)", SEEDED_CASE);` at its start.

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw -q test -Dtest=LocalPostgresIntegrationTest`
Expected: FAIL — `relation "conversations" does not exist` (or the class is skipped if no local Postgres; then run once Postgres is up — these tests are the proof of the trigger).

- [ ] **Step 3: Write the migration** `V69__case_chat.sql`:

```sql
-- Unit 57 (2026-09-25): case chat. Three conversations per case, membership computed by EvalOS
-- from case assignments, history kept by never deleting a member row. Text only (no attachments).
-- Spec: context/specs/57-case-chat.md.

CREATE TABLE conversations (
    id              uuid        PRIMARY KEY,
    brand_id        uuid        NOT NULL REFERENCES brand (id),
    case_id         uuid        NOT NULL REFERENCES evalos_case (id),
    type            text        NOT NULL CHECK (type IN ('CLIENT', 'INTERNAL', 'EXPERT')),
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'READ_ONLY')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    read_only_at    timestamptz,
    last_message_at timestamptz,
    CONSTRAINT conversations_one_per_type UNIQUE (case_id, type),
    CONSTRAINT conversations_read_only_pair CHECK ((status = 'READ_ONLY') = (read_only_at IS NOT NULL))
);
CREATE INDEX conversations_brand_status_idx ON conversations (brand_id, status);

-- created_at is when the person joined. A current member has left_at NULL.
CREATE TABLE conversation_members (
    id              uuid        PRIMARY KEY,
    brand_id        uuid        NOT NULL REFERENCES brand (id),
    conversation_id uuid        NOT NULL REFERENCES conversations (id),
    member_kind     text        NOT NULL CHECK (member_kind IN ('STAFF', 'CLIENT', 'EXPERT')),
    member_id       uuid        NOT NULL,
    member_role     text        NOT NULL CHECK (member_role IN
                        ('CLIENT', 'SALES', 'PM', 'COORDINATOR', 'CASE_MANAGER', 'ENM', 'EXPERT')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    left_at         timestamptz,
    left_reason     text,
    CONSTRAINT conversation_members_left_pair CHECK ((left_at IS NULL) = (left_reason IS NULL))
);
CREATE UNIQUE INDEX conversation_members_current_idx
    ON conversation_members (conversation_id, member_kind, member_id) WHERE left_at IS NULL;
CREATE INDEX conversation_members_by_member_idx
    ON conversation_members (member_kind, member_id) WHERE left_at IS NULL;

CREATE FUNCTION conversation_members_history_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'conversation_members is membership history: rows are never deleted';
    END IF;
    IF OLD.left_at IS NOT NULL OR NEW.left_at IS NULL
       OR NEW.id <> OLD.id OR NEW.brand_id <> OLD.brand_id OR NEW.conversation_id <> OLD.conversation_id
       OR NEW.member_kind <> OLD.member_kind OR NEW.member_id <> OLD.member_id
       OR NEW.member_role <> OLD.member_role OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'conversation_members: an update may only stamp left_at and left_reason on a current row';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER conversation_members_history_only
    BEFORE UPDATE OR DELETE ON conversation_members
    FOR EACH ROW EXECUTE FUNCTION conversation_members_history_only();

CREATE TABLE messages (
    id                uuid        PRIMARY KEY,
    brand_id          uuid        NOT NULL REFERENCES brand (id),
    conversation_id   uuid        NOT NULL REFERENCES conversations (id),
    author_kind       text        NOT NULL CHECK (author_kind IN ('STAFF', 'CLIENT', 'EXPERT')),
    author_id         uuid        NOT NULL,
    body              text        NOT NULL CHECK (char_length(body) <= 4000),
    parent_message_id uuid        REFERENCES messages (id),
    created_at        timestamptz NOT NULL DEFAULT now(),
    edited_at         timestamptz,
    deleted_at        timestamptz,
    search            tsvector    GENERATED ALWAYS AS (to_tsvector('simple', body)) STORED,
    CONSTRAINT messages_deleted_is_empty CHECK (deleted_at IS NULL OR body = '')
);
CREATE INDEX messages_page_idx ON messages (conversation_id, created_at DESC, id DESC);
CREATE INDEX messages_thread_idx ON messages (parent_message_id, created_at, id) WHERE parent_message_id IS NOT NULL;
CREATE INDEX messages_search_idx ON messages USING gin (search);

CREATE TABLE message_reactions (
    id          uuid        PRIMARY KEY,
    brand_id    uuid        NOT NULL REFERENCES brand (id),
    message_id  uuid        NOT NULL REFERENCES messages (id),
    reactor_kind text       NOT NULL CHECK (reactor_kind IN ('STAFF', 'CLIENT', 'EXPERT')),
    reactor_id  uuid        NOT NULL,
    reaction    text        NOT NULL CHECK (reaction IN
                    ('THUMBS_UP', 'HEART', 'LAUGH', 'CELEBRATE', 'SURPRISED', 'THANKS')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT message_reactions_once UNIQUE (message_id, reactor_kind, reactor_id, reaction)
);

-- The read watermark: one row per member per conversation, moved forward, never back.
CREATE TABLE message_reads (
    id                   uuid        PRIMARY KEY,
    brand_id             uuid        NOT NULL REFERENCES brand (id),
    conversation_id      uuid        NOT NULL REFERENCES conversations (id),
    reader_kind          text        NOT NULL CHECK (reader_kind IN ('STAFF', 'CLIENT', 'EXPERT')),
    reader_id            uuid        NOT NULL,
    last_read_message_id uuid        NOT NULL REFERENCES messages (id),
    last_read_at         timestamptz NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT message_reads_one_per_reader UNIQUE (conversation_id, reader_kind, reader_id)
);

-- Web push (D37). One row per browser a person has allowed notifications in. Used from Task 11.
CREATE TABLE push_subscriptions (
    id              uuid        PRIMARY KEY,
    brand_id        uuid        NOT NULL REFERENCES brand (id),
    subscriber_kind text        NOT NULL CHECK (subscriber_kind IN ('STAFF', 'CLIENT', 'EXPERT')),
    subscriber_id   uuid        NOT NULL,
    endpoint        text        NOT NULL UNIQUE,
    p256dh          text        NOT NULL,
    auth            text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_success_at timestamptz
);
CREATE INDEX push_subscriptions_subscriber_idx ON push_subscriptions (subscriber_kind, subscriber_id);
```

- [ ] **Step 4: Write the enums and entities** in `com.ie.evalos.chat`. Each enum is one file, e.g.:

```java
package com.ie.evalos.chat;

/** The three conversations every case has (Unit 57 §1). */
public enum ConversationType {
	CLIENT, INTERNAL, EXPERT
}
```

The same shape for `ConversationStatus { ACTIVE, READ_ONLY }`, `ParticipantKind { STAFF, CLIENT, EXPERT }`, `ChatRole { CLIENT, SALES, PM, COORDINATOR, CASE_MANAGER, ENM, EXPERT }`, `LeftReason { REASSIGNED, OFFER_DECLINED, OFFER_TIMED_OUT, OFFER_SUPERSEDED, PIPELINE_REVOKED, ROLE_CHANGED, DEACTIVATED, ACCOUNT_REMOVED }`, `Reaction { THUMBS_UP, HEART, LAUGH, CELEBRATE, SURPRISED, THANKS }`.

`Conversation.java`:

```java
package com.ie.evalos.chat;

import java.time.Instant;
import java.util.UUID;

import com.ie.evalos.domain.ScopedEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** One of a case's three conversations. {@code UNIQUE (case_id, type)} keeps it to three. */
@Entity
@Table(name = "conversations")
public class Conversation extends ScopedEntity {

	@Column(name = "case_id", nullable = false, updatable = false)
	private UUID caseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, updatable = false)
	private ConversationType type;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private ConversationStatus status = ConversationStatus.ACTIVE;

	@Column(name = "read_only_at")
	private Instant readOnlyAt;

	@Column(name = "last_message_at")
	private Instant lastMessageAt;

	protected Conversation() {
		// for JPA
	}

	public Conversation(UUID brandId, UUID caseId, ConversationType type) {
		super(brandId);
		this.caseId = caseId;
		this.type = type;
	}

	/** At {@code CLOSED}. Idempotent: a second call keeps the first timestamp. */
	public boolean makeReadOnly(Instant at) {
		if (status == ConversationStatus.READ_ONLY) {
			return false;
		}
		status = ConversationStatus.READ_ONLY;
		readOnlyAt = at;
		return true;
	}

	public void touch(Instant at) {
		lastMessageAt = at;
	}

	public boolean isReadOnly() {
		return status == ConversationStatus.READ_ONLY;
	}

	public UUID getCaseId() { return caseId; }
	public ConversationType getType() { return type; }
	public ConversationStatus getStatus() { return status; }
	public Instant getReadOnlyAt() { return readOnlyAt; }
	public Instant getLastMessageAt() { return lastMessageAt; }
}
```

`ConversationMember.java` (fields `conversationId`, `kind` → `member_kind`, `memberId`, `role` → `member_role`, `leftAt`, `leftReason`; constructor `(UUID brandId, UUID conversationId, ParticipantKind kind, UUID memberId, ChatRole role)`; method `leave(LeftReason reason, Instant at)` that throws `IllegalStateException` if already left; getters incl. `getJoinedAt()` returning `getCreatedAt()`; `isCurrent()` = `leftAt == null`). Mark `conversationId`, `kind`, `memberId`, `role` `updatable = false`.

`Message.java` (fields `conversationId`, `authorKind`, `authorId`, `body`, `parentMessageId`, `editedAt`, `deletedAt`; constructor `(UUID brandId, UUID conversationId, ParticipantKind authorKind, UUID authorId, String body, UUID parentMessageId)`; `edit(String body, Instant at)` sets body and `editedAt`; `delete(Instant at)` sets `body = ""` and `deletedAt`; `isDeleted()`; getters). The `search` column is generated — do **not** map it.

`MessageReaction.java` (fields `messageId`, `reactorKind`, `reactorId`, `reaction`; constructor with all four plus brand).

`MessageRead.java` (fields `conversationId`, `readerKind`, `readerId`, `lastReadMessageId`, `lastReadAt`). Written only through the repository upsert in Step 5; the entity exists for reads.

- [ ] **Step 5: Write the repositories:**

```java
package com.ie.evalos.chat;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

	List<Conversation> findByBrandIdAndCaseId(UUID brandId, UUID caseId);

	Optional<Conversation> findByBrandIdAndCaseIdAndType(UUID brandId, UUID caseId, ConversationType type);

	/** Loaded by id only after the caller's access to it is proved — see ChatAccess. */
	Optional<Conversation> findByIdAndBrandId(UUID id, UUID brandId);

	List<Conversation> findByBrandIdAndCaseIdIn(UUID brandId, Collection<UUID> caseIds);
}
```

```java
public interface ConversationMemberRepository extends JpaRepository<ConversationMember, UUID> {

	List<ConversationMember> findByBrandIdAndConversationIdAndLeftAtIsNull(UUID brandId, UUID conversationId);

	List<ConversationMember> findByBrandIdAndConversationIdInAndLeftAtIsNull(UUID brandId,
			Collection<UUID> conversationIds);

	Optional<ConversationMember> findByBrandIdAndConversationIdAndKindAndMemberIdAndLeftAtIsNull(UUID brandId,
			UUID conversationId, ParticipantKind kind, UUID memberId);

	/**
	 * Inserts a current member unless one exists. {@code ON CONFLICT DO NOTHING} on the partial
	 * index, so a listener and the sweep racing on the same case insert one row, not a 500.
	 * @return 1 if inserted, 0 if already a member
	 */
	@org.springframework.transaction.annotation.Transactional
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
			INSERT INTO conversation_members (id, brand_id, conversation_id, member_kind, member_id, member_role, created_at)
			VALUES (gen_random_uuid(), :brandId, :conversationId, :kind, :memberId, :role, now())
			ON CONFLICT (conversation_id, member_kind, member_id) WHERE left_at IS NULL DO NOTHING
			""")
	int addIfAbsent(@org.springframework.data.repository.query.Param("brandId") UUID brandId,
			@org.springframework.data.repository.query.Param("conversationId") UUID conversationId,
			@org.springframework.data.repository.query.Param("kind") String kind,
			@org.springframework.data.repository.query.Param("memberId") UUID memberId,
			@org.springframework.data.repository.query.Param("role") String role);
}
```

```java
public interface MessageRepository extends JpaRepository<Message, UUID> {

	Optional<Message> findByIdAndBrandId(UUID id, UUID brandId);

	List<Message> findByBrandIdAndParentMessageIdOrderByCreatedAtAscIdAsc(UUID brandId, UUID parentMessageId);
}
```

```java
public interface MessageReactionRepository extends JpaRepository<MessageReaction, UUID> {

	List<MessageReaction> findByBrandIdAndMessageIdIn(UUID brandId, Collection<UUID> messageIds);

	Optional<MessageReaction> findByBrandIdAndMessageIdAndReactorKindAndReactorIdAndReaction(UUID brandId,
			UUID messageId, ParticipantKind kind, UUID reactorId, Reaction reaction);
}
```

```java
public interface MessageReadRepository extends JpaRepository<MessageRead, UUID> {

	List<MessageRead> findByBrandIdAndConversationId(UUID brandId, UUID conversationId);

	/** Moves the watermark forward only: a stale tab cannot un-read a message. */
	@org.springframework.transaction.annotation.Transactional
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
			INSERT INTO message_reads (id, brand_id, conversation_id, reader_kind, reader_id,
			                           last_read_message_id, last_read_at, created_at)
			VALUES (gen_random_uuid(), :brandId, :conversationId, :kind, :readerId, :messageId, :messageAt, now())
			ON CONFLICT (conversation_id, reader_kind, reader_id) DO UPDATE
			  SET last_read_message_id = EXCLUDED.last_read_message_id, last_read_at = EXCLUDED.last_read_at
			  WHERE message_reads.last_read_at < EXCLUDED.last_read_at
			""")
	int advance(@org.springframework.data.repository.query.Param("brandId") UUID brandId,
			@org.springframework.data.repository.query.Param("conversationId") UUID conversationId,
			@org.springframework.data.repository.query.Param("kind") String kind,
			@org.springframework.data.repository.query.Param("readerId") UUID readerId,
			@org.springframework.data.repository.query.Param("messageId") UUID messageId,
			@org.springframework.data.repository.query.Param("messageAt") java.time.Instant messageAt);
}
```

`last_read_at` stores the **message's** `created_at`, not the wall clock, so "unread" is a pure comparison against message timestamps.

- [ ] **Step 6: Add the audit actions** — in `AuditAction.java`, add (anywhere in the enum, each with a one-line Javadoc):

```java
	/** A participant joined a case conversation (Unit 57). System actor: EvalOS computed it. */
	CHAT_MEMBER_ADDED,
	/** A participant left a case conversation (Unit 57); the row is stamped, never deleted. */
	CHAT_MEMBER_REMOVED,
	/** A chat message was edited by its author; `before` holds the previous text (Unit 57 §0 #7). */
	CHAT_MESSAGE_EDITED,
	/** A chat message was deleted by its author; `before` holds the text it had (Unit 57 §0 #7). */
	CHAT_MESSAGE_DELETED,
	/** A case's conversations became read-only because the case closed (Unit 57). */
	CHAT_READ_ONLY,
```

- [ ] **Step 7: Run tests**

Run: `./mvnw -q test -Dtest=LocalPostgresIntegrationTest,MigrationTreeTest,DomainInvariantsTest`
Expected: PASS (Postgres tests pass with a local Postgres; skipped without one).

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "feat(chat): the case-chat schema, entities and repositories (Unit 57, V69)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Membership rules

**Files:**
- Create: `chat/CaseRoster.java`, `chat/ExpectedMember.java`, `chat/ChatMembership.java`, `chat/ChatRosterLoader.java`
- Modify: `repository/ClientAccountRepository.java` (add a finder)
- Test: `backend/src/test/java/com/ie/evalos/chat/ChatMembershipTest.java`, `chat/ChatRosterLoaderTest.java`

**Interfaces:**
- Produces `record CaseRoster(UUID caseId, UUID brandId, UUID clientAccountId, List<UUID> pipelineSales, UUID pm, UUID coordinator, UUID caseManager, List<UUID> enms, UUID expertId)` — nullable single fields, non-null lists.
- Produces `record ExpectedMember(ParticipantKind kind, UUID id, ChatRole role)`.
- Produces `ChatMembership.expected(CaseRoster roster, ConversationType type)` → `List<ExpectedMember>`, de-duplicated by `(kind, id)`, first role wins in the order PM, COORDINATOR, CASE_MANAGER, SALES, ENM (so a case role beats a pipeline or brand role).
- Produces `ChatRosterLoader.load(Case subject)` → `CaseRoster`.
- Produces `ClientAccountRepository.findByBrandIdAndContactId(UUID brandId, UUID contactId)` → `Optional<ClientAccount>`.

- [ ] **Step 1: Write the failing pure test** `ChatMembershipTest.java`:

```java
package com.ie.evalos.chat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMembershipTest {

	private static final UUID CLIENT = UUID.randomUUID();
	private static final UUID SALES = UUID.randomUUID();
	private static final UUID PM = UUID.randomUUID();
	private static final UUID PC = UUID.randomUUID();
	private static final UUID CM = UUID.randomUUID();
	private static final UUID ENM = UUID.randomUUID();
	private static final UUID EXPERT = UUID.randomUUID();

	private static CaseRoster full() {
		return new CaseRoster(UUID.randomUUID(), UUID.randomUUID(), CLIENT, List.of(SALES), PM, PC, CM,
				List.of(ENM), EXPERT);
	}

	@Test
	void clientConversationIsClientSalesAndTheCaseTeam() {
		assertThat(ChatMembership.expected(full(), ConversationType.CLIENT)).containsExactlyInAnyOrder(
				new ExpectedMember(ParticipantKind.CLIENT, CLIENT, ChatRole.CLIENT),
				new ExpectedMember(ParticipantKind.STAFF, SALES, ChatRole.SALES),
				new ExpectedMember(ParticipantKind.STAFF, PM, ChatRole.PM),
				new ExpectedMember(ParticipantKind.STAFF, PC, ChatRole.COORDINATOR),
				new ExpectedMember(ParticipantKind.STAFF, CM, ChatRole.CASE_MANAGER));
	}

	@Test
	void internalConversationIsSalesTheCaseTeamAndEnmsButNeverClientOrExpert() {
		assertThat(ChatMembership.expected(full(), ConversationType.INTERNAL)).containsExactlyInAnyOrder(
				new ExpectedMember(ParticipantKind.STAFF, SALES, ChatRole.SALES),
				new ExpectedMember(ParticipantKind.STAFF, PM, ChatRole.PM),
				new ExpectedMember(ParticipantKind.STAFF, PC, ChatRole.COORDINATOR),
				new ExpectedMember(ParticipantKind.STAFF, CM, ChatRole.CASE_MANAGER),
				new ExpectedMember(ParticipantKind.STAFF, ENM, ChatRole.ENM));
	}

	@Test
	void expertConversationIsTheCaseTeamEnmsAndTheExpertButNeverClientOrSales() {
		assertThat(ChatMembership.expected(full(), ConversationType.EXPERT)).containsExactlyInAnyOrder(
				new ExpectedMember(ParticipantKind.STAFF, PM, ChatRole.PM),
				new ExpectedMember(ParticipantKind.STAFF, PC, ChatRole.COORDINATOR),
				new ExpectedMember(ParticipantKind.STAFF, CM, ChatRole.CASE_MANAGER),
				new ExpectedMember(ParticipantKind.STAFF, ENM, ChatRole.ENM),
				new ExpectedMember(ParticipantKind.EXPERT, EXPERT, ChatRole.EXPERT));
	}

	@Test
	void noMirroredOpportunityMeansNoSales() {
		CaseRoster roster = new CaseRoster(UUID.randomUUID(), UUID.randomUUID(), CLIENT, List.of(), PM, null,
				null, List.of(), null);

		assertThat(ChatMembership.expected(roster, ConversationType.CLIENT)).containsExactlyInAnyOrder(
				new ExpectedMember(ParticipantKind.CLIENT, CLIENT, ChatRole.CLIENT),
				new ExpectedMember(ParticipantKind.STAFF, PM, ChatRole.PM));
	}

	@Test
	void aClientWithNoAccountYetIsSimplyAbsent() {
		CaseRoster roster = new CaseRoster(UUID.randomUUID(), UUID.randomUUID(), null, List.of(), PM, null,
				null, List.of(), null);

		assertThat(ChatMembership.expected(roster, ConversationType.CLIENT))
				.containsExactly(new ExpectedMember(ParticipantKind.STAFF, PM, ChatRole.PM));
	}

	@Test
	void onePersonHoldingTwoRolesIsOneMember() {
		CaseRoster roster = new CaseRoster(UUID.randomUUID(), UUID.randomUUID(), null, List.of(CM), null, null,
				CM, List.of(CM), null);

		assertThat(ChatMembership.expected(roster, ConversationType.INTERNAL))
				.containsExactly(new ExpectedMember(ParticipantKind.STAFF, CM, ChatRole.CASE_MANAGER));
	}
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=ChatMembershipTest`
Expected: FAIL — compilation error, `CaseRoster` / `ChatMembership` do not exist.

- [ ] **Step 3: Implement** `CaseRoster.java`, `ExpectedMember.java`, `ChatMembership.java`:

```java
package com.ie.evalos.chat;

import java.util.List;
import java.util.UUID;

/**
 * Everything membership depends on, for one case, already loaded. Pure data so the rules can be
 * tested without a database. Lists are never null; single ids may be.
 */
public record CaseRoster(UUID caseId, UUID brandId, UUID clientAccountId, List<UUID> pipelineSales,
		UUID pm, UUID coordinator, UUID caseManager, List<UUID> enms, UUID expertId) {

	public CaseRoster {
		pipelineSales = List.copyOf(pipelineSales);
		enms = List.copyOf(enms);
	}
}
```

```java
package com.ie.evalos.chat;

import java.util.UUID;

/** A participant a conversation should have now. */
public record ExpectedMember(ParticipantKind kind, UUID id, ChatRole role) {
}
```

```java
package com.ie.evalos.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who should be in each of a case's conversations (Unit 57 §1). Pure: a roster in, a list out.
 *
 * <p><strong>One person, one row.</strong> Somebody who is both the Case Manager and a pipeline
 * Sales holder is one member, labelled by the case role — the order below adds case roles first and
 * the first label for an id wins.
 */
public final class ChatMembership {

	private ChatMembership() {
	}

	public static List<ExpectedMember> expected(CaseRoster roster, ConversationType type) {
		Map<String, ExpectedMember> byIdentity = new LinkedHashMap<>();
		boolean sales = type == ConversationType.CLIENT || type == ConversationType.INTERNAL;
		boolean enms = type == ConversationType.INTERNAL || type == ConversationType.EXPERT;

		// The case team is in all three, and goes first so a case role outranks SALES or ENM.
		add(byIdentity, ParticipantKind.STAFF, roster.pm(), ChatRole.PM);
		add(byIdentity, ParticipantKind.STAFF, roster.coordinator(), ChatRole.COORDINATOR);
		add(byIdentity, ParticipantKind.STAFF, roster.caseManager(), ChatRole.CASE_MANAGER);
		if (sales) {
			roster.pipelineSales().forEach((id) -> add(byIdentity, ParticipantKind.STAFF, id, ChatRole.SALES));
		}
		if (enms) {
			roster.enms().forEach((id) -> add(byIdentity, ParticipantKind.STAFF, id, ChatRole.ENM));
		}
		if (type == ConversationType.CLIENT) {
			add(byIdentity, ParticipantKind.CLIENT, roster.clientAccountId(), ChatRole.CLIENT);
		}
		if (type == ConversationType.EXPERT) {
			add(byIdentity, ParticipantKind.EXPERT, roster.expertId(), ChatRole.EXPERT);
		}
		return new ArrayList<>(byIdentity.values());
	}

	private static void add(Map<String, ExpectedMember> into, ParticipantKind kind, UUID id, ChatRole role) {
		if (id != null) {
			into.putIfAbsent(kind + ":" + id, new ExpectedMember(kind, id, role));
		}
	}
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q test -Dtest=ChatMembershipTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Write the failing loader test** `ChatRosterLoaderTest.java` (Mockito; the loader is I/O only, so the test checks it reads the right rows):

```java
package com.ie.evalos.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.OpportunityRepository;
import com.ie.evalos.repository.TeamMemberPipelineRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatRosterLoaderTest {

	private final ClientAccountRepository accounts = mock(ClientAccountRepository.class);
	private final OpportunityRepository opportunities = mock(OpportunityRepository.class);
	private final TeamMemberPipelineRepository pipelines = mock(TeamMemberPipelineRepository.class);
	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final ExpertCaseOfferRepository offers = mock(ExpertCaseOfferRepository.class);
	private final ChatRosterLoader loader = new ChatRosterLoader(accounts, opportunities, pipelines, members, offers);

	private static TeamMember member(UUID id, Role role, UUID brand, boolean active) {
		TeamMember m = mock(TeamMember.class);
		when(m.getId()).thenReturn(id);
		when(m.getRole()).thenReturn(role);
		when(m.getBrandId()).thenReturn(brand);
		when(m.isActive()).thenReturn(active);
		return m;
	}

	@Test
	void salesAreActiveSalesHoldersOfTheDealsPipelineInTheCasesBrandOnly() {
		UUID brand = UUID.randomUUID();
		UUID caseId = UUID.randomUUID();
		UUID pipeline = UUID.randomUUID();
		UUID sales = UUID.randomUUID();
		UUID marketing = UUID.randomUUID();
		UUID inactive = UUID.randomUUID();
		UUID otherBrand = UUID.randomUUID();

		Case subject = mock(Case.class);
		when(subject.getId()).thenReturn(caseId);
		when(subject.getBrandId()).thenReturn(brand);
		when(subject.getGhlOpportunityId()).thenReturn("opp_1");
		Opportunity deal = mock(Opportunity.class);
		when(deal.getPipelineId()).thenReturn(pipeline);
		when(opportunities.findByBrandIdAndGhlId(brand, "opp_1")).thenReturn(Optional.of(deal));
		when(pipelines.membersOn(pipeline)).thenReturn(List.of(sales, marketing, inactive, otherBrand));
		when(members.findAllById(List.of(sales, marketing, inactive, otherBrand))).thenReturn(List.of(
				member(sales, Role.SALES, brand, true), member(marketing, Role.MARKETING, brand, true),
				member(inactive, Role.SALES, brand, false), member(otherBrand, Role.SALES, UUID.randomUUID(), true)));
		when(members.findByActiveTrueAndRoleAndBrandId(Role.EXPERT_NETWORK_MANAGER, brand)).thenReturn(List.of());
		when(offers.findByCaseIdAndOutcome(caseId, OfferOutcome.OFFERED)).thenReturn(List.of());
		when(offers.findByCaseIdAndOutcome(caseId, OfferOutcome.ACCEPTED)).thenReturn(List.of());

		assertThat(loader.load(subject).pipelineSales()).containsExactly(sales);
	}

	@Test
	void theExpertIsTheOneWithAnOpenOrAcceptedOffer() {
		UUID brand = UUID.randomUUID();
		UUID caseId = UUID.randomUUID();
		UUID expert = UUID.randomUUID();
		Case subject = mock(Case.class);
		when(subject.getId()).thenReturn(caseId);
		when(subject.getBrandId()).thenReturn(brand);
		ExpertCaseOffer open = mock(ExpertCaseOffer.class);
		when(open.getExpertId()).thenReturn(expert);
		when(offers.findByCaseIdAndOutcome(caseId, OfferOutcome.OFFERED)).thenReturn(List.of(open));
		when(offers.findByCaseIdAndOutcome(caseId, OfferOutcome.ACCEPTED)).thenReturn(List.of());
		when(members.findByActiveTrueAndRoleAndBrandId(Role.EXPERT_NETWORK_MANAGER, brand)).thenReturn(List.of());

		assertThat(loader.load(subject).expertId()).isEqualTo(expert);
	}

	@Test
	void theClientIsTheAccountOfTheCasesContact() {
		UUID brand = UUID.randomUUID();
		UUID contact = UUID.randomUUID();
		UUID account = UUID.randomUUID();
		Case subject = mock(Case.class);
		when(subject.getId()).thenReturn(UUID.randomUUID());
		when(subject.getBrandId()).thenReturn(brand);
		when(subject.getContactId()).thenReturn(contact);
		ClientAccount held = mock(ClientAccount.class);
		when(held.getId()).thenReturn(account);
		when(accounts.findByBrandIdAndContactId(brand, contact)).thenReturn(Optional.of(held));
		when(members.findByActiveTrueAndRoleAndBrandId(Role.EXPERT_NETWORK_MANAGER, brand)).thenReturn(List.of());
		when(offers.findByCaseIdAndOutcome(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
				.thenReturn(List.of());

		assertThat(loader.load(subject).clientAccountId()).isEqualTo(account);
	}
}
```

- [ ] **Step 6: Run to verify it fails** — `./mvnw -q test -Dtest=ChatRosterLoaderTest` → FAIL, `ChatRosterLoader` missing.

- [ ] **Step 7: Add the finder and implement the loader.** In `ClientAccountRepository`:

```java
	/** The portal account on a CRM contact, if the client has made one (Unit 57: the chat's client). */
	Optional<ClientAccount> findByBrandIdAndContactId(UUID brandId, UUID contactId);
```

(`ClientAccount` maps `contact_id` as a property named `contactId` — `getContactId()` exists at `ClientAccount.java:172`; confirm the field name before relying on the derived query.)

`ChatRosterLoader.java`:

```java
package com.ie.evalos.chat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.OpportunityRepository;
import com.ie.evalos.repository.TeamMemberPipelineRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads what {@link ChatMembership} needs for one case. Every read is brand-matched to the case.
 *
 * <p><strong>Pipeline Sales</strong> = active SALES members holding, unrevoked, the pipeline of the
 * case's deal. A case whose deal the mirror does not hold has none — that is a fact, not an error.
 */
@Component
public class ChatRosterLoader {

	private final ClientAccountRepository accounts;
	private final OpportunityRepository opportunities;
	private final TeamMemberPipelineRepository pipelines;
	private final TeamMemberRepository members;
	private final ExpertCaseOfferRepository offers;

	ChatRosterLoader(ClientAccountRepository accounts, OpportunityRepository opportunities,
			TeamMemberPipelineRepository pipelines, TeamMemberRepository members, ExpertCaseOfferRepository offers) {
		this.accounts = accounts;
		this.opportunities = opportunities;
		this.pipelines = pipelines;
		this.members = members;
		this.offers = offers;
	}

	@Transactional(readOnly = true)
	public CaseRoster load(Case subject) {
		UUID brand = subject.getBrandId();
		UUID client = subject.getContactId() == null ? null
				: accounts.findByBrandIdAndContactId(brand, subject.getContactId()).map(ClientAccount::getId)
						.orElse(null);
		List<UUID> enms = members.findByActiveTrueAndRoleAndBrandId(Role.EXPERT_NETWORK_MANAGER, brand).stream()
				.map(TeamMember::getId).toList();
		UUID expert = Stream.concat(offers.findByCaseIdAndOutcome(subject.getId(), OfferOutcome.OFFERED).stream(),
				offers.findByCaseIdAndOutcome(subject.getId(), OfferOutcome.ACCEPTED).stream())
				.map(ExpertCaseOffer::getExpertId).findFirst().orElse(null);
		return new CaseRoster(subject.getId(), brand, client, pipelineSales(subject), subject.getAssignedPm(),
				subject.getAssignedCoordinator(), subject.getAssignedCm(), enms, expert);
	}

	private List<UUID> pipelineSales(Case subject) {
		if (subject.getGhlOpportunityId() == null) {
			return List.of();
		}
		List<UUID> holders = opportunities.findByBrandIdAndGhlId(subject.getBrandId(), subject.getGhlOpportunityId())
				.map(Opportunity::getPipelineId).map(pipelines::membersOn).orElse(List.of());
		if (holders.isEmpty()) {
			return List.of();
		}
		return members.findAllById(holders).stream()
				.filter(TeamMember::isActive)
				.filter((m) -> m.getRole() == Role.SALES)
				.filter((m) -> subject.getBrandId().equals(m.getBrandId()))
				.map(TeamMember::getId).toList();
	}
}
```

- [ ] **Step 8: Run to verify both pass** — `./mvnw -q test -Dtest=ChatMembershipTest,ChatRosterLoaderTest` → PASS.

- [ ] **Step 9: Commit** — `git add backend/src && git commit -m "feat(chat): who belongs in each case conversation (Unit 57 §1)" ` (with the Co-Authored-By trailer).

---

### Task 4: ConversationService — ensure, sync, read-only

**Files:**
- Create: `chat/ConversationService.java`, `chat/ChatChanged.java`
- Test: `chat/ConversationServiceTest.java`; add `concurrentSyncInsertsOneRow` to `LocalPostgresIntegrationTest`

**Interfaces:**
- Consumes: `ChatRosterLoader.load(Case)`, `ChatMembership.expected(...)`, repositories from Task 2, `AuditService.recordSystemEvent(UUID brandId, String objectType, UUID objectId, AuditAction, Object before, Object after)`.
- Produces `ConversationService.ensureAndSync(Case subject)` → `List<Conversation>` (the three, members applied); `ConversationService.makeReadOnly(Case subject)`; published event `record ChatChanged(UUID brandId, UUID conversationId, Kind kind, Object payload)` with `enum Kind { MEMBERS_CHANGED, READ_ONLY, MESSAGE_CREATED, MESSAGE_EDITED, MESSAGE_DELETED, REACTIONS_CHANGED, READ_MOVED }` — Task 10 fans these out and fixes the payload types.

Leave-reason choice when a member drops out of the expected set: `EXPERT` kind → the expert's latest offer outcome (`DECLINED` → `OFFER_DECLINED`, `TIMED_OUT` → `OFFER_TIMED_OUT`, `SUPERSEDED` → `OFFER_SUPERSEDED`, otherwise `REASSIGNED`); `CLIENT` kind → `ACCOUNT_REMOVED`; `STAFF` with role `SALES` → `PIPELINE_REVOKED`; `STAFF` with role `ENM` → `DEACTIVATED`; other staff → `REASSIGNED`. Keep this in one private method `reasonFor(ConversationMember gone, UUID caseId)`.

- [ ] **Step 1: Write the failing unit test** `ConversationServiceTest.java`:

```java
package com.ie.evalos.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.service.AuditService;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

	private final ConversationRepository conversations = mock(ConversationRepository.class);
	private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
	private final ChatRosterLoader roster = mock(ChatRosterLoader.class);
	private final ExpertCaseOfferRepository offers = mock(ExpertCaseOfferRepository.class);
	private final AuditService audit = mock(AuditService.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
	private final ConversationService service =
			new ConversationService(conversations, members, roster, offers, audit, events);

	private final UUID brand = UUID.randomUUID();
	private final UUID caseId = UUID.randomUUID();
	private final UUID pm = UUID.randomUUID();

	private Case subject(Stage stage) {
		Case c = mock(Case.class);
		when(c.getId()).thenReturn(caseId);
		when(c.getBrandId()).thenReturn(brand);
		when(c.getCurrentStage()).thenReturn(stage);
		return c;
	}

	@Test
	void aNewCaseGetsThreeConversationsAndItsMembers() {
		Case c = subject(Stage.DOC_COLLECTION);
		when(conversations.findByBrandIdAndCaseIdAndType(any(), any(), any())).thenReturn(Optional.empty());
		when(conversations.saveAndFlush(any(Conversation.class))).thenAnswer((call) -> call.getArgument(0));
		when(roster.load(c)).thenReturn(new CaseRoster(caseId, brand, null, List.of(), pm, null, null, List.of(), null));
		when(members.findByBrandIdAndConversationIdAndLeftAtIsNull(any(), any())).thenReturn(List.of());
		when(members.addIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

		List<Conversation> made = service.ensureAndSync(c);

		assertThat(made).extracting(Conversation::getType)
				.containsExactlyInAnyOrder(ConversationType.CLIENT, ConversationType.INTERNAL, ConversationType.EXPERT);
		verify(members, times(3)).addIfAbsent(eq(brand), any(), eq("STAFF"), eq(pm), eq("PM"));
		verify(audit, times(3)).recordSystemEvent(eq(brand), eq("CONVERSATION"), any(), eq(AuditAction.CHAT_MEMBER_ADDED),
				any(), any());
	}

	@Test
	void aReassignedCaseManagerLeavesAndTheNewOneJoinsWithHistoryKept() {
		Case c = subject(Stage.DRAFT_IN_PROGRESS);
		UUID oldCm = UUID.randomUUID();
		UUID newCm = UUID.randomUUID();
		Conversation internal = new Conversation(brand, caseId, ConversationType.INTERNAL);
		when(conversations.findByBrandIdAndCaseIdAndType(brand, caseId, ConversationType.INTERNAL))
				.thenReturn(Optional.of(internal));
		when(conversations.findByBrandIdAndCaseIdAndType(brand, caseId, ConversationType.CLIENT)).thenReturn(Optional.of(
				new Conversation(brand, caseId, ConversationType.CLIENT)));
		when(conversations.findByBrandIdAndCaseIdAndType(brand, caseId, ConversationType.EXPERT)).thenReturn(Optional.of(
				new Conversation(brand, caseId, ConversationType.EXPERT)));
		when(roster.load(c)).thenReturn(new CaseRoster(caseId, brand, null, List.of(), null, null, newCm, List.of(), null));
		ConversationMember leaving = new ConversationMember(brand, internal.getId(), ParticipantKind.STAFF, oldCm,
				ChatRole.CASE_MANAGER);
		when(members.findByBrandIdAndConversationIdAndLeftAtIsNull(eq(brand), any())).thenReturn(List.of(leaving));
		when(members.addIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

		service.ensureAndSync(c);

		assertThat(leaving.isCurrent()).isFalse();
		assertThat(leaving.getLeftReason()).isEqualTo(LeftReason.REASSIGNED);
		verify(members, never()).delete(any());
		verify(members, times(3)).addIfAbsent(eq(brand), any(), eq("STAFF"), eq(newCm), eq("CASE_MANAGER"));
	}

	@Test
	void aClosedCaseMakesAllThreeReadOnlyOnce() {
		Case c = subject(Stage.CLOSED);
		Conversation one = new Conversation(brand, caseId, ConversationType.CLIENT);
		when(conversations.findByBrandIdAndCaseId(brand, caseId)).thenReturn(List.of(one));

		service.makeReadOnly(c);
		service.makeReadOnly(c);

		assertThat(one.isReadOnly()).isTrue();
		verify(audit, times(1)).recordSystemEvent(eq(brand), eq("CONVERSATION"), any(), eq(AuditAction.CHAT_READ_ONLY),
				any(), any());
	}
}
```

- [ ] **Step 2: Run to verify it fails** — `./mvnw -q test -Dtest=ConversationServiceTest` → FAIL, `ConversationService` missing.

- [ ] **Step 3: Implement** `ChatChanged.java`:

```java
package com.ie.evalos.chat;

import java.util.UUID;

/**
 * Something about a conversation changed and has committed. Published inside the writing
 * transaction; `ChatFanout` (Task 10) and `ChatPushNotifier` (Task 11) listen AFTER_COMMIT.
 */
public record ChatChanged(UUID brandId, UUID conversationId, Kind kind, Object payload) {

	public enum Kind {
		MEMBERS_CHANGED, READ_ONLY, MESSAGE_CREATED, MESSAGE_EDITED, MESSAGE_DELETED, REACTIONS_CHANGED, READ_MOVED
	}
}
```

`ConversationService.java`:

```java
package com.ie.evalos.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.service.AuditService;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps a case's three conversations in step with the case (Unit 57 §3).
 *
 * <p><strong>Idempotent by construction.</strong> The listener and the hourly sweep both call
 * {@link #ensureAndSync}; conversation creation relies on {@code UNIQUE (case_id, type)} and member
 * inserts on the partial unique index, so a race inserts once and the loser does nothing.
 *
 * <p><strong>Audited as the system.</strong> EvalOS computed the change from an assignment the
 * audit trail already records with its human actor; the chat row is the consequence.
 */
@Service
public class ConversationService {

	private static final String OBJECT_TYPE = "CONVERSATION";

	private final ConversationRepository conversations;
	private final ConversationMemberRepository members;
	private final ChatRosterLoader roster;
	private final ExpertCaseOfferRepository offers;
	private final AuditService audit;
	private final ApplicationEventPublisher events;

	ConversationService(ConversationRepository conversations, ConversationMemberRepository members,
			ChatRosterLoader roster, ExpertCaseOfferRepository offers, AuditService audit,
			ApplicationEventPublisher events) {
		this.conversations = conversations;
		this.members = members;
		this.roster = roster;
		this.offers = offers;
		this.audit = audit;
		this.events = events;
	}

	@Transactional
	public List<Conversation> ensureAndSync(Case subject) {
		CaseRoster loaded = roster.load(subject);
		List<Conversation> all = new ArrayList<>();
		for (ConversationType type : ConversationType.values()) {
			Conversation conversation = ensure(subject, type);
			all.add(conversation);
			if (!conversation.isReadOnly()) {
				sync(conversation, ChatMembership.expected(loaded, type));
			}
		}
		if (subject.getCurrentStage() == Stage.CLOSED) {
			makeReadOnly(subject);
		}
		return all;
	}

	@Transactional
	public void makeReadOnly(Case subject) {
		Instant now = Instant.now();
		for (Conversation conversation : conversations.findByBrandIdAndCaseId(subject.getBrandId(), subject.getId())) {
			if (conversation.makeReadOnly(now)) {
				conversations.save(conversation);
				audit.recordSystemEvent(conversation.getBrandId(), OBJECT_TYPE, conversation.getId(),
						AuditAction.CHAT_READ_ONLY, null, Map.of("caseId", subject.getId()));
				events.publishEvent(new ChatChanged(conversation.getBrandId(), conversation.getId(),
						ChatChanged.Kind.READ_ONLY, null));
			}
		}
	}

	private Conversation ensure(Case subject, ConversationType type) {
		return conversations.findByBrandIdAndCaseIdAndType(subject.getBrandId(), subject.getId(), type)
				.orElseGet(() -> {
					try {
						return conversations.saveAndFlush(new Conversation(subject.getBrandId(), subject.getId(), type));
					}
					catch (DataIntegrityViolationException raced) {
						// The sweep and a listener created it at the same moment; take the winner's.
						return conversations.findByBrandIdAndCaseIdAndType(subject.getBrandId(), subject.getId(), type)
								.orElseThrow(() -> raced);
					}
				});
	}

	private void sync(Conversation conversation, List<ExpectedMember> expected) {
		List<ConversationMember> current =
				members.findByBrandIdAndConversationIdAndLeftAtIsNull(conversation.getBrandId(), conversation.getId());
		Set<String> wanted = expected.stream().map((m) -> m.kind() + ":" + m.id()).collect(Collectors.toSet());
		Set<String> held = current.stream().map((m) -> m.getKind() + ":" + m.getMemberId()).collect(Collectors.toSet());
		boolean changed = false;

		for (ConversationMember member : current) {
			if (!wanted.contains(member.getKind() + ":" + member.getMemberId())) {
				member.leave(reasonFor(member, conversation.getCaseId()), Instant.now());
				members.save(member);
				audit.recordSystemEvent(conversation.getBrandId(), OBJECT_TYPE, conversation.getId(),
						AuditAction.CHAT_MEMBER_REMOVED, Map.of("kind", member.getKind(), "id", member.getMemberId(),
								"role", member.getRole()), Map.of("reason", member.getLeftReason()));
				changed = true;
			}
		}
		for (ExpectedMember member : expected) {
			if (!held.contains(member.kind() + ":" + member.id())
					&& members.addIfAbsent(conversation.getBrandId(), conversation.getId(), member.kind().name(),
							member.id(), member.role().name()) == 1) {
				audit.recordSystemEvent(conversation.getBrandId(), OBJECT_TYPE, conversation.getId(),
						AuditAction.CHAT_MEMBER_ADDED, null,
						Map.of("kind", member.kind(), "id", member.id(), "role", member.role()));
				changed = true;
			}
		}
		if (changed) {
			events.publishEvent(new ChatChanged(conversation.getBrandId(), conversation.getId(),
					ChatChanged.Kind.MEMBERS_CHANGED, null));
		}
	}

	private LeftReason reasonFor(ConversationMember gone, UUID caseId) {
		return switch (gone.getKind()) {
			case CLIENT -> LeftReason.ACCOUNT_REMOVED;
			case EXPERT -> offers.findByCaseIdOrderByOfferedAtDesc(caseId).stream()
					.filter((offer) -> offer.getExpertId().equals(gone.getMemberId()))
					.findFirst().map(ExpertCaseOffer::getOutcome)
					.map((outcome) -> switch (outcome) {
						case DECLINED -> LeftReason.OFFER_DECLINED;
						case TIMED_OUT -> LeftReason.OFFER_TIMED_OUT;
						case SUPERSEDED -> LeftReason.OFFER_SUPERSEDED;
						default -> LeftReason.REASSIGNED;
					}).orElse(LeftReason.REASSIGNED);
			case STAFF -> switch (gone.getRole()) {
				case SALES -> LeftReason.PIPELINE_REVOKED;
				case ENM -> LeftReason.DEACTIVATED;
				default -> LeftReason.REASSIGNED;
			};
		};
	}
}
```

(`OfferOutcome` values are `OFFERED, ACCEPTED, DECLINED, TIMED_OUT, SUPERSEDED` per V19; if the enum names differ, match them. `ConversationMember.getKind()`/`getRole()`/`getLeftReason()` are Task 2 getters.)

- [ ] **Step 4: Run to verify it passes** — `./mvnw -q test -Dtest=ConversationServiceTest` → PASS, 3 tests.

- [ ] **Step 5: Add the race test** to `LocalPostgresIntegrationTest` (autowire `ConversationMemberRepository chatMembers`):

```java
	@Test
	void concurrentSyncInsertsOneRow() {
		UUID conversation = UUID.randomUUID();
		jdbc.update("INSERT INTO conversations (id, brand_id, case_id, type, status, created_at) "
				+ "VALUES (?, ?, ?, 'INTERNAL', 'ACTIVE', now()) ON CONFLICT (case_id, type) DO NOTHING",
				conversation, BRAND_IE, SEEDED_CASE);
		UUID target = jdbc.queryForObject("SELECT id FROM conversations WHERE case_id = ? AND type = 'INTERNAL'",
				UUID.class, SEEDED_CASE);
		UUID person = UUID.randomUUID();

		int first = chatMembers.addIfAbsent(BRAND_IE, target, "STAFF", person, "PM");
		int second = chatMembers.addIfAbsent(BRAND_IE, target, "STAFF", person, "PM");

		assertThat(first + second).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_members WHERE conversation_id = ? "
				+ "AND member_id = ? AND left_at IS NULL", Long.class, target, person)).isEqualTo(1L);
	}
```

- [ ] **Step 6: Run** — `./mvnw -q test -Dtest=ConversationServiceTest,LocalPostgresIntegrationTest` → PASS.

- [ ] **Step 7: Commit** — `feat(chat): conversations follow the case — ensure, sync, read-only (Unit 57 §3)`.

---

### Task 5: Identity and access

**Files:**
- Create: `chat/ChatIdentity.java`, `chat/ChatAccessLevel.java`, `chat/ChatAccess.java`
- Test: `chat/ChatAccessTest.java`

**Interfaces:**
- Produces `record ChatIdentity(ParticipantKind kind, UUID id, UUID brandId, Role staffRole)` with factories `ChatIdentity.staff(TenantContext)` and `ChatIdentity.client(UUID accountId, UUID brandId)` / `ChatIdentity.expert(UUID expertId, UUID brandId)`; `boolean isViewerRole()` = staff with `GM` or `BRAND_MANAGER`.
- Produces `enum ChatAccessLevel { MEMBER, VIEWER, NONE }`.
- Produces `ChatAccess.level(ChatIdentity who, Conversation c)` → `ChatAccessLevel`; `ChatAccess.requireRead(who, conversationId)` → `Conversation` (403 otherwise); `ChatAccess.requireWrite(who, conversationId)` → `Conversation` (403 for NONE/VIEWER, `ConversationReadOnlyException` for read-only). `ConversationReadOnlyException` is created here (Task 7's handler maps it to 409).
- Brand of the conversation lookup: members and viewers are resolved **without** trusting a client-supplied brand. A client/expert identity carries the token's brand; a Brand Manager their own; the GM has none, so for the GM `ChatAccess` loads by id alone (`conversations.findById`) — the only unscoped read, justified because GM is `Tier.ALL`.

- [ ] **Step 1: Write the failing test** `ChatAccessTest.java`:

```java
package com.ie.evalos.chat;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Role;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAccessTest {

	private final ConversationRepository conversations = mock(ConversationRepository.class);
	private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
	private final ChatAccess access = new ChatAccess(conversations, members);

	private final UUID brand = UUID.randomUUID();

	private Conversation conversation(ConversationType type) {
		return new Conversation(brand, UUID.randomUUID(), type);
	}

	private void isMember(ChatIdentity who, boolean yes) {
		when(members.findByBrandIdAndConversationIdAndKindAndMemberIdAndLeftAtIsNull(any(), any(), any(), any()))
				.thenReturn(yes ? Optional.of(mock(ConversationMember.class)) : Optional.empty());
	}

	@Test
	void aCurrentMemberIsAMember() {
		ChatIdentity pm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.PROJECT_MANAGER);
		isMember(pm, true);
		assertThat(access.level(pm, conversation(ConversationType.INTERNAL))).isEqualTo(ChatAccessLevel.MEMBER);
	}

	@Test
	void aFormerMemberHasNoAccess() {
		ChatIdentity pm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.PROJECT_MANAGER);
		isMember(pm, false);
		assertThat(access.level(pm, conversation(ConversationType.INTERNAL))).isEqualTo(ChatAccessLevel.NONE);
	}

	@Test
	void theGmViewsEveryBrand() {
		ChatIdentity gm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), null, Role.GM);
		isMember(gm, false);
		assertThat(access.level(gm, conversation(ConversationType.EXPERT))).isEqualTo(ChatAccessLevel.VIEWER);
	}

	@Test
	void aBrandManagerViewsOnlyTheirBrand() {
		ChatIdentity own = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.BRAND_MANAGER);
		ChatIdentity other = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), UUID.randomUUID(),
				Role.BRAND_MANAGER);
		isMember(own, false);
		assertThat(access.level(own, conversation(ConversationType.CLIENT))).isEqualTo(ChatAccessLevel.VIEWER);
		assertThat(access.level(other, conversation(ConversationType.CLIENT))).isEqualTo(ChatAccessLevel.NONE);
	}

	@Test
	void aClientCanNeverReachInternalOrExpertEvenIfARowSaysSo() {
		ChatIdentity client = ChatIdentity.client(UUID.randomUUID(), brand);
		isMember(client, true);
		assertThat(access.level(client, conversation(ConversationType.INTERNAL))).isEqualTo(ChatAccessLevel.NONE);
		assertThat(access.level(client, conversation(ConversationType.EXPERT))).isEqualTo(ChatAccessLevel.NONE);
		assertThat(access.level(client, conversation(ConversationType.CLIENT))).isEqualTo(ChatAccessLevel.MEMBER);
	}

	@Test
	void anExpertCanOnlyReachExpert() {
		ChatIdentity expert = ChatIdentity.expert(UUID.randomUUID(), brand);
		isMember(expert, true);
		assertThat(access.level(expert, conversation(ConversationType.CLIENT))).isEqualTo(ChatAccessLevel.NONE);
		assertThat(access.level(expert, conversation(ConversationType.EXPERT))).isEqualTo(ChatAccessLevel.MEMBER);
	}

	@Test
	void writingToAReadOnlyConversationIsRefusedAsReadOnly() {
		ChatIdentity pm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.PROJECT_MANAGER);
		Conversation closed = conversation(ConversationType.INTERNAL);
		closed.makeReadOnly(java.time.Instant.now());
		when(conversations.findByIdAndBrandId(any(), any())).thenReturn(Optional.of(closed));
		isMember(pm, true);

		assertThatThrownBy(() -> access.requireWrite(pm, UUID.randomUUID()))
				.isInstanceOf(ConversationReadOnlyException.class);
	}

	@Test
	void aViewerCannotWrite() {
		ChatIdentity gm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), null, Role.GM);
		when(conversations.findById(any())).thenReturn(Optional.of(conversation(ConversationType.CLIENT)));
		isMember(gm, false);

		assertThatThrownBy(() -> access.requireWrite(gm, UUID.randomUUID())).isInstanceOf(ForbiddenException.class);
	}

	@Test
	void anUnknownConversationIsTheSame403AsAForeignOne() {
		ChatIdentity pm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.PROJECT_MANAGER);
		when(conversations.findByIdAndBrandId(any(), any())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> access.requireRead(pm, UUID.randomUUID()))
				.isInstanceOf(ForbiddenException.class).hasMessage(ChatAccess.NOT_YOURS);
	}
}
```

- [ ] **Step 2: Run to verify it fails** — `./mvnw -q test -Dtest=ChatAccessTest` → FAIL.

- [ ] **Step 3: Implement.** `ChatIdentity.java`:

```java
package com.ie.evalos.chat;

import java.util.UUID;

import com.ie.evalos.domain.Role;
import com.ie.evalos.security.TenantContext;

/** Who is calling, on any of the three surfaces. {@code staffRole} is null for a client or expert. */
public record ChatIdentity(ParticipantKind kind, UUID id, UUID brandId, Role staffRole) {

	public static ChatIdentity staff(TenantContext ctx) {
		return new ChatIdentity(ParticipantKind.STAFF, ctx.memberId(), ctx.brandId(), ctx.role());
	}

	public static ChatIdentity client(UUID clientAccountId, UUID brandId) {
		return new ChatIdentity(ParticipantKind.CLIENT, clientAccountId, brandId, null);
	}

	public static ChatIdentity expert(UUID expertId, UUID brandId) {
		return new ChatIdentity(ParticipantKind.EXPERT, expertId, brandId, null);
	}

	public boolean isViewerRole() {
		return staffRole == Role.GM || staffRole == Role.BRAND_MANAGER;
	}
}
```

`ChatAccessLevel.java`: `public enum ChatAccessLevel { MEMBER, VIEWER, NONE }`.

`ConversationReadOnlyException.java`:

```java
package com.ie.evalos.chat;

/** A write to a conversation of a closed case. Answered 409 CONVERSATION_READ_ONLY. */
public class ConversationReadOnlyException extends RuntimeException {

	public ConversationReadOnlyException() {
		super("This case is closed, so its conversation is read-only.");
	}
}
```

`ChatAccess.java`:

```java
package com.ie.evalos.chat;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Role;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one gate every chat read and write passes (Unit 57 §3): every REST route, and typing.
 *
 * <p><strong>Type before membership.</strong> A client reaches CLIENT conversations and an expert
 * EXPERT ones, whatever a member row says — a bad row must not be the only thing between a client
 * and the internal conversation.
 */
@Component
public class ChatAccess {

	public static final String NOT_YOURS = "That conversation is not one you can open.";

	private final ConversationRepository conversations;
	private final ConversationMemberRepository members;

	ChatAccess(ConversationRepository conversations, ConversationMemberRepository members) {
		this.conversations = conversations;
		this.members = members;
	}

	public ChatAccessLevel level(ChatIdentity who, Conversation conversation) {
		if (who.kind() == ParticipantKind.CLIENT && conversation.getType() != ConversationType.CLIENT) {
			return ChatAccessLevel.NONE;
		}
		if (who.kind() == ParticipantKind.EXPERT && conversation.getType() != ConversationType.EXPERT) {
			return ChatAccessLevel.NONE;
		}
		boolean member = members.findByBrandIdAndConversationIdAndKindAndMemberIdAndLeftAtIsNull(
				conversation.getBrandId(), conversation.getId(), who.kind(), who.id()).isPresent();
		if (member) {
			return ChatAccessLevel.MEMBER;
		}
		if (who.staffRole() == Role.GM) {
			return ChatAccessLevel.VIEWER;
		}
		if (who.staffRole() == Role.BRAND_MANAGER && conversation.getBrandId().equals(who.brandId())) {
			return ChatAccessLevel.VIEWER;
		}
		return ChatAccessLevel.NONE;
	}

	@Transactional(readOnly = true)
	public Conversation requireRead(ChatIdentity who, UUID conversationId) {
		Conversation conversation = load(who, conversationId).orElseThrow(() -> new ForbiddenException(NOT_YOURS));
		if (level(who, conversation) == ChatAccessLevel.NONE) {
			throw new ForbiddenException(NOT_YOURS);
		}
		return conversation;
	}

	@Transactional(readOnly = true)
	public Conversation requireWrite(ChatIdentity who, UUID conversationId) {
		Conversation conversation = load(who, conversationId).orElseThrow(() -> new ForbiddenException(NOT_YOURS));
		ChatAccessLevel level = level(who, conversation);
		if (level == ChatAccessLevel.NONE) {
			throw new ForbiddenException(NOT_YOURS);
		}
		if (level == ChatAccessLevel.VIEWER) {
			throw new ForbiddenException("Oversight reads conversations; it does not take part in them.");
		}
		if (conversation.isReadOnly()) {
			throw new ConversationReadOnlyException();
		}
		return conversation;
	}

	/** The GM is Tier.ALL and carries no brand; everyone else is looked up inside their own brand. */
	private Optional<Conversation> load(ChatIdentity who, UUID conversationId) {
		return who.staffRole() == Role.GM ? conversations.findById(conversationId)
				: conversations.findByIdAndBrandId(conversationId, who.brandId());
	}
}
```

- [ ] **Step 4: Run to verify it passes** — `./mvnw -q test -Dtest=ChatAccessTest` → PASS, 9 tests.

- [ ] **Step 5: Commit** — `feat(chat): one gate for every chat read and write (Unit 57 §3)`.

---

### Task 6: Lifecycle listener, the missing event, and the reconcile sweep

**Files:**
- Modify: `event/CaseEvents.java` (add `CASE_MANAGER_REASSIGNED`)
- Modify: `service/CaseLifecycleService.java:306-324` (`reassignCaseManager` publishes it)
- Create: `chat/ChatLifecycleListener.java`, `job/ChatReconcileSweep.java`
- Modify: `backend/src/main/resources/application.yml` (`evalos.jobs.intervals.CHAT_RECONCILE`)
- Test: `chat/ChatLifecycleListenerTest.java`, `job/ChatReconcileSweepTest.java`; `CaseLifecycleServiceTest` gains one assertion

**Interfaces:**
- Consumes: `ConversationService.ensureAndSync(Case)`, `ConversationService.makeReadOnly(Case)`, `CaseRepository.findActiveForSweep(Collection<Stage> terminal)`, `CaseRepository.findById`, `SweepRunner.sweep(String, Supplier<List<T>>, ItemAction<T>)`.
- Produces `CaseEvents.Type.CASE_MANAGER_REASSIGNED("case.case_manager_reassigned")`.

- [ ] **Step 1: Write the failing listener test** `ChatLifecycleListenerTest.java`:

```java
package com.ie.evalos.chat;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatLifecycleListenerTest {

	private final CaseRepository cases = mock(CaseRepository.class);
	private final ConversationService conversations = mock(ConversationService.class);
	private final ChatLifecycleListener listener = new ChatLifecycleListener(cases, conversations);

	private CaseEvents.CaseEvent event(CaseEvents.Type type, UUID caseId) {
		return new CaseEvents.CaseEvent(type, UUID.randomUUID(), caseId, null, null, Stage.DOC_COLLECTION);
	}

	@Test
	void aCreatedCaseGetsItsConversations() {
		UUID id = UUID.randomUUID();
		Case subject = mock(Case.class);
		when(cases.findById(id)).thenReturn(Optional.of(subject));

		listener.on(event(CaseEvents.Type.CASE_CREATED, id));

		verify(conversations).ensureAndSync(subject);
	}

	@Test
	void everyAssignmentEventSyncsMembership() {
		for (CaseEvents.Type type : new CaseEvents.Type[] { CaseEvents.Type.PM_ASSIGNED,
				CaseEvents.Type.COORDINATOR_ASSIGNED, CaseEvents.Type.EXPERT_ASSIGNED, CaseEvents.Type.EXPERT_ACCEPTED,
				CaseEvents.Type.EXPERT_DECLINED, CaseEvents.Type.EXPERT_TIMED_OUT,
				CaseEvents.Type.CASE_MANAGER_REASSIGNED, CaseEvents.Type.CASE_CLOSED }) {
			UUID id = UUID.randomUUID();
			Case subject = mock(Case.class);
			when(cases.findById(id)).thenReturn(Optional.of(subject));
			listener.on(event(type, id));
			verify(conversations).ensureAndSync(subject);
		}
	}

	@Test
	void anUnrelatedEventDoesNothing() {
		listener.on(event(CaseEvents.Type.DRAFT_SUBMITTED, UUID.randomUUID()));
		verify(conversations, never()).ensureAndSync(any());
	}

	@Test
	void aChatFailureIsSwallowedSoTheCaseChangeStands() {
		UUID id = UUID.randomUUID();
		Case subject = mock(Case.class);
		when(cases.findById(id)).thenReturn(Optional.of(subject));
		when(conversations.ensureAndSync(subject)).thenThrow(new IllegalStateException("boom"));

		assertThatCode(() -> listener.on(event(CaseEvents.Type.PM_ASSIGNED, id))).doesNotThrowAnyException();
	}
}
```

- [ ] **Step 2: Run to verify it fails** — `./mvnw -q test -Dtest=ChatLifecycleListenerTest` → FAIL (`CASE_MANAGER_REASSIGNED` and the listener do not exist).

- [ ] **Step 3: Add the event type** — in `CaseEvents.Type`, after `COORDINATOR_ASSIGNED("case.coordinator_assigned"),` add:

```java
		/** The Case Manager changed on a case already under way (Unit 57: the chat follows it). */
		CASE_MANAGER_REASSIGNED("case.case_manager_reassigned"),
```

`NotificationListeners` routes through a map and ignores types it has no route for, so it needs no change. Grep for any `switch` over `CaseEvents.Type` and add a no-op branch if one is exhaustive: `grep -rn "switch (.*type())" backend/src/main/java`.

- [ ] **Step 4: Publish it** — in `CaseLifecycleService.reassignCaseManager`, after the `audit.recordEvent(...)` call and before `return saved;`:

```java
		// Unit 57: the chat membership follows the Case Manager. Until now this method published
		// nothing, so the chat would have learned of the change only on the hourly sweep.
		events.publishEvent(CaseEvents.CaseEvent.of(CaseEvents.Type.CASE_MANAGER_REASSIGNED, saved));
```

And in `CaseLifecycleServiceTest`, in the existing reassign-CM test (search for `reassignCaseManager`), assert the event: `verify(events).publishEvent(argThat((Object e) -> e instanceof CaseEvents.CaseEvent ce && ce.type() == CaseEvents.Type.CASE_MANAGER_REASSIGNED));` — use the test's existing `ApplicationEventPublisher` mock name.

- [ ] **Step 5: Implement the listener** `ChatLifecycleListener.java`:

```java
package com.ie.evalos.chat;

import java.util.EnumSet;
import java.util.Set;

import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Moves a case's conversations when the case moves (Unit 57 §3).
 *
 * <p><strong>After commit, in its own transaction, and never throwing.</strong> A chat problem must
 * not undo an assignment somebody just made; the hourly {@code CHAT_RECONCILE} sweep repairs whatever
 * this misses. {@code fallbackExecution} so an event published outside a transaction still lands.
 */
@Component
public class ChatLifecycleListener {

	private static final Logger log = LoggerFactory.getLogger(ChatLifecycleListener.class);

	static final Set<CaseEvents.Type> MOVES_THE_CHAT = EnumSet.of(CaseEvents.Type.CASE_CREATED,
			CaseEvents.Type.PM_ASSIGNED, CaseEvents.Type.COORDINATOR_ASSIGNED, CaseEvents.Type.EXPERT_ASSIGNED,
			CaseEvents.Type.EXPERT_ACCEPTED, CaseEvents.Type.EXPERT_DECLINED, CaseEvents.Type.EXPERT_TIMED_OUT,
			CaseEvents.Type.CASE_MANAGER_REASSIGNED, CaseEvents.Type.CASE_CLOSED);

	private final CaseRepository cases;
	private final ConversationService conversations;

	ChatLifecycleListener(CaseRepository cases, ConversationService conversations) {
		this.cases = cases;
		this.conversations = conversations;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void on(CaseEvents.CaseEvent event) {
		if (!MOVES_THE_CHAT.contains(event.type())) {
			return;
		}
		try {
			// Unscoped by id, as NotificationListeners does: a listener has no caller whose scope applies.
			cases.findById(event.caseId()).ifPresent(conversations::ensureAndSync);
		}
		catch (RuntimeException failed) {
			log.warn("Chat did not follow {} on case {}; CHAT_RECONCILE will repair it", event.type(),
					event.caseId(), failed);
		}
	}
}
```

`ensureAndSync` already makes a `CLOSED` case read-only, so `CASE_CLOSED` needs no separate branch.

- [ ] **Step 6: Run** — `./mvnw -q test -Dtest=ChatLifecycleListenerTest,CaseLifecycleServiceTest` → PASS.

- [ ] **Step 7: Write the failing sweep test** `job/ChatReconcileSweepTest.java`:

```java
package com.ie.evalos.job;

import java.util.List;
import java.util.Set;

import com.ie.evalos.chat.ConversationService;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.CaseRepository;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatReconcileSweepTest {

	@Test
	void everyCaseNotYetClosedIsEnsuredAndSynced() {
		CaseRepository cases = mock(CaseRepository.class);
		ConversationService conversations = mock(ConversationService.class);
		SweepRunner runner = mock(SweepRunner.class);
		Case open = mock(Case.class);
		when(cases.findActiveForSweep(Set.of(Stage.CLOSED))).thenReturn(List.of(open));
		when(runner.sweep(eq("CHAT_RECONCILE"), any(), any())).thenAnswer((call) -> {
			java.util.function.Supplier<List<Case>> items = call.getArgument(1);
			SweepRunner.ItemAction<Case> act = call.getArgument(2);
			items.get().forEach(act::act);
			return true;
		});

		new ChatReconcileSweep(runner, cases, conversations).run();

		verify(conversations).ensureAndSync(open);
	}
}
```

- [ ] **Step 8: Implement the sweep** `job/ChatReconcileSweep.java` — follow `ContactMirrorSweep`'s shape:

```java
package com.ie.evalos.job;

import java.util.Set;

import com.ie.evalos.chat.ConversationService;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.CaseRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every open case's conversations right (Unit 57 §3). Hourly, on Unit 19's machinery so the
 * admin panel's staleness warning covers it.
 *
 * <p><strong>The floor under the listener.</strong> It is what catches a change no event announces
 * — a revoked pipeline, an ENM activated or deactivated, a client who makes their portal account
 * after the case — and a listener that failed. <strong>Its first run backfills every case that
 * existed before Unit 57.</strong> Cases already CLOSED are left alone: they get no conversations,
 * because nobody can talk in a closed case and backfilling history that never happened is noise.
 */
@Component
public class ChatReconcileSweep implements Sweep {

	static final String JOB_TYPE = "CHAT_RECONCILE";

	private final SweepRunner runner;
	private final CaseRepository cases;
	private final ConversationService conversations;

	ChatReconcileSweep(SweepRunner runner, CaseRepository cases, ConversationService conversations) {
		this.runner = runner;
		this.cases = cases;
		this.conversations = conversations;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.CHAT_RECONCILE}")
	@Override
	public boolean run() {
		return runner.sweep(JOB_TYPE, () -> cases.findActiveForSweep(Set.of(Stage.CLOSED)), (subject) -> {
			conversations.ensureAndSync(subject);
			return true;
		});
	}
}
```

(`findActiveForSweep` returns cases **not** at a terminal stage — confirm in `CaseRepository.java:187`. A case that reaches `CLOSED` is frozen by the listener's `CASE_CLOSED`; if that listener failed, the sweep would no longer see it. So also add to `ConversationRepository` a finder for conversations still `ACTIVE` whose case is `CLOSED`, and freeze those in the same `run()`:

```java
	@org.springframework.data.jpa.repository.Query("""
			SELECT c FROM Conversation c WHERE c.status = com.ie.evalos.chat.ConversationStatus.ACTIVE
			AND EXISTS (SELECT 1 FROM Case k WHERE k.id = c.caseId AND k.currentStage = com.ie.evalos.domain.Stage.CLOSED)
			""")
	List<Conversation> findActiveOfClosedCases();
```

and in `run()`, after the sweep call: `conversations.makeReadOnlyWhereClosed();` — add that method to `ConversationService`, grouping the result by case and calling `makeReadOnly` per case. Add a test to `ChatReconcileSweepTest` asserting `makeReadOnlyWhereClosed()` is called. Confirm the JPQL entity/property names: `Case` entity name and its stage property (`currentStage`).)

- [ ] **Step 9: Register the interval** — in `application.yml` under `evalos.jobs.intervals`, after `CONTACT_MIRROR`:

```yaml
      # Case chat (Unit 57): hourly, the floor under the lifecycle listener — revoked pipelines,
      # ENM changes, late portal accounts, and the first-run backfill of every open case.
      CHAT_RECONCILE: ${JOBS_CHAT_RECONCILE_INTERVAL:1h}
```

- [ ] **Step 10: Run** — `./mvnw -q test -Dtest=ChatReconcileSweepTest,SweepRegistrationTest,ChatLifecycleListenerTest` → PASS.

- [ ] **Step 11: Commit** — `feat(chat): conversations follow case events, and an hourly sweep repairs and backfills (Unit 57)`.

---

### Task 7: MessageService — send, edit, delete, react, read, history, search, unread

**Files:**
- Create: `chat/MessageService.java`, `chat/ChatViews.java`, `chat/ChatInboxQuery.java`, `chat/ChatRateLimiter.java`
- Modify: `common/ApiExceptionHandler.java` (409 for `ConversationReadOnlyException`, 429 for `ChatRateLimiter.TooManyMessagesException`)
- Test: `chat/MessageServiceTest.java`; `pagingIsStableForEqualTimestamps`, `unreadCountsFromTheWatermark`, `searchFindsOnlyReachableConversations` in `LocalPostgresIntegrationTest`

**Interfaces:**
- Consumes: `ChatAccess.requireRead/requireWrite/level`, repositories, `AuditService.recordEvent` (staff) and `recordPortalEvent` (client/expert).
- Produces `ChatViews` records (all public, used by Task 8 controllers and returned as JSON):
  - `record Participant(ParticipantKind kind, UUID id, ChatRole role, String name)`
  - `record MessageView(UUID id, UUID conversationId, ParticipantKind authorKind, UUID authorId, String authorName, String body, UUID parentId, int replyCount, Instant createdAt, Instant editedAt, boolean deleted, Map<Reaction, List<String>> reactions, boolean mine)` — `reactions` maps each reaction to reactor display names.
  - `record ConversationView(UUID id, UUID caseId, String caseCode, String serviceType, String stage, ConversationType type, ConversationStatus status, ChatAccessLevel access, long unread, MessageView lastMessage, List<Participant> participants, Instant lastMessageAt)`
  - `record Page<T>(List<T> items, String nextCursor)` — cursor = `createdAt.toEpochMilli()*1000+micros` is fragile; use `"<ISO instant>|<uuid>"`.
  - `record ReadState(UUID conversationId, List<ReaderMark> readers)` with `record ReaderMark(ParticipantKind kind, UUID id, String name, UUID lastReadMessageId)`.
- Produces `MessageService` methods (every one takes a `ChatIdentity who` first):
  - `Page<ConversationView> inbox(who, UUID caseId, ConversationType type, ConversationStatus status, String cursor, int limit)`
  - `ConversationView conversation(who, UUID conversationId)`
  - `Page<MessageView> messages(who, UUID conversationId, String before, String after, int limit)`
  - `List<MessageView> replies(who, UUID messageId)`
  - `MessageView send(who, UUID conversationId, String body, UUID parentId)`
  - `MessageView edit(who, UUID messageId, String body)`
  - `void delete(who, UUID messageId)`
  - `MessageView react(who, UUID messageId, Reaction reaction, boolean on)`
  - `void markRead(who, UUID conversationId, UUID messageId)`
  - `ReadState readState(who, UUID conversationId)`
  - `List<MessageView> search(who, String q, UUID caseId, ConversationType type, int limit)`
  - `long unreadTotal(who)`

Implementation notes (each is enforced by a test below):
- `send`: `requireWrite`; body `strip()`, 1–4,000 chars else `InvalidRequestException`; `parentId` must be a top-level, non-deleted message **in the same conversation** else `InvalidRequestException` ("Replies are one level deep" / "That message is not in this conversation"); `ChatRateLimiter.check(who)`; save; `conversation.touch(message.getCreatedAt())`; the author's watermark advances to their own message (`reads.advance`); publish `ChatChanged(MESSAGE_CREATED, view)`.
- `edit` / `delete`: load message inside the conversation the caller can write (`requireWrite(who, message.getConversationId())`); author must be `who` (`kind` and `id`) else `ForbiddenException("Only the author can change a message.")`; deleted messages cannot be edited; audit with `before = Map.of("body", previous)` via `recordEvent` for STAFF (actor `who.id()`) or `recordPortalEvent(who.brandId(), CLIENT|EXPERT, ...)` otherwise; publish `MESSAGE_EDITED` / `MESSAGE_DELETED`.
- `react`: `requireWrite`; not on a deleted message; `on=true` inserts (idempotent: an existing reaction is left); `on=false` deletes the row if present. `message_reactions` rows are not history, so deleting them is allowed.
- `markRead`: members only (`level == MEMBER`, viewers get 403); the message must belong to the conversation; `reads.advance(...)` with the message's `createdAt`; publish `READ_MOVED`.
- `messages`: keyset on `(created_at, id)` newest-first for `before`, oldest-first for `after` (reconnect catch-up), top-level only (`parent_message_id IS NULL`), `limit` clamped 1–100 (default 50). Implement with `JdbcTemplate` in `ChatInboxQuery` (below) rather than derived queries, because tuple comparison `(created_at, id) < (?, ?)` is the whole point.
- Names: staff → `team_member.display_name`; client → `first_name || ' ' || last_name` (trimmed; `"Client"` if blank); expert → `expert.full_name`. **A client never receives an expert's name** — guaranteed because a client only reaches CLIENT conversations, which have no expert member; add no special case.
- `inbox`: for a MEMBER-type caller (anyone not GM/BM), conversations joined through their current member rows; for GM all conversations; for BM their brand's. Filters optional. Order by `coalesce(last_message_at, created_at) DESC, id DESC`, keyset cursor, `limit` clamped 1–50.
- `unread` for a member = `count(*)` of messages in the conversation with `author` ≠ reader, `deleted_at IS NULL`, `created_at > watermark.last_read_at` (or all such messages if no watermark). Viewers always 0.
- `search`: `websearch_to_tsquery('simple', ?)` against `messages.search`, limited to conversations the caller can open (same join as the inbox), deleted messages excluded, `limit` clamped 1–50, newest first.

`ChatInboxQuery.java` holds the JDBC for `inbox`, `messages`, `unread`, `search` and name lookup — one class, every statement brand-filtered (GM excepted, by design) and parameterised. Give each method a Javadoc stating its brand rule.

`ChatRateLimiter.java`:

```java
package com.ie.evalos.chat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 30 messages a minute per identity (Unit 57 §8).
 *
 * <p>ponytail: in memory, so it holds for one backend instance — the deployment today. Several
 * instances would need the count in Postgres or Redis.
 */
@Component
public class ChatRateLimiter {

	static final int PER_MINUTE = 30;

	private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();
	private final Clock clock;

	ChatRateLimiter() {
		this(Clock.systemUTC());
	}

	ChatRateLimiter(Clock clock) {
		this.clock = clock;
	}

	public void check(ChatIdentity who) {
		Instant now = clock.instant();
		Deque<Instant> window = recent.computeIfAbsent(who.kind() + ":" + who.id(), (key) -> new ArrayDeque<>());
		synchronized (window) {
			while (!window.isEmpty() && window.peekFirst().isBefore(now.minusSeconds(60))) {
				window.pollFirst();
			}
			if (window.size() >= PER_MINUTE) {
				throw new TooManyMessagesException();
			}
			window.addLast(now);
		}
	}

	public static class TooManyMessagesException extends RuntimeException {
		public TooManyMessagesException() {
			super("That is a lot of messages in a minute. Wait a moment and send again.");
		}
	}
}
```

- [ ] **Step 1: Write the failing unit test** `MessageServiceTest.java` covering, each as its own `@Test` with Mockito mocks of `ChatAccess`, the repositories, `ChatInboxQuery`, `AuditService`, `ApplicationEventPublisher` and a real `ChatRateLimiter` built on a fixed `Clock`:
  - `anEmptyOrWhitespaceBodyIsRefused` — `send(pm, conv, "   ", null)` → `InvalidRequestException`.
  - `aBodyOver4000CharactersIsRefused` — `"x".repeat(4001)` → `InvalidRequestException`; `"x".repeat(4000)` succeeds.
  - `aReplyToAReplyIsRefused` — parent message whose `getParentMessageId()` is non-null → `InvalidRequestException`.
  - `aReplyToAMessageInAnotherConversationIsRefused`.
  - `sendingMovesTheAuthorsOwnWatermark` — verify `reads.advance(brand, conv, "STAFF", pm, savedId, createdAt)`.
  - `onlyTheAuthorCanEdit` — a different identity → `ForbiddenException`.
  - `editKeepsTheOriginalTextInTheAudit` — verify `recordEvent("MESSAGE", id, CHAT_MESSAGE_EDITED, pmId, Map.of("body","old"), Map.of("body","new"))`.
  - `aClientsDeleteIsAuditedAsTheClient` — verify `recordPortalEvent(brand, PortalAudience.CLIENT, "MESSAGE", id, CHAT_MESSAGE_DELETED, Map.of("body","old"), null)`.
  - `aDeletedMessageCannotBeEditedOrReactedTo`.
  - `aViewerCannotWrite` — `access.requireWrite` throws `ForbiddenException` for the GM identity; assert `send`, `react`, `markRead` each propagate it and the message repository's `save` is never called.
  - `the31stMessageInAMinuteIsRefused` — 30 sends succeed, the 31st throws `ChatRateLimiter.TooManyMessagesException`.

  Write each test fully (arrange the mocks, act, assert) following the style of `ConversationServiceTest` in Task 4.

- [ ] **Step 2: Run to verify they fail** — `./mvnw -q test -Dtest=MessageServiceTest` → FAIL.

- [ ] **Step 3: Implement** `ChatViews`, `ChatInboxQuery`, `ChatRateLimiter`, `MessageService` per the interfaces and notes above. The keyset SQL for `messages(before)`:

```sql
SELECT m.id, m.conversation_id, m.author_kind, m.author_id, m.body, m.parent_message_id, m.created_at,
       m.edited_at, m.deleted_at,
       (SELECT count(*) FROM messages r WHERE r.parent_message_id = m.id AND r.deleted_at IS NULL) AS reply_count
  FROM messages m
 WHERE m.brand_id = ? AND m.conversation_id = ? AND m.parent_message_id IS NULL
   AND (?::timestamptz IS NULL OR (m.created_at, m.id) < (?::timestamptz, ?::uuid))
 ORDER BY m.created_at DESC, m.id DESC
 LIMIT ?
```

and for `after` (catch-up) the mirror image with `>` and `ASC`. Parse the cursor `"<instant>|<uuid>"` with `Instant.parse` / `UUID.fromString`; a malformed cursor → `InvalidRequestException("That page cursor is not valid.")`.

- [ ] **Step 4: Map the two exceptions** in `ApiExceptionHandler`:

```java
	@ExceptionHandler(com.ie.evalos.chat.ConversationReadOnlyException.class)
	public ResponseEntity<ApiResponse<Void>> onConversationReadOnly(com.ie.evalos.chat.ConversationReadOnlyException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("CONVERSATION_READ_ONLY", ex.getMessage()));
	}

	@ExceptionHandler(com.ie.evalos.chat.ChatRateLimiter.TooManyMessagesException.class)
	public ResponseEntity<ApiResponse<Void>> onTooManyMessages(com.ie.evalos.chat.ChatRateLimiter.TooManyMessagesException ex) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiResponse.error("TOO_MANY_MESSAGES", ex.getMessage()));
	}
```

- [ ] **Step 5: Run** — `./mvnw -q test -Dtest=MessageServiceTest` → PASS.

- [ ] **Step 6: Add the Postgres tests** to `LocalPostgresIntegrationTest` (autowire `ChatInboxQuery chatQuery`; insert rows with `jdbc` directly into a fresh conversation on `SEEDED_CASE` using a type chosen so the test does not collide — create the conversation with `ON CONFLICT (case_id, type) DO NOTHING` then select its id, and give messages a unique marker word in the body so assertions only count this run's rows):
  - `pagingIsStableForEqualTimestamps` — insert 5 messages with the **same** `created_at`; page with `limit 2` three times using each `nextCursor`; assert the 5 ids are returned exactly once each, in `(created_at DESC, id DESC)` order.
  - `unreadCountsFromTheWatermark` — 3 messages from another author, watermark on the first → unread 2; the reader's own messages never count; a deleted message never counts.
  - `searchFindsOnlyReachableConversations` — a message containing the marker in a conversation where the caller is a member is found; the same marker in a conversation where they are not a member is not.

- [ ] **Step 7: Run** — `./mvnw -q test -Dtest=LocalPostgresIntegrationTest` → PASS (with a local Postgres).

- [ ] **Step 8: Commit** — `feat(chat): messages, threads, reactions, reads, search and unread counts (Unit 57 §3)`.

---

### Task 8: REST on three surfaces

**Files:**
- Create: `chat/ChatApi.java`, `web/StaffChatController.java`, `web/ClientChatController.java`, `web/ExpertChatController.java`
- Modify: `security/PortalSecurityConfig.java` (add `PUT` to allowed methods and its comment)
- Modify: `backend/src/test/java/com/ie/evalos/web/ClientApplicationRoutesTest.java` (PUT back in the allowed list; only PATCH refused)
- Test: `web/StaffChatControllerTest.java`, `web/PortalChatControllerTest.java`

**Interfaces:**
- Consumes: every `MessageService` method from Task 7; `ChatIdentity` factories; `PortalPrincipal.current(PortalAudience)`; `ClientAccountRepository.findByBrandIdAndGhlContactId`.
- Produces routes (each surface prefix + these suffixes): `GET conversations`, `GET conversations/{id}`, `GET conversations/{id}/messages`, `GET conversations/{id}/read-state`, `GET messages/{id}/replies`, `POST conversations/{id}/messages`, `PUT messages/{id}`, `DELETE messages/{id}`, `PUT messages/{id}/reactions/{reaction}`, `DELETE messages/{id}/reactions/{reaction}`, `POST conversations/{id}/read`, `GET search`, `GET unread`. Prefixes: `/api/chat`, `/api/portal/client/chat`, `/api/portal/expert/chat`.
- `ChatApi` is a plain class (not a controller) holding the route bodies once; each controller resolves its `ChatIdentity` and delegates. Request records: `record SendRequest(@NotBlank @Size(max = 4000) String body, UUID parentId)`, `record EditRequest(@NotBlank @Size(max = 4000) String body)`, `record ReadRequest(@NotNull UUID messageId)`.
- Client identity: `principal.namesAnAccountDirectly() ? principal.clientAccountId() : accounts.findByBrandIdAndGhlContactId(principal.brandId(), principal.ghlContactId()).map(ClientAccount::getId).orElseThrow(() -> new ForbiddenException("This link does not admit you to that"))` — the same two arms `ClientApplicationService.account` uses. Expert identity: `principal.expertId()` or 403 if null.
- Staff routes carry no `@PreAuthorize` role list: every staff role may call them and `ChatAccess` decides per conversation. Add a class Javadoc saying so, because the codebase's convention is a role list and a reader will look for one.

- [ ] **Step 1: Write the failing controller tests.** `StaffChatControllerTest` (copy the `@WebMvcTest` / `@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })` / `bearer(Role)` setup from `OpportunityNoteControllerTest`; `@MockitoBean MessageService messages`):
  - `anUnauthenticatedCallerIs401` — `GET /api/chat/conversations` with no header → 401.
  - `theInboxIsTheServicesAnswerForTheCallersIdentity` — PM bearer → 200; verify `messages.inbox(argThat(who -> who.kind() == STAFF && who.staffRole() == PROJECT_MANAGER), isNull(), isNull(), isNull(), isNull(), eq(50))`.
  - `sendingPassesBodyAndParent` — `POST /api/chat/conversations/{id}/messages` `{"body":"hi","parentId":null}` → 200 and `send(...)` called with `"hi"`.
  - `aReadOnlyConversationIs409` — `send` throws `ConversationReadOnlyException` → 409, `$.error.code` = `CONVERSATION_READ_ONLY`.
  - `aBlankBodyIs400` — `{"body":"  "}` → 400 (bean validation; `@NotBlank`).
  - `anUnknownReactionIs400` — `PUT /api/chat/messages/{id}/reactions/FIRE` → 400.

  `PortalChatControllerTest` (copy `ClientApplicationRoutesTest`'s `@WebMvcTest` + portal security setup):
  - `everyPortalChatRouteRefusesAnUnauthenticatedCaller` — `GET /api/portal/client/chat/conversations` and `GET /api/portal/expert/chat/conversations` → 401.

- [ ] **Step 2: Run to verify they fail** — `./mvnw -q test -Dtest=StaffChatControllerTest,PortalChatControllerTest` → FAIL.

- [ ] **Step 3: Implement** `ChatApi` and the three controllers. `StaffChatController`:

```java
package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.chat.ChatApi;
import com.ie.evalos.chat.ChatIdentity;
import com.ie.evalos.chat.ChatViews;
import com.ie.evalos.chat.ConversationStatus;
import com.ie.evalos.chat.ConversationType;
import com.ie.evalos.chat.Reaction;
import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.security.TenantContext;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Case chat for staff (Unit 57 §4).
 *
 * <p><strong>No role list, on purpose.</strong> Every other controller here names its roles; this
 * one cannot, because who may read a conversation is membership, decided per conversation by
 * {@code ChatAccess}. A role with no conversations simply gets an empty inbox.
 */
@RestController
@RequestMapping("/api/chat")
public class StaffChatController {

	private final ChatApi api;

	StaffChatController(ChatApi api) {
		this.api = api;
	}

	private static ChatIdentity who() {
		return ChatIdentity.staff(TenantContext.current());
	}

	@GetMapping("/conversations")
	public ApiResponse<ChatViews.Page<ChatViews.ConversationView>> inbox(@RequestParam(required = false) UUID caseId,
			@RequestParam(required = false) ConversationType type, @RequestParam(required = false) ConversationStatus status,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
		return ApiResponse.ok(api.inbox(who(), caseId, type, status, cursor, limit));
	}

	@GetMapping("/conversations/{id}")
	public ApiResponse<ChatViews.ConversationView> conversation(@PathVariable UUID id) {
		return ApiResponse.ok(api.conversation(who(), id));
	}

	@GetMapping("/conversations/{id}/messages")
	public ApiResponse<ChatViews.Page<ChatViews.MessageView>> messages(@PathVariable UUID id,
			@RequestParam(required = false) String before, @RequestParam(required = false) String after,
			@RequestParam(defaultValue = "50") int limit) {
		return ApiResponse.ok(api.messages(who(), id, before, after, limit));
	}

	@GetMapping("/conversations/{id}/read-state")
	public ApiResponse<ChatViews.ReadState> readState(@PathVariable UUID id) {
		return ApiResponse.ok(api.readState(who(), id));
	}

	@GetMapping("/messages/{id}/replies")
	public ApiResponse<List<ChatViews.MessageView>> replies(@PathVariable UUID id) {
		return ApiResponse.ok(api.replies(who(), id));
	}

	@PostMapping("/conversations/{id}/messages")
	public ApiResponse<ChatViews.MessageView> send(@PathVariable UUID id, @Valid @RequestBody ChatApi.SendRequest request) {
		return ApiResponse.ok(api.send(who(), id, request));
	}

	@PutMapping("/messages/{id}")
	public ApiResponse<ChatViews.MessageView> edit(@PathVariable UUID id, @Valid @RequestBody ChatApi.EditRequest request) {
		return ApiResponse.ok(api.edit(who(), id, request));
	}

	@DeleteMapping("/messages/{id}")
	public ApiResponse<Void> delete(@PathVariable UUID id) {
		api.delete(who(), id);
		return ApiResponse.ok(null);
	}

	@PutMapping("/messages/{id}/reactions/{reaction}")
	public ApiResponse<ChatViews.MessageView> react(@PathVariable UUID id, @PathVariable Reaction reaction) {
		return ApiResponse.ok(api.react(who(), id, reaction, true));
	}

	@DeleteMapping("/messages/{id}/reactions/{reaction}")
	public ApiResponse<ChatViews.MessageView> unreact(@PathVariable UUID id, @PathVariable Reaction reaction) {
		return ApiResponse.ok(api.react(who(), id, reaction, false));
	}

	@PostMapping("/conversations/{id}/read")
	public ApiResponse<Void> read(@PathVariable UUID id, @Valid @RequestBody ChatApi.ReadRequest request) {
		api.read(who(), id, request);
		return ApiResponse.ok(null);
	}

	@GetMapping("/search")
	public ApiResponse<List<ChatViews.MessageView>> search(@RequestParam String q,
			@RequestParam(required = false) UUID caseId, @RequestParam(required = false) ConversationType type,
			@RequestParam(defaultValue = "25") int limit) {
		return ApiResponse.ok(api.search(who(), q, caseId, type, limit));
	}

	@GetMapping("/unread")
	public ApiResponse<Long> unread() {
		return ApiResponse.ok(api.unread(who()));
	}
}
```

`ClientChatController` (`@RequestMapping("/api/portal/client/chat")`) and `ExpertChatController` (`@RequestMapping("/api/portal/expert/chat")`) are the same class body with `who()` replaced by the portal identity resolution described in Interfaces (the client one needs `ClientAccountRepository` injected). Write both out in full — do not share a base class; three thin controllers with one shared `ChatApi` is the pattern, matching `ChatApi`'s purpose.

`ChatApi`: `@Component`, delegates each method to `MessageService`, holds the three request records, and unwraps request records into the service's parameters (`request.body()`, `request.parentId()`, `request.messageId()`).

A `Reaction` path value that is not an enum constant fails conversion → Spring answers 400 through `MethodArgumentTypeMismatchException`, which `ApiExceptionHandler` already maps (line 75).

- [ ] **Step 4: Allow PUT on the portal again** — in `PortalSecurityConfig`, set `config.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));` and change the comment's PUT sentence to: `PUT left with the questionnaire's autosave (Unit 55) and came back with case chat (Unit 57): editing a message and reacting to one.` In `ClientApplicationRoutesTest`, set the allowed loop to `{ "GET", "POST", "PUT", "DELETE" }` and the refused parameter list to `{ "PATCH" }`, updating both Javadocs in one sentence each.

- [ ] **Step 5: Run** — `./mvnw -q test -Dtest=StaffChatControllerTest,PortalChatControllerTest,ClientApplicationRoutesTest` → PASS.

- [ ] **Step 6: Run the full backend suite** — `./mvnw -q test` → PASS, 0 failures (previous baseline 1,179 plus this unit's tests).

- [ ] **Step 7: Commit** — `feat(chat): case chat over REST for staff, clients and experts (Unit 57 §4)`.

---

### Task 9: Ably gateway and realtime tokens

**Files:**
- Modify: `backend/pom.xml` (add `io.ably:ably-java:1.2.53` — confirm it is still the latest 1.x on Maven Central)
- Create: `chat/live/AblySettings.java`, `chat/live/ChatRealtime.java`, `chat/live/AblyToken.java`, `chat/live/RealtimeUnavailableException.java`, `chat/live/ChatChannels.java`
- Modify: `chat/ChatApi.java` and the three chat controllers (a `GET realtime/token` route each)
- Modify: `common/ApiExceptionHandler.java` (503 `REALTIME_UNAVAILABLE`)
- Modify: `backend/src/main/resources/application.yml` (`evalos.ably.api-key`, `evalos.chat.staff-origin`), `docker-compose.yml` (`ABLY_API_KEY`)
- Test: `chat/live/ChatChannelsTest.java`, `chat/live/ChatRealtimeTest.java`

**Interfaces:**
- Consumes: `ChatIdentity` (Task 5).
- Produces `ChatChannels` (pure, static):
  - `personal(ParticipantKind kind, UUID id)` → `"chat:user:" + kind + ":" + id` — one private channel per person, the only channel a member's token may subscribe to.
  - `view(UUID brandId, UUID conversationId)` → `"chat:view:" + brandId + ":" + conversationId` — what a viewer (GM, Brand Manager) listens to for the conversation they have open.
  - `clientId(ChatIdentity who)` → `who.kind() + ":" + who.id()`.
  - `capability(ChatIdentity who)` → JSON: members get `{"chat:user:<kind>:<id>":["subscribe","presence"]}`; a Brand Manager also gets `"chat:view:<brandId>:*":["subscribe"]`; the GM also gets `"chat:view:*":["subscribe"]`. **No token can ever publish.**
- Produces `ChatRealtime` (the only class that talks to Ably):
  - `boolean enabled()`
  - `AblyToken token(ChatIdentity who)` → `record AblyToken(String keyName, String clientId, String capability, long ttl, long timestamp, String nonce, String mac)` — exactly the fields of an Ably TokenRequest, which ably-js accepts as the `authUrl`/`authCallback` answer.
  - `void publish(String channel, String event, Object data)` — serialises `data` to JSON with Jackson; logs and swallows Ably failures (the database already holds the change; a client catches up over REST).
  - `boolean isPresent(String channel)` — true if anyone is in the channel's presence set.
- Produces the route `GET realtime/token` on `/api/chat`, `/api/portal/client/chat`, `/api/portal/expert/chat`.

Why per-person channels: recipients are chosen **by the backend, per event, from current membership** (Task 10). A token that can only subscribe to its own channel means nobody can join a conversation channel by guessing its name, and a reassigned person stops receiving the moment they leave — no token revocation, no capability refresh.

Token lifetime: one hour; ably-js refreshes it through the same route before expiry without dropping the connection. Because capabilities are per person rather than per conversation, a membership change never needs a new token.

- [ ] **Step 1: Write the failing pure test** `ChatChannelsTest.java`:

```java
package com.ie.evalos.chat.live;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.chat.ChatIdentity;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.domain.Role;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatChannelsTest {

	private static JsonNode capability(ChatIdentity who) throws Exception {
		return new ObjectMapper().readTree(ChatChannels.capability(who));
	}

	@Test
	void aMemberMayOnlySubscribeToTheirOwnChannelAndNeverPublish() throws Exception {
		UUID pm = UUID.randomUUID();
		JsonNode caps = capability(new ChatIdentity(ParticipantKind.STAFF, pm, UUID.randomUUID(), Role.PROJECT_MANAGER));

		assertThat(caps.size()).isEqualTo(1);
		JsonNode own = caps.get("chat:user:STAFF:" + pm);
		assertThat(own).isNotNull();
		assertThat(own.toString()).contains("subscribe").contains("presence").doesNotContain("publish");
	}

	@Test
	void aClientGetsTheirOwnChannelOnly() throws Exception {
		UUID account = UUID.randomUUID();
		JsonNode caps = capability(ChatIdentity.client(account, UUID.randomUUID()));

		assertThat(caps.fieldNames()).toIterable().containsExactly("chat:user:CLIENT:" + account);
	}

	@Test
	void aBrandManagerMayWatchTheirBrandsConversationsOnly() throws Exception {
		UUID brand = UUID.randomUUID();
		JsonNode caps = capability(new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.BRAND_MANAGER));

		assertThat(caps.get("chat:view:" + brand + ":*").toString()).isEqualTo("[\"subscribe\"]");
		assertThat(caps.has("chat:view:*")).isFalse();
	}

	@Test
	void theGmMayWatchEveryConversation() throws Exception {
		JsonNode caps = capability(new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), null, Role.GM));

		assertThat(caps.get("chat:view:*").toString()).isEqualTo("[\"subscribe\"]");
	}

	@Test
	void theClientIdIsKindAndId() {
		UUID id = UUID.randomUUID();
		assertThat(ChatChannels.clientId(ChatIdentity.expert(id, UUID.randomUUID()))).isEqualTo("EXPERT:" + id);
		assertThat(ChatChannels.personal(ParticipantKind.EXPERT, id)).isEqualTo("chat:user:EXPERT:" + id);
	}
}
```

- [ ] **Step 2: Run to verify it fails** — `./mvnw -q test -Dtest=ChatChannelsTest` → FAIL (class missing).

- [ ] **Step 3: Implement** `ChatChannels.java`:

```java
package com.ie.evalos.chat.live;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.chat.ChatIdentity;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.domain.Role;

/**
 * Ably channel names and token capabilities for case chat (Unit 57 §5, D50).
 *
 * <p><strong>One private channel per person, and no token may publish.</strong> The backend decides
 * who receives each event and publishes it into each recipient's own channel, so a capability never
 * names a conversation and never has to change when membership does.
 */
public final class ChatChannels {

	private static final ObjectMapper JSON = new ObjectMapper();

	private ChatChannels() {
	}

	public static String personal(ParticipantKind kind, UUID id) {
		return "chat:user:" + kind + ":" + id;
	}

	public static String view(UUID brandId, UUID conversationId) {
		return "chat:view:" + brandId + ":" + conversationId;
	}

	public static String clientId(ChatIdentity who) {
		return who.kind() + ":" + who.id();
	}

	public static String capability(ChatIdentity who) {
		Map<String, List<String>> caps = new LinkedHashMap<>();
		caps.put(personal(who.kind(), who.id()), List.of("subscribe", "presence"));
		if (who.staffRole() == Role.GM) {
			caps.put("chat:view:*", List.of("subscribe"));
		}
		else if (who.staffRole() == Role.BRAND_MANAGER && who.brandId() != null) {
			caps.put("chat:view:" + who.brandId() + ":*", List.of("subscribe"));
		}
		try {
			return JSON.writeValueAsString(caps);
		}
		catch (JsonProcessingException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
```

- [ ] **Step 4: Run** — `./mvnw -q test -Dtest=ChatChannelsTest` → PASS, 5 tests.

- [ ] **Step 5: Write the failing gateway test** `ChatRealtimeTest.java` — the unconfigured path, which needs no network:

```java
package com.ie.evalos.chat.live;

import java.util.UUID;

import com.ie.evalos.chat.ChatIdentity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRealtimeTest {

	private final ChatRealtime off = new ChatRealtime(new AblySettings(""));

	@Test
	void withNoKeyRealtimeIsOffAndSaysSo() {
		assertThat(off.enabled()).isFalse();
		assertThatThrownBy(() -> off.token(ChatIdentity.client(UUID.randomUUID(), UUID.randomUUID())))
				.isInstanceOf(RealtimeUnavailableException.class);
	}

	@Test
	void publishingWithRealtimeOffIsANoOpNotAFailure() {
		assertThatCode(() -> off.publish("chat:user:STAFF:" + UUID.randomUUID(), "message.created", "{}"))
				.doesNotThrowAnyException();
		assertThat(off.isPresent("chat:user:STAFF:" + UUID.randomUUID())).isFalse();
	}

	@Test
	void aConfiguredKeyMintsATokenRequestForTheCallerAlone() {
		// A syntactically valid key; createTokenRequest signs locally and makes no network call.
		ChatRealtime on = new ChatRealtime(new AblySettings("appid.keyid:secretsecretsecret"));
		UUID account = UUID.randomUUID();

		AblyToken token = on.token(ChatIdentity.client(account, UUID.randomUUID()));

		assertThat(token.clientId()).isEqualTo("CLIENT:" + account);
		assertThat(token.capability()).contains("chat:user:CLIENT:" + account).doesNotContain("publish");
		assertThat(token.keyName()).isEqualTo("appid.keyid");
		assertThat(token.mac()).isNotBlank();
	}
}
```

- [ ] **Step 6: Run to verify it fails** — `./mvnw -q test -Dtest=ChatRealtimeTest` → FAIL.

- [ ] **Step 7: Add the dependency and config, then implement.** `pom.xml`:

```xml
		<dependency>
			<groupId>io.ably</groupId>
			<artifactId>ably-java</artifactId>
			<version>1.2.53</version>
		</dependency>
```

`application.yml`, under `evalos:`:

```yaml
  ably:
    # Real-time delivery for case chat (Unit 57, D50): Ably relays, PostgreSQL keeps the record.
    # A credential - never defaulted. Empty = no live updates; chat still works over REST.
    api-key: ${ABLY_API_KEY:}
  chat:
    # The staff app's origin, used to build the link a staff push notification opens.
    staff-origin: ${EVALOS_STAFF_ORIGIN:http://localhost:5173}
```

`docker-compose.yml`, backend `environment:`: `ABLY_API_KEY: ${ABLY_API_KEY:-}` and `EVALOS_STAFF_ORIGIN: ${EVALOS_STAFF_ORIGIN:-}`.

`AblySettings.java`:

```java
package com.ie.evalos.chat.live;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The Ably API key. Empty = realtime off (a startup warning, not a failed boot). */
@Component
public class AblySettings {

	private final String apiKey;

	AblySettings(@Value("${evalos.ably.api-key:}") String apiKey) {
		this.apiKey = apiKey == null ? "" : apiKey.trim();
	}

	public boolean enabled() {
		return !apiKey.isEmpty();
	}

	public String apiKey() {
		return apiKey;
	}
}
```

`RealtimeUnavailableException.java`:

```java
package com.ie.evalos.chat.live;

/** No Ably key here. Answered 503 REALTIME_UNAVAILABLE; the apps fall back to polling REST. */
public class RealtimeUnavailableException extends RuntimeException {

	public RealtimeUnavailableException() {
		super("Live updates are not set up here (ABLY_API_KEY). Chat still works; refresh to see new messages.");
	}
}
```

`AblyToken.java`:

```java
package com.ie.evalos.chat.live;

/** An Ably TokenRequest, field for field — ably-js takes this as the authUrl / authCallback answer. */
public record AblyToken(String keyName, String clientId, String capability, long ttl, long timestamp,
		String nonce, String mac) {
}
```

`ChatRealtime.java`:

```java
package com.ie.evalos.chat.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ie.evalos.chat.ChatIdentity;

import io.ably.lib.rest.AblyRest;
import io.ably.lib.rest.Auth;
import io.ably.lib.types.AblyException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only class that talks to Ably (Unit 57 §5, D50).
 *
 * <p><strong>Ably relays; it is never the record.</strong> Every change is already committed in
 * PostgreSQL when it is published here, so a failed publish is logged and dropped: the recipient's
 * app catches up over REST on its next reconnect.
 */
@Component
public class ChatRealtime {

	private static final Logger log = LoggerFactory.getLogger(ChatRealtime.class);
	private static final long TOKEN_TTL_MS = 60 * 60 * 1000L;
	private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	private final AblyRest rest;

	ChatRealtime(AblySettings settings) {
		AblyRest built = null;
		if (settings.enabled()) {
			try {
				built = new AblyRest(settings.apiKey());
			}
			catch (AblyException invalid) {
				throw new IllegalStateException("ABLY_API_KEY is not a valid Ably key", invalid);
			}
		}
		else {
			log.warn("No ABLY_API_KEY configured - case chat works over REST without live updates.");
		}
		this.rest = built;
	}

	public boolean enabled() {
		return rest != null;
	}

	public AblyToken token(ChatIdentity who) {
		if (rest == null) {
			throw new RealtimeUnavailableException();
		}
		Auth.TokenParams params = new Auth.TokenParams();
		params.clientId = ChatChannels.clientId(who);
		params.capability = ChatChannels.capability(who);
		params.ttl = TOKEN_TTL_MS;
		try {
			Auth.TokenRequest request = rest.auth.createTokenRequest(params, null);
			return new AblyToken(request.keyName, request.clientId, request.capability, request.ttl,
					request.timestamp, request.nonce, request.mac);
		}
		catch (AblyException failed) {
			throw new RealtimeUnavailableException();
		}
	}

	public void publish(String channel, String event, Object data) {
		if (rest == null) {
			return;
		}
		try {
			rest.channels.get(channel).publish(event, data instanceof String s ? s : JSON.writeValueAsString(data));
		}
		catch (AblyException | JsonProcessingException failed) {
			log.warn("Ably publish of {} to {} failed; the recipient catches up over REST", event, channel, failed);
		}
	}

	public boolean isPresent(String channel) {
		if (rest == null) {
			return false;
		}
		try {
			return rest.channels.get(channel).presence.get(null).items().length > 0;
		}
		catch (AblyException failed) {
			// Unknown is treated as offline: a spare push beats a missed one.
			log.warn("Ably presence check on {} failed", channel, failed);
			return false;
		}
	}
}
```

(Confirm the ably-java 1.2.x names used above against its Javadoc: `AblyRest(String key)`, `rest.auth.createTokenRequest(Auth.TokenParams, Auth.AuthOptions)` returning `Auth.TokenRequest` with public fields `keyName, clientId, capability, ttl, timestamp, nonce, mac`, `rest.channels.get(name).publish(String, Object)`, and `channel.presence.get(Param[])` returning `PaginatedResult<PresenceMessage>` with `items()`. If `createTokenRequest` wants non-null `AuthOptions`, pass `new Auth.AuthOptions()`.)

- [ ] **Step 8: The token route and the 503.** Add to `ChatApi`: `public AblyToken realtimeToken(ChatIdentity who) { return realtime.token(who); }` (inject `ChatRealtime`). Add to each of the three controllers:

```java
	/** ably-js calls this through authCallback, and again before the token expires. */
	@GetMapping("/realtime/token")
	public ApiResponse<com.ie.evalos.chat.live.AblyToken> realtimeToken() {
		return ApiResponse.ok(api.realtimeToken(who()));
	}
```

In `ApiExceptionHandler`:

```java
	@ExceptionHandler(com.ie.evalos.chat.live.RealtimeUnavailableException.class)
	public ResponseEntity<ApiResponse<Void>> onRealtimeUnavailable(com.ie.evalos.chat.live.RealtimeUnavailableException ex) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error("REALTIME_UNAVAILABLE", ex.getMessage()));
	}
```

The apps use `authCallback` (not `authUrl`) so the call carries each app's own credential header through its existing API client, and unwrap `ApiResponse.data` before handing the TokenRequest to ably-js.

- [ ] **Step 9: Run** — `./mvnw -q test -Dtest=ChatChannelsTest,ChatRealtimeTest,ConfigSecretsTest,StaffChatControllerTest` → PASS. Add to `StaffChatControllerTest`: `theRealtimeTokenIsTheServicesAnswer` (200, `$.data.clientId`) and `realtimeOffIs503` (`$.error.code` = `REALTIME_UNAVAILABLE`).

- [ ] **Step 10: Commit** — `feat(chat): Ably realtime tokens — one private channel per person, publish never granted (Unit 57, D50)`.

---

### Task 10: Live fan-out, typing and presence over Ably

**Files:**
- Modify: `chat/ChatChanged.java` (payload types), `chat/ConversationService.java` (members-changed payload)
- Create: `chat/MembersChanged.java`, `chat/live/ChatEnvelope.java`, `chat/live/ChatFanout.java`, `chat/live/ChatPresence.java`, `chat/live/ChatTyping.java`
- Modify: `chat/ChatApi.java` and the three controllers (`POST conversations/{id}/typing`)
- Test: `chat/live/ChatFanoutTest.java`, `chat/live/ChatPresenceTest.java`, `chat/live/ChatTypingTest.java`

**Interfaces:**
- Consumes: `ChatChanged` (Task 4), `ConversationMemberRepository.findByBrandIdAndConversationIdAndLeftAtIsNull`, `ChatAccess.requireWrite`, `ChatRealtime.publish/isPresent`, `ChatChannels.personal/view`.
- Produces the envelope every app receives as the **data** of an Ably message whose **name** is the envelope `type`: `record ChatEnvelope(String type, UUID conversationId, Object data)`, `type` ∈ `message.created`, `message.edited`, `message.deleted`, `reactions.changed`, `read.moved`, `members.changed`, `conversation.read_only`, `access.granted`, `access.revoked`, `typing`, `unread.changed`. **Phases 2–3 code against exactly these strings.**
- Produces `ChatChanged` payloads: `MESSAGE_*`, `REACTIONS_CHANGED` → `ChatViews.MessageView`; `READ_MOVED` → `ChatViews.ReaderMark`; `MEMBERS_CHANGED` → `record MembersChanged(List<ExpectedMember> added, List<ExpectedMember> removed)`; `READ_ONLY` → null.
- Produces `ChatPresence.isOnline(ParticipantKind kind, UUID id)` → boolean (Task 11 uses it).
- Produces `POST conversations/{id}/typing` on each surface → 204-style `ApiResponse.ok(null)`.

Presence model: each app, once connected, **enters presence on its own personal channel** (its token grants `presence` there). "Online" = anyone present on that person's channel, read by the backend with `ChatRealtime.isPresent`. Online status for the participant list is not broadcast in Phase 1 — the apps read it on demand through `GET presence?ids=` (Task 7 route, now backed by `ChatPresence`); a live online dot is added in phase 2 if wanted. This keeps presence traffic to one enter and one leave per session.

Fan-out rules (unchanged from the spec):
- Recipients are read **per event** from current members, so a removed member receives nothing after removal.
- Every member, author included, gets `message.created` (the author's other tabs need it); everyone except the author also gets `unread.changed`.
- `MEMBERS_CHANGED`: `access.granted` to each added person, `access.revoked` to each removed person, `members.changed` to everyone still in.
- Every event except `typing` and `unread.changed` is also published once to the conversation's view channel for viewers.
- `typing` goes to current members except the sender, **throttled to one per 3 seconds** per sender per conversation (every Ably message is billed), never stored, never to viewers.

- [ ] **Step 1: Write the failing tests.**

`ChatFanoutTest.java`:

```java
package com.ie.evalos.chat.live;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.chat.ChatChanged;
import com.ie.evalos.chat.ChatRole;
import com.ie.evalos.chat.ChatViews;
import com.ie.evalos.chat.ConversationMember;
import com.ie.evalos.chat.ConversationMemberRepository;
import com.ie.evalos.chat.ExpectedMember;
import com.ie.evalos.chat.MembersChanged;
import com.ie.evalos.chat.ParticipantKind;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatFanoutTest {

	private final ChatRealtime realtime = mock(ChatRealtime.class);
	private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
	private final ChatFanout fanout = new ChatFanout(realtime, members);

	private final UUID brand = UUID.randomUUID();
	private final UUID conversation = UUID.randomUUID();
	private final UUID pm = UUID.randomUUID();
	private final UUID client = UUID.randomUUID();

	private ChatViews.MessageView fromPm() {
		return new ChatViews.MessageView(UUID.randomUUID(), conversation, ParticipantKind.STAFF, pm, "Priya", "hi",
				null, 0, Instant.now(), null, false, Map.of(), false);
	}

	private void twoMembers() {
		when(members.findByBrandIdAndConversationIdAndLeftAtIsNull(brand, conversation)).thenReturn(List.of(
				new ConversationMember(brand, conversation, ParticipantKind.STAFF, pm, ChatRole.PM),
				new ConversationMember(brand, conversation, ParticipantKind.CLIENT, client, ChatRole.CLIENT)));
	}

	@Test
	void aNewMessageGoesToEachMembersOwnChannelAndTheViewChannel() {
		twoMembers();

		fanout.on(new ChatChanged(brand, conversation, ChatChanged.Kind.MESSAGE_CREATED, fromPm()));

		verify(realtime).publish(eq("chat:user:STAFF:" + pm), eq("message.created"), any(ChatEnvelope.class));
		verify(realtime).publish(eq("chat:user:CLIENT:" + client), eq("message.created"), any(ChatEnvelope.class));
		verify(realtime).publish(eq("chat:view:" + brand + ":" + conversation), eq("message.created"),
				any(ChatEnvelope.class));
	}

	@Test
	void theAuthorGetsNoUnreadBumpButEveryoneElseDoes() {
		twoMembers();

		fanout.on(new ChatChanged(brand, conversation, ChatChanged.Kind.MESSAGE_CREATED, fromPm()));

		verify(realtime).publish(eq("chat:user:CLIENT:" + client), eq("unread.changed"), any());
		verify(realtime, never()).publish(eq("chat:user:STAFF:" + pm), eq("unread.changed"), any());
	}

	@Test
	void aRemovedMemberIsToldAccessIsRevokedAndGetsNothingElse() {
		UUID oldCm = UUID.randomUUID();
		twoMembers();

		fanout.on(new ChatChanged(brand, conversation, ChatChanged.Kind.MEMBERS_CHANGED, new MembersChanged(List.of(),
				List.of(new ExpectedMember(ParticipantKind.STAFF, oldCm, ChatRole.CASE_MANAGER)))));

		verify(realtime).publish(eq("chat:user:STAFF:" + oldCm), eq("access.revoked"), any());
		verify(realtime, never()).publish(eq("chat:user:STAFF:" + oldCm), eq("members.changed"), any());
		verify(realtime).publish(eq("chat:user:STAFF:" + pm), eq("members.changed"), any());
	}

	@Test
	void theEnvelopeCarriesTheConversationAndTheView() {
		twoMembers();
		ChatViews.MessageView message = fromPm();

		fanout.on(new ChatChanged(brand, conversation, ChatChanged.Kind.MESSAGE_CREATED, message));

		verify(realtime).publish(eq("chat:user:CLIENT:" + client), eq("message.created"),
				argThat((ChatEnvelope e) -> e.conversationId().equals(conversation) && e.data() == message));
	}
}
```

`ChatPresenceTest.java`:

```java
package com.ie.evalos.chat.live;

import java.util.UUID;

import com.ie.evalos.chat.ParticipantKind;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatPresenceTest {

	@Test
	void onlineMeansPresentOnTheirOwnChannel() {
		ChatRealtime realtime = mock(ChatRealtime.class);
		UUID client = UUID.randomUUID();
		when(realtime.isPresent("chat:user:CLIENT:" + client)).thenReturn(true);

		assertThat(new ChatPresence(realtime).isOnline(ParticipantKind.CLIENT, client)).isTrue();
	}

	@Test
	void aRecentAnswerIsReusedForTenSeconds() {
		ChatRealtime realtime = mock(ChatRealtime.class);
		UUID pm = UUID.randomUUID();
		when(realtime.isPresent("chat:user:STAFF:" + pm)).thenReturn(false);
		ChatPresence presence = new ChatPresence(realtime);

		presence.isOnline(ParticipantKind.STAFF, pm);
		presence.isOnline(ParticipantKind.STAFF, pm);

		verify(realtime, times(1)).isPresent("chat:user:STAFF:" + pm);
	}
}
```

`ChatTypingTest.java`:

```java
package com.ie.evalos.chat.live;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.chat.ChatAccess;
import com.ie.evalos.chat.ChatIdentity;
import com.ie.evalos.chat.ChatRole;
import com.ie.evalos.chat.Conversation;
import com.ie.evalos.chat.ConversationMember;
import com.ie.evalos.chat.ConversationMemberRepository;
import com.ie.evalos.chat.ConversationType;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Role;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTypingTest {

	private final ChatRealtime realtime = mock(ChatRealtime.class);
	private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
	private final ChatAccess access = mock(ChatAccess.class);
	private final ChatTyping typing = new ChatTyping(realtime, members, access);

	private final UUID brand = UUID.randomUUID();
	private final UUID pm = UUID.randomUUID();
	private final UUID client = UUID.randomUUID();
	private final ChatIdentity who = new ChatIdentity(ParticipantKind.STAFF, pm, brand, Role.PROJECT_MANAGER);

	private UUID conversation() {
		Conversation c = new Conversation(brand, UUID.randomUUID(), ConversationType.CLIENT);
		UUID id = UUID.randomUUID();
		when(access.requireWrite(who, id)).thenReturn(c);
		when(members.findByBrandIdAndConversationIdAndLeftAtIsNull(eq(brand), any())).thenReturn(List.of(
				new ConversationMember(brand, id, ParticipantKind.STAFF, pm, ChatRole.PM),
				new ConversationMember(brand, id, ParticipantKind.CLIENT, client, ChatRole.CLIENT)));
		return id;
	}

	@Test
	void typingReachesTheOtherMembersButNotTheTypist() {
		typing.typing(who, conversation());

		verify(realtime).publish(eq("chat:user:CLIENT:" + client), eq("typing"), any());
		verify(realtime, never()).publish(eq("chat:user:STAFF:" + pm), eq("typing"), any());
	}

	@Test
	void typingIsThrottledToOncePerThreeSeconds() {
		UUID id = conversation();

		typing.typing(who, id);
		typing.typing(who, id);

		verify(realtime, times(1)).publish(eq("chat:user:CLIENT:" + client), eq("typing"), any());
	}

	@Test
	void aViewerOrStrangerCannotSignalTyping() {
		UUID id = UUID.randomUUID();
		when(access.requireWrite(who, id)).thenThrow(new ForbiddenException(ChatAccess.NOT_YOURS));

		assertThatThrownBy(() -> typing.typing(who, id)).isInstanceOf(ForbiddenException.class);
		verify(realtime, never()).publish(any(), any(), any());
	}
}
```

- [ ] **Step 2: Run to verify they fail** — `./mvnw -q test -Dtest=ChatFanoutTest,ChatPresenceTest,ChatTypingTest` → FAIL.

- [ ] **Step 3: The members-changed payload.** Create `chat/MembersChanged.java`:

```java
package com.ie.evalos.chat;

import java.util.List;

/** Who joined and who left in one sync — the fan-out tells each of them. */
public record MembersChanged(List<ExpectedMember> added, List<ExpectedMember> removed) {
}
```

In `ConversationService.sync`, collect `added` (each `ExpectedMember` inserted) and `removed` (each leaver as `new ExpectedMember(member.getKind(), member.getMemberId(), member.getRole())`) and publish `new ChatChanged(brand, id, ChatChanged.Kind.MEMBERS_CHANGED, new MembersChanged(added, removed))` instead of the null payload. Extend `ConversationServiceTest.aReassignedCaseManagerLeavesAndTheNewOneJoinsWithHistoryKept` to capture the published `ChatChanged` for the INTERNAL conversation and assert one removed (the old CM) and one added (the new CM).

- [ ] **Step 4: Implement** `ChatEnvelope.java`:

```java
package com.ie.evalos.chat.live;

import java.util.UUID;

/** The data of every chat Ably message; the Ably message name is {@code type}. The contract for phases 2–3. */
public record ChatEnvelope(String type, UUID conversationId, Object data) {
}
```

`ChatFanout.java`:

```java
package com.ie.evalos.chat.live;

import java.util.List;

import com.ie.evalos.chat.ChatChanged;
import com.ie.evalos.chat.ChatViews;
import com.ie.evalos.chat.ConversationMember;
import com.ie.evalos.chat.ConversationMemberRepository;
import com.ie.evalos.chat.ExpectedMember;
import com.ie.evalos.chat.MembersChanged;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes committed chat changes into each current member's private Ably channel (Unit 57 §5).
 *
 * <p><strong>Recipients are read per event from current membership</strong>, so somebody reassigned
 * off a case receives nothing after they leave — their token never named the conversation.
 */
@Component
public class ChatFanout {

	private final ChatRealtime realtime;
	private final ConversationMemberRepository members;

	ChatFanout(ChatRealtime realtime, ConversationMemberRepository members) {
		this.realtime = realtime;
		this.members = members;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void on(ChatChanged change) {
		if (!realtime.enabled()) {
			return;
		}
		List<ConversationMember> current =
				members.findByBrandIdAndConversationIdAndLeftAtIsNull(change.brandId(), change.conversationId());
		switch (change.kind()) {
			case MESSAGE_CREATED -> {
				ChatViews.MessageView message = (ChatViews.MessageView) change.payload();
				ChatEnvelope created = new ChatEnvelope("message.created", change.conversationId(), message);
				ChatEnvelope unread = new ChatEnvelope("unread.changed", change.conversationId(), null);
				for (ConversationMember member : current) {
					send(member, created);
					boolean author = member.getKind() == message.authorKind() && member.getMemberId().equals(message.authorId());
					if (!author) {
						send(member, unread);
					}
				}
				view(change, created);
			}
			case MESSAGE_EDITED -> everyone(current, change, "message.edited");
			case MESSAGE_DELETED -> everyone(current, change, "message.deleted");
			case REACTIONS_CHANGED -> everyone(current, change, "reactions.changed");
			case READ_MOVED -> everyone(current, change, "read.moved");
			case READ_ONLY -> everyone(current, change, "conversation.read_only");
			case MEMBERS_CHANGED -> {
				MembersChanged diff = (MembersChanged) change.payload();
				diff.added().forEach((m) -> sendTo(m, new ChatEnvelope("access.granted", change.conversationId(), null)));
				diff.removed().forEach((m) -> sendTo(m, new ChatEnvelope("access.revoked", change.conversationId(), null)));
				everyone(current, change, "members.changed");
			}
		}
	}

	private void everyone(List<ConversationMember> current, ChatChanged change, String type) {
		ChatEnvelope envelope = new ChatEnvelope(type, change.conversationId(), change.payload());
		current.forEach((member) -> send(member, envelope));
		view(change, envelope);
	}

	private void send(ConversationMember member, ChatEnvelope envelope) {
		realtime.publish(ChatChannels.personal(member.getKind(), member.getMemberId()), envelope.type(), envelope);
	}

	private void sendTo(ExpectedMember member, ChatEnvelope envelope) {
		realtime.publish(ChatChannels.personal(member.kind(), member.id()), envelope.type(), envelope);
	}

	private void view(ChatChanged change, ChatEnvelope envelope) {
		realtime.publish(ChatChannels.view(change.brandId(), change.conversationId()), envelope.type(), envelope);
	}
}
```

(`ChatFanoutTest` builds `ChatRealtime` as a mock, whose `enabled()` returns false by default — add `when(realtime.enabled()).thenReturn(true);` to each test's arrange step.)

`ChatPresence.java`:

```java
package com.ie.evalos.chat.live;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.ie.evalos.chat.ParticipantKind;

import org.springframework.stereotype.Component;

/**
 * Whether a person has an app open: present on their own Ably channel (Unit 57 §5–6).
 *
 * <p>Answers are cached ten seconds, so a burst of messages to one conversation asks Ably once per
 * recipient, not once per message.
 */
@Component
public class ChatPresence {

	private static final long CACHE_SECONDS = 10;

	private record Answer(boolean online, Instant at) {
	}

	private final Map<String, Answer> recent = new ConcurrentHashMap<>();
	private final ChatRealtime realtime;

	ChatPresence(ChatRealtime realtime) {
		this.realtime = realtime;
	}

	public boolean isOnline(ParticipantKind kind, UUID id) {
		String channel = ChatChannels.personal(kind, id);
		Answer cached = recent.get(channel);
		if (cached != null && cached.at().isAfter(Instant.now().minusSeconds(CACHE_SECONDS))) {
			return cached.online();
		}
		boolean online = realtime.isPresent(channel);
		recent.put(channel, new Answer(online, Instant.now()));
		return online;
	}
}
```

`ChatTyping.java`:

```java
package com.ie.evalos.chat.live;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.ie.evalos.chat.ChatAccess;
import com.ie.evalos.chat.ChatIdentity;
import com.ie.evalos.chat.Conversation;
import com.ie.evalos.chat.ConversationMemberRepository;

import org.springframework.stereotype.Component;

/**
 * "Somebody is typing", relayed by the backend because no token may publish (Unit 57 §5).
 * Throttled to one per three seconds per person per conversation — every Ably message is billed.
 * Never stored.
 */
@Component
public class ChatTyping {

	private static final long EVERY_SECONDS = 3;

	private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();
	private final ChatRealtime realtime;
	private final ConversationMemberRepository members;
	private final ChatAccess access;

	ChatTyping(ChatRealtime realtime, ConversationMemberRepository members, ChatAccess access) {
		this.realtime = realtime;
		this.members = members;
		this.access = access;
	}

	public void typing(ChatIdentity who, UUID conversationId) {
		Conversation conversation = access.requireWrite(who, conversationId);
		String key = ChatChannels.clientId(who) + "@" + conversationId;
		Instant now = Instant.now();
		Instant previous = lastSent.get(key);
		if (previous != null && previous.isAfter(now.minusSeconds(EVERY_SECONDS))) {
			return;
		}
		lastSent.put(key, now);
		ChatEnvelope envelope = new ChatEnvelope("typing", conversationId,
				Map.of("kind", who.kind(), "id", who.id()));
		members.findByBrandIdAndConversationIdAndLeftAtIsNull(conversation.getBrandId(), conversationId).stream()
				.filter((m) -> !(m.getKind() == who.kind() && m.getMemberId().equals(who.id())))
				.forEach((m) -> realtime.publish(ChatChannels.personal(m.getKind(), m.getMemberId()), "typing", envelope));
	}
}
```

(`requireWrite` already refuses viewers, strangers and read-only conversations, so typing obeys the same rules as sending.)

Add to `ChatApi` `public void typing(ChatIdentity who, UUID conversationId) { typingRelay.typing(who, conversationId); }` and to each controller:

```java
	@PostMapping("/conversations/{id}/typing")
	public ApiResponse<Void> typing(@PathVariable UUID id) {
		api.typing(who(), id);
		return ApiResponse.ok(null);
	}
```

Back Task 7's `GET presence?ids=` with `ChatPresence.isOnline` for each requested participant of a conversation the caller can read (ignore ids that are not participants of it).

- [ ] **Step 5: Run** — `./mvnw -q test -Dtest=ChatFanoutTest,ChatPresenceTest,ChatTypingTest,ConversationServiceTest` → PASS.

- [ ] **Step 6: A live check against a real Ably app (manual, once).** With `ABLY_API_KEY` set to a **development** Ably app, start the backend with the local profile, open Ably's dashboard dev console on channel `chat:user:STAFF:<a PM's team_member id>`, send a message as another member of one of that PM's case conversations through `POST /api/chat/conversations/{id}/messages`, and confirm a `message.created` message appears on the channel within a second. Record the result in the commit message. (No automated test calls Ably: it would need a real key in CI.)

- [ ] **Step 7: Commit** — `feat(chat): live delivery through each member's private Ably channel, typing and presence (Unit 57 §5)`.

---

### Task 11: Web push — subscriptions and sending

**Files:**
- Modify: `backend/pom.xml` (add `nl.martijndwars:web-push:5.1.2`; BouncyCastle only if not transitive — Step 3)
- (Schema: `push_subscriptions` is already in `V69__case_chat.sql`, Task 2.)
- Create: `chat/push/PushSubscription.java`, `chat/push/PushSubscriptionRepository.java`, `chat/push/PushSettings.java`, `chat/push/PushSender.java`, `chat/push/ChatPushNotifier.java`
- Modify: `StaffChatController`, `ClientChatController`, `ExpertChatController` (three push routes each, delegating through `ChatApi`)
- Modify: `application.yml` (`evalos.push.*`)
- Test: `chat/push/ChatPushNotifierTest.java`, `chat/push/PushSenderTest.java`

**Interfaces:**
- Consumes: `ChatChanged(MESSAGE_CREATED, MessageView)`, `ConversationMemberRepository`, `ConversationRepository`, `CaseRepository` (for `caseCode`), `ChatPresence.isOnline(kind, id)`, `ChatIdentity`.
- Produces routes on each surface: `GET push/public-key` → `{ "publicKey": "<base64url>" }` or 404 `PUSH_UNAVAILABLE` when unconfigured; `POST push/subscriptions` `{ endpoint, keys: { p256dh, auth } }`; `DELETE push/subscriptions` `{ endpoint }`.
- Produces `PushSender.send(PushSubscription s, String json)` → `enum Outcome { SENT, GONE, FAILED }`.
- Produces the push payload JSON the phase-2/3 service workers read: `{ "title": "...", "body": "...", "url": "...", "tag": "<conversationId>" }`.

Rules (spec §6):
- On `MESSAGE_CREATED`, for each current member **except the author** with **no open session** (`!presence.isOnline`), send to every subscription they hold.
- Title `New message from <author name>` — to a CLIENT recipient, a staff author is `Your case team`. Body: `<case code> · <Client|Internal|Expert> conversation`. Never the message text.
- URL: staff → `<staff-origin>/cases/<caseId>?chat=<conversationId>`; client → `<client-base-url>/cases/<caseId>`; expert → `<expert-base-url>/case`.
- Sent on a dedicated 2-thread executor, off the request and off the transaction.
- `GONE` (404/410) deletes the subscription; `FAILED` is logged and kept.
- Unconfigured VAPID → `PushSettings.enabled() == false`, a startup warning, and the notifier does nothing.

Schema: `push_subscriptions` is created by Task 2's `V69__case_chat.sql`.

GM staff have a null brand; a staff subscription stores the member's own brand when they have one and **the GM is never a chat member**, so every row that ever gets a push has a brand. Store the staff member's `brandId`; refuse `POST push/subscriptions` for a staff identity with no brand (403 "Oversight does not take part in conversations, so there is nothing to notify you about.").

- [ ] **Step 1: Write the failing notifier test** `ChatPushNotifierTest.java`:

```java
package com.ie.evalos.chat.push;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.chat.ChatChanged;
import com.ie.evalos.chat.ChatRole;
import com.ie.evalos.chat.ChatViews;
import com.ie.evalos.chat.Conversation;
import com.ie.evalos.chat.ConversationMember;
import com.ie.evalos.chat.ConversationMemberRepository;
import com.ie.evalos.chat.ConversationRepository;
import com.ie.evalos.chat.ConversationType;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.chat.live.ChatPresence;
import com.ie.evalos.domain.Case;
import com.ie.evalos.repository.CaseRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatPushNotifierTest {

	private final PushSettings settings = new PushSettings("pub", "priv", "mailto:ops@ie.test",
			"http://staff.test", "http://client.test", "http://expert.test");
	private final PushSubscriptionRepository subscriptions = mock(PushSubscriptionRepository.class);
	private final PushSender sender = mock(PushSender.class);
	private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
	private final ConversationRepository conversations = mock(ConversationRepository.class);
	private final CaseRepository cases = mock(CaseRepository.class);
	private final ChatPresence presence = mock(ChatPresence.class);
	private final ChatPushNotifier notifier = new ChatPushNotifier(settings, subscriptions, sender, members,
			conversations, cases, presence, Runnable::run);

	private final UUID brand = UUID.randomUUID();
	private final UUID caseId = UUID.randomUUID();
	private final UUID pm = UUID.randomUUID();
	private final UUID client = UUID.randomUUID();

	private UUID clientConversation() {
		Conversation conversation = new Conversation(brand, caseId, ConversationType.CLIENT);
		UUID id = UUID.randomUUID();
		when(conversations.findByIdAndBrandId(id, brand)).thenReturn(Optional.of(conversation));
		Case subject = mock(Case.class);
		when(subject.getCaseCode()).thenReturn("IE-1042");
		when(cases.findById(caseId)).thenReturn(Optional.of(subject));
		when(members.findByBrandIdAndConversationIdAndLeftAtIsNull(brand, id)).thenReturn(List.of(
				new ConversationMember(brand, id, ParticipantKind.STAFF, pm, ChatRole.PM),
				new ConversationMember(brand, id, ParticipantKind.CLIENT, client, ChatRole.CLIENT)));
		return id;
	}

	private ChatChanged fromPm(UUID conversation, String text) {
		return new ChatChanged(brand, conversation, ChatChanged.Kind.MESSAGE_CREATED, new ChatViews.MessageView(
				UUID.randomUUID(), conversation, ParticipantKind.STAFF, pm, "Priya", text, null, 0, Instant.now(), null,
				false, Map.of(), false));
	}

	private PushSubscription subscription(ParticipantKind kind, UUID id) {
		return new PushSubscription(brand, kind, id, "https://push.test/" + UUID.randomUUID(), "p", "a");
	}

	@Test
	void anOfflineClientIsPushedAndTheTextNeverLeaves() {
		UUID conversation = clientConversation();
		when(presence.isOnline(ParticipantKind.CLIENT, client)).thenReturn(false);
		PushSubscription phone = subscription(ParticipantKind.CLIENT, client);
		when(subscriptions.findBySubscriberKindAndSubscriberId(ParticipantKind.CLIENT, client)).thenReturn(List.of(phone));
		when(sender.send(any(), any())).thenReturn(PushSender.Outcome.SENT);

		notifier.on(fromPm(conversation, "Your passport scan is blurry"));

		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		verify(sender).send(eq(phone), payload.capture());
		assertThat(payload.getValue()).contains("Your case team").contains("IE-1042")
				.contains("http://client.test/cases/" + caseId).contains(conversation.toString())
				.doesNotContain("passport");
	}

	@Test
	void anOnlineRecipientAndTheAuthorAreNotPushed() {
		UUID conversation = clientConversation();
		when(presence.isOnline(ParticipantKind.CLIENT, client)).thenReturn(true);

		notifier.on(fromPm(conversation, "hi"));

		verify(subscriptions, never()).findBySubscriberKindAndSubscriberId(ParticipantKind.STAFF, pm);
		verify(sender, never()).send(any(), any());
	}

	@Test
	void aGoneSubscriptionIsDeleted() {
		UUID conversation = clientConversation();
		PushSubscription stale = subscription(ParticipantKind.CLIENT, client);
		when(subscriptions.findBySubscriberKindAndSubscriberId(ParticipantKind.CLIENT, client)).thenReturn(List.of(stale));
		when(sender.send(any(), any())).thenReturn(PushSender.Outcome.GONE);

		notifier.on(fromPm(conversation, "hi"));

		verify(subscriptions).delete(stale);
	}

	@Test
	void unconfiguredPushDoesNothing() {
		ChatPushNotifier off = new ChatPushNotifier(new PushSettings("", "", "", "a", "b", "c"), subscriptions, sender,
				members, conversations, cases, presence, Runnable::run);

		off.on(fromPm(clientConversation(), "hi"));

		verify(sender, never()).send(any(), any());
	}
}
```

- [ ] **Step 2: Run to verify it fails** — `./mvnw -q test -Dtest=ChatPushNotifierTest` → FAIL.

- [ ] **Step 3: Add dependencies and config.** `pom.xml`:

```xml
		<dependency>
			<groupId>nl.martijndwars</groupId>
			<artifactId>web-push</artifactId>
			<version>5.1.2</version>
		</dependency>
```

Then run `./mvnw -q dependency:tree -Dincludes=org.bouncycastle`. web-push needs a BouncyCastle
provider at runtime; if the tree shows one arriving transitively, add nothing else. If it shows
none, add `org.bouncycastle:bcprov-jdk18on` at the latest `1.x` release on Maven Central, and record
the version chosen in the commit message.

`application.yml`, under `evalos:`:

```yaml
  push:
    # Web push (D37, Unit 57). VAPID keys: generate once with the web-push CLI
    # (`npx web-push generate-vapid-keys`) and set them in the environment. Both empty = push off,
    # chat still works. The private key is a credential and never carries a default.
    vapid-public: ${EVALOS_PUSH_VAPID_PUBLIC:}
    vapid-private: ${EVALOS_PUSH_VAPID_PRIVATE:}
    subject: ${EVALOS_PUSH_SUBJECT:}
```

`ConfigSecretsTest` scans names containing KEY/SECRET/…: `EVALOS_PUSH_VAPID_PRIVATE` and `EVALOS_PUSH_VAPID_PUBLIC` have empty defaults, so they pass. Add the three variables to `docker-compose.yml`'s backend `environment:` as `${EVALOS_PUSH_VAPID_PUBLIC:-}` etc.

- [ ] **Step 4: Implement.** `PushSettings.java` (`@Component`, record-like; constructor used by tests):

```java
package com.ie.evalos.chat.push;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** VAPID keys and the three app origins a push opens. Empty keys = push off. */
@Component
public class PushSettings {

	private final String publicKey;
	private final String privateKey;
	private final String subject;
	private final String staffOrigin;
	private final String clientBase;
	private final String expertBase;

	@Autowired
	PushSettings(@Value("${evalos.push.vapid-public:}") String publicKey,
			@Value("${evalos.push.vapid-private:}") String privateKey, @Value("${evalos.push.subject:}") String subject,
			@Value("${evalos.chat.staff-origin}") String staffOrigin,
			@Value("${evalos.portal.client-base-url}") String clientBase,
			@Value("${evalos.portal.expert-base-url}") String expertBase) {
		this.publicKey = publicKey.trim();
		this.privateKey = privateKey.trim();
		this.subject = subject.trim();
		this.staffOrigin = staffOrigin;
		this.clientBase = clientBase;
		this.expertBase = expertBase;
		if (!enabled()) {
			LoggerFactory.getLogger(PushSettings.class)
					.warn("No VAPID keys configured (EVALOS_PUSH_VAPID_*) - chat works, push notifications are off.");
		}
	}

	public boolean enabled() {
		return !publicKey.isEmpty() && !privateKey.isEmpty() && !subject.isEmpty();
	}

	public String publicKey() { return publicKey; }
	public String privateKey() { return privateKey; }
	public String subject() { return subject; }
	public String staffOrigin() { return staffOrigin; }
	public String clientBase() { return clientBase; }
	public String expertBase() { return expertBase; }
}
```

(Confirm the portal base-url property names: `application.yml:203/205` show `expert-base-url` and `client-base-url` under a `portal`-level block — use the exact full keys found there.)

`PushSubscription.java` (entity on `push_subscriptions`, `extends ScopedEntity`; constructor `(UUID brandId, ParticipantKind kind, UUID subscriberId, String endpoint, String p256dh, String auth)`; `markSent(Instant)`; getters). `PushSubscriptionRepository`:

```java
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

	List<PushSubscription> findBySubscriberKindAndSubscriberId(ParticipantKind kind, UUID subscriberId);

	Optional<PushSubscription> findByEndpoint(String endpoint);
}
```

(`findBySubscriberKindAndSubscriberId` is not brand-filtered: a subscriber id is a UUID of one brand's row, and the lookup starts from a member row that is already the conversation's brand. Say so in its Javadoc.)

`PushSender.java`:

```java
package com.ie.evalos.chat.push;

import java.security.Security;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** One encrypted Web Push (RFC 8291) with VAPID (RFC 8292), via nl.martijndwars:web-push. */
@Component
public class PushSender {

	public enum Outcome { SENT, GONE, FAILED }

	private static final Logger log = LoggerFactory.getLogger(PushSender.class);

	private final PushService service;

	PushSender(PushSettings settings) {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
		}
		PushService built = null;
		if (settings.enabled()) {
			try {
				built = new PushService(settings.publicKey(), settings.privateKey(), settings.subject());
			}
			catch (Exception invalid) {
				throw new IllegalStateException("EVALOS_PUSH_VAPID_* keys are not a valid VAPID pair", invalid);
			}
		}
		this.service = built;
	}

	public Outcome send(PushSubscription to, String json) {
		if (service == null) {
			return Outcome.FAILED;
		}
		try {
			Subscription subscription = new Subscription(to.getEndpoint(),
					new Subscription.Keys(to.getP256dh(), to.getAuth()));
			int status = service.send(new Notification(subscription, json)).getStatusLine().getStatusCode();
			if (status == 404 || status == 410) {
				return Outcome.GONE;
			}
			return status >= 200 && status < 300 ? Outcome.SENT : Outcome.FAILED;
		}
		catch (Exception failed) {
			log.warn("Web push to {} failed", to.getEndpoint(), failed);
			return Outcome.FAILED;
		}
	}
}
```

(Confirm `PushService.send(...)` returns an `org.apache.http.HttpResponse` in 5.1.2 — it did in 5.1.x; adjust the status read if its API differs.)

`ChatPushNotifier.java`:

```java
package com.ie.evalos.chat.push;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.chat.ChatChanged;
import com.ie.evalos.chat.ChatViews;
import com.ie.evalos.chat.Conversation;
import com.ie.evalos.chat.ConversationMember;
import com.ie.evalos.chat.ConversationMemberRepository;
import com.ie.evalos.chat.ConversationRepository;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.chat.live.ChatPresence;
import com.ie.evalos.domain.Case;
import com.ie.evalos.repository.CaseRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A browser push for every member who is not connected when a message lands (Unit 57 §6, D37).
 *
 * <p><strong>Who and where, never what.</strong> A lock screen is not private, and a client's case
 * correspondence is. The push names the sender and the case; the text is read in the app.
 */
@Component
public class ChatPushNotifier {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final PushSettings settings;
	private final PushSubscriptionRepository subscriptions;
	private final PushSender sender;
	private final ConversationMemberRepository members;
	private final ConversationRepository conversations;
	private final CaseRepository cases;
	private final ChatPresence presence;
	private final Executor executor;

	@Autowired
	ChatPushNotifier(PushSettings settings, PushSubscriptionRepository subscriptions, PushSender sender,
			ConversationMemberRepository members, ConversationRepository conversations, CaseRepository cases,
			ChatPresence presence) {
		this(settings, subscriptions, sender, members, conversations, cases, presence, Executors.newFixedThreadPool(2));
	}

	ChatPushNotifier(PushSettings settings, PushSubscriptionRepository subscriptions, PushSender sender,
			ConversationMemberRepository members, ConversationRepository conversations, CaseRepository cases,
			ChatPresence presence, Executor executor) {
		this.settings = settings;
		this.subscriptions = subscriptions;
		this.sender = sender;
		this.members = members;
		this.conversations = conversations;
		this.cases = cases;
		this.presence = presence;
		this.executor = executor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void on(ChatChanged change) {
		if (!settings.enabled() || change.kind() != ChatChanged.Kind.MESSAGE_CREATED) {
			return;
		}
		ChatViews.MessageView message = (ChatViews.MessageView) change.payload();
		Conversation conversation = conversations.findByIdAndBrandId(change.conversationId(), change.brandId()).orElse(null);
		if (conversation == null) {
			return;
		}
		String caseCode = cases.findById(conversation.getCaseId()).map(Case::getCaseCode).orElse("your case");
		List<ConversationMember> offline = members
				.findByBrandIdAndConversationIdAndLeftAtIsNull(change.brandId(), change.conversationId()).stream()
				.filter((m) -> !(m.getKind() == message.authorKind() && m.getMemberId().equals(message.authorId())))
				.filter((m) -> !presence.isOnline(m.getKind(), m.getMemberId()))
				.toList();
		for (ConversationMember recipient : offline) {
			String payload = payload(recipient, message, conversation, caseCode);
			for (PushSubscription to : subscriptions.findBySubscriberKindAndSubscriberId(recipient.getKind(),
					recipient.getMemberId())) {
				executor.execute(() -> deliver(to, payload));
			}
		}
	}

	private void deliver(PushSubscription to, String payload) {
		switch (sender.send(to, payload)) {
			case SENT -> {
				to.markSent(Instant.now());
				subscriptions.save(to);
			}
			case GONE -> subscriptions.delete(to);
			case FAILED -> {
				// Logged by the sender; kept, because a push service having a bad minute is not a dead browser.
			}
		}
	}

	private String payload(ConversationMember recipient, ChatViews.MessageView message, Conversation conversation,
			String caseCode) {
		String sender = recipient.getKind() == ParticipantKind.CLIENT && message.authorKind() == ParticipantKind.STAFF
				? "Your case team" : message.authorName();
		String kind = switch (conversation.getType()) {
			case CLIENT -> "Client";
			case INTERNAL -> "Internal";
			case EXPERT -> "Expert";
		};
		String url = switch (recipient.getKind()) {
			case STAFF -> settings.staffOrigin() + "/cases/" + conversation.getCaseId() + "?chat=" + conversation.getId();
			case CLIENT -> settings.clientBase() + "/cases/" + conversation.getCaseId();
			case EXPERT -> settings.expertBase() + "/case";
		};
		try {
			return JSON.writeValueAsString(Map.of("title", "New message from " + sender,
					"body", caseCode + " · " + kind + " conversation", "url", url, "tag", conversation.getId().toString()));
		}
		catch (JsonProcessingException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
```

(The staff URL assumes the staff case screen is at `/cases/:caseId`; check `frontend/src/App.tsx` for the real route and use it.)

- [ ] **Step 5: The subscription routes.** Add to `ChatApi` a `record SubscribeRequest(@NotBlank String endpoint, @NotNull Keys keys) { public record Keys(@NotBlank String p256dh, @NotBlank String auth) {} }` and `record UnsubscribeRequest(@NotBlank String endpoint)`, and methods `publicKey()`, `subscribe(ChatIdentity who, SubscribeRequest r)` (upsert by endpoint: an existing row for the endpoint is re-pointed only if it belongs to the same identity; otherwise the old row is deleted and a new one saved — a shared computer's browser moves to whoever signed in last), `unsubscribe(who, UnsubscribeRequest r)` (deletes only the caller's own row). Add to each of the three controllers:

```java
	@GetMapping("/push/public-key")
	public ApiResponse<Map<String, String>> publicKey() {
		return ApiResponse.ok(api.publicKey());
	}

	@PostMapping("/push/subscriptions")
	public ApiResponse<Void> subscribe(@Valid @RequestBody ChatApi.SubscribeRequest request) {
		api.subscribe(who(), request);
		return ApiResponse.ok(null);
	}

	@DeleteMapping("/push/subscriptions")
	public ApiResponse<Void> unsubscribe(@Valid @RequestBody ChatApi.UnsubscribeRequest request) {
		api.unsubscribe(who(), request);
		return ApiResponse.ok(null);
	}
```

`publicKey()` throws a new `PushUnavailableException` when push is off; map it in `ApiExceptionHandler` to 404 `PUSH_UNAVAILABLE` so the UI simply hides the opt-in card.

- [ ] **Step 6: Run** — `./mvnw -q test -Dtest=ChatPushNotifierTest,ConfigSecretsTest,StaffChatControllerTest` → PASS. Add to `StaffChatControllerTest`: `aGmCannotSubscribeToPush` (403) and `thePublicKeyIs404WhenPushIsOff`.

- [ ] **Step 7: Full suite** — `./mvnw -q test` → PASS, 0 failures.

- [ ] **Step 8: Commit** — `feat(chat): web push to members who are not connected (Unit 57 §6, D37)`.

---

### Task 12: Docs and memories

**Files:**
- Modify: `context/specs/57-case-chat.md` (status line; §2 note that `created_at` is the join time)
- Modify: `context/specs/55-remove-questionnaire.md` (the pending drop becomes `V70`)
- Modify: `.claude/current-decisions.md` (D19c edit; D50 already rewritten in Task 1)
- Modify: `.claude/open-decisions.md` (item h settled)
- Modify: `.claude/data-model.md`, `.claude/workflows.md`, `.claude/implementation-status.md`
- Modify: `.serena/memories/current_decisions.md`, `data_model.md`, `workflows.md`, `implementation_status.md`, `open_decisions.md`

**Interfaces:** none — documentation only.

- [ ] **Step 1: Spec 57** — status line becomes `**Status: PHASE 1 BUILT <date> (backend: schema, membership, access, lifecycle, sweep, REST, Ably live delivery, web push). Phases 2–3 (the apps) not built.**`; in §2 under `conversation_members` add: `The join time is the row's \`created_at\` (every EvalOS table's), not a separate \`joined_at\` column.`

- [ ] **Step 2: Spec 55** — replace every `V69__drop_client_application_answers.sql` with `V70__drop_client_application_answers.sql` and add one sentence: `V69 went to case chat (Unit 57) on 2026-09-25.`

- [ ] **Step 3: D19c** — in `.claude/current-decisions.md`, append to the D19c bullet: `**Chat is the one exception (Unit 57, 2026-09-25):** Sales takes part in the Client and Internal conversations of cases from their pipeline, and still reads no case data.` Mirror the sentence in `.serena/memories/current_decisions.md` where D19c is summarised.

- [ ] **Step 4: Open item h** — in `.claude/open-decisions.md`, delete the `| h   | Does the client see who is working on their case? |` row and add to the section intro: `Item h was settled by Unit 57 (2026-09-25): the client sees the case team by name in the Client conversation and never shares a conversation with the expert.` Same in the Serena `open_decisions.md`.

- [ ] **Step 5: data-model, workflows, implementation-status** — `data-model.md` CURRENT DATABASE: add rows for `conversations`, `conversation_members` (history, trigger-guarded), `messages`, `message_reactions`, `message_reads`. `workflows.md` CURRENT IMPLEMENTATION: a short "Case chat (Unit 57 phase 1)" subsection — created at `CASE_CREATED`, membership follows the events in `ChatLifecycleListener.MOVES_THE_CHAT`, `CHAT_RECONCILE` hourly with first-run backfill, read-only at `CLOSED`, live through each member's private Ably channel, web push to members without the app open. `implementation-status.md`: a `| **Case chat (Unit 57)** | PARTIAL — phase 1 of 3 |` row naming the classes and tests, gap cell: `no UI yet — staff app is phase 2, portals phase 3; ABLY_API_KEY and the VAPID keys must be set in the environment`. Mirror each in the matching Serena memory.

- [ ] **Step 6: Commit** — `docs(chat): the baseline catches up with Unit 57 phase 1`.

---

## Self-review (done while writing)

- **Spec coverage:** §0/§1 → Tasks 3–5; §2 → Task 2 (including `push_subscriptions`); §3 → Tasks 3–7; §4 → Task 8; §5 real-time (Ably) → Tasks 9–10; §6 push → Task 11; §7 frontend → phases 2–3; §8 errors → Tasks 5, 7, 8; §9 tests → each task; §10 phase 1 list → Tasks 1–11; §11 doc edits → Tasks 1 and 12.
- **Placeholders:** Task 7 Step 1 lists its tests by name and behaviour rather than full code, and its implementation is described by interface and SQL rather than a full listing — `MessageService` is the largest single class and its shape is fixed by the Interfaces block. The executor writes it from those; every signature it must honour is stated.
- **Type consistency:** `ensureAndSync(Case)`, `makeReadOnly(Case)`, `makeReadOnlyWhereClosed()`, `ChatIdentity(kind, id, brandId, staffRole)`, `ChatAccessLevel`, `ChatViews.*`, `addIfAbsent(brandId, conversationId, kind, memberId, role)` and `advance(brandId, conversationId, kind, readerId, messageId, messageAt)` are used with the same names and parameter orders throughout.
