# Unit 77 — GM member targets and GM chat participation: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The GM can write in every open case conversation without becoming a member, and can set a monthly target per Sales (won value) and Marketing (new leads) member, which the member and the GM both see against progress.

**Architecture:** Chat gains one access level, `PARTICIPANT` (a GM who is not a member: may write, has no read position and no unread). Targets are an append-only table written and read by one small service that mirrors `GmOverviewService.setGoal`, with progress taken from the existing per-desk figures `GmOverviewService` already derives. The two halves share no code; tasks 1–2 (chat) and 3–6 (targets) can merge separately.

**Tech Stack:** Spring Boot 3 + JdbcTemplate + Flyway (backend), JUnit 5 / AssertJ / Mockito, React + TypeScript + Vitest (`packages/evalos-chat`, `frontend`).

**Spec:** `context/specs/77-gm-targets-and-chat.md` (decision D75; D50 edited).

## Global Constraints

- **Brand-scoped by default.** Every scoped query filters by `brand_id`. A query without it is a bug.
- **Append-only truth.** `member_monthly_target` rows are never updated or deleted.
- Schema ships as a Flyway migration in `backend/src/main/resources/db/migration`, next number **V86** (V85 is the latest). Never a hand-run script.
- A target `kind` is derived from the member's role (`SALES` → `WON_VALUE`, `MARKETING` → `LEADS`), never taken from the request body.
- A month with no target is **"not set"** (`null`), never `0`.
- A closed case's conversation stays read-only for everyone, the GM included.
- The Brand Manager stays a viewer. The GM is **not** added to `conversation_member`.
- Tabs for Java indentation (the repo's style), 2 spaces for TS/TSX.
- No new dependencies.
- After each task that changes behaviour: update `.claude/implementation-status.md` and the matching Serena memory in the same commit (project rule).

## Review Focus

- A GM posting in a `CLOSED` case must be refused as read-only, not accepted because the GM "can write everywhere" (Task 1 test).
- A GM message must not create a `conversation_read` row or a `conversation_member` row (Task 1 test).
- A target set for a member who is not Sales or Marketing (e.g. a PM) must be a 422, not stored (Task 3 test).
- A fractional `LEADS` amount (`2.5`) must be refused; a negative amount must be refused (Task 3 test).
- Two sets for the same member and month: the newest wins, both rows remain (Task 3 test).
- A target in brand A must be invisible when reading as brand B / for a member of brand B (Task 4 test).
- A Sales or Marketing member reading `/api/me/target` must only ever see their own row, whatever id they pass (Task 4 test).
- A month with no row must come back `null`, and `0` must come back as `0` (Task 3 test).

---

### Task 1: Chat — a GM can write in any open conversation

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/chat/ChatAccessLevel.java`
- Modify: `backend/src/main/java/com/ie/evalos/chat/ChatAccess.java:44-46,69-71`
- Modify: `backend/src/main/java/com/ie/evalos/chat/MessageService.java` (send ~86-92, markRead ~144, conversationViews ~334-366, views ~300-325)
- Modify: `backend/src/main/java/com/ie/evalos/chat/ChatViews.java:30-32`
- Modify: `backend/src/main/java/com/ie/evalos/chat/ChatInboxQuery.java` (add `gmAmong`)
- Test: `backend/src/test/java/com/ie/evalos/chat/ChatAccessTest.java`, `MessageServiceTest.java`, `live/ChatFanoutTest.java:39`
- Modify: `context/specs/77-gm-targets-and-chat.md` (§2.2: no `ChatRole.GM`; the label travels as `MessageView.authorRole`)

**Interfaces:**
- Produces: `ChatAccessLevel.PARTICIPANT`; `ChatViews.MessageView` gains a trailing component `String authorRole` (null, or `"GM"`); `ChatInboxQuery.gmAmong(Collection<UUID> staffIds) : Set<UUID>`.
- Consumes: `ChatIdentity.staffRole()`, `ChatAccess.level(...)`.

**Deviation from the spec, stated:** the spec says "`ChatRole` gains `GM`". `ChatRole` labels a stored member row (`conversation_member.role`) and the GM is never one, so the enum is left alone and the label is carried on the message as `authorRole`. Task 1 edits the spec line.

- [ ] **Step 1: Write the failing tests** — in `ChatAccessTest.java`, replace `theGmViewsEveryBrand` and `aViewerCannotWrite`, and add the closed-case and Brand Manager cases:

```java
	@Test
	void theGmTakesPartInEveryBrand() {
		ChatIdentity gm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), null, Role.GM);
		isMember(gm, false);
		assertThat(access.level(gm, conversation(ConversationType.EXPERT))).isEqualTo(ChatAccessLevel.PARTICIPANT);
	}

	@Test
	void aGmWhoIsNotAMemberMayWrite() {
		ChatIdentity gm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), null, Role.GM);
		Conversation open = conversation(ConversationType.CLIENT);
		when(conversations.findById(any())).thenReturn(Optional.of(open));
		isMember(gm, false);

		assertThat(access.requireWrite(gm, UUID.randomUUID())).isSameAs(open);
	}

	@Test
	void aGmCannotWriteToAClosedCase() {
		ChatIdentity gm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), null, Role.GM);
		Conversation closed = conversation(ConversationType.INTERNAL);
		closed.makeReadOnly(java.time.Instant.now());
		when(conversations.findById(any())).thenReturn(Optional.of(closed));
		isMember(gm, false);

		assertThatThrownBy(() -> access.requireWrite(gm, UUID.randomUUID()))
				.isInstanceOf(ConversationReadOnlyException.class);
	}

	@Test
	void aBrandManagerStillCannotWrite() {
		ChatIdentity bm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.BRAND_MANAGER);
		when(conversations.findByIdAndBrandId(any(), any())).thenReturn(Optional.of(conversation(ConversationType.CLIENT)));
		isMember(bm, false);

		assertThatThrownBy(() -> access.requireWrite(bm, UUID.randomUUID())).isInstanceOf(ForbiddenException.class);
	}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=ChatAccessTest`
Expected: COMPILATION ERROR (`PARTICIPANT` does not exist).

- [ ] **Step 3: Implement the access change**

`ChatAccessLevel.java` — add the constant and its comment:

```java
	/** A GM who is not a member: may write, but holds no read position and no unread (D75). */
	PARTICIPANT,
```

`ChatAccess.level` — the GM branch returns `PARTICIPANT`:

```java
		if (who.staffRole() == Role.GM) {
			return ChatAccessLevel.PARTICIPANT;
		}
```

`requireWrite` is unchanged: it only refuses `VIEWER`, and `PARTICIPANT` falls through to the `isReadOnly()` check, which still wins for a closed case.

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=ChatAccessTest`
Expected: PASS (all, including the untouched member/client/expert cases).

- [ ] **Step 5: Write the failing message tests** — in `MessageServiceTest.java`, following that file's existing fixture style (read its setup first and reuse its helper for building a service with mocked repositories), add:

```java
	@Test
	void aGmWhoIsNotAMemberCanPostAndLeavesNoReadPosition() {
		// Arrange as the file's other send tests do, but with a ChatIdentity whose staffRole is Role.GM and
		// access.level(...) answering PARTICIPANT; the conversation is open.
		// Act
		service.send(gm, conversationId, "Please prioritise this one", null);
		// Assert: the message was saved, and no read position was written for the GM.
		verify(messages).save(any(Message.class));
		verify(reads, never()).advance(any(), any(), any(), eq(gm.id()), any(), any());
	}

	@Test
	void aGmsMessageIsLabelledGm() {
		// Arrange: query.gmAmong(Set.of(gm.id())) answers Set.of(gm.id()) and query.names(...) answers the GM's name.
		// Act: build the view of a message authored by gm.
		// Assert:
		assertThat(view.authorRole()).isEqualTo("GM");
		assertThat(otherMemberView.authorRole()).isNull();
	}

	@Test
	void aGmCanEditOnlyItsOwnMessages() {
		// access.requireWrite answers an open conversation (the GM may write); the message is authored by someone else.
		assertThatThrownBy(() -> service.edit(gm, someoneElsesMessageId, "changed"))
				.isInstanceOf(ForbiddenException.class).hasMessage("Only the author can change a message.");
	}

	@Test
	void aGmCannotMarkReadBecauseItHasNoReadPosition() {
		// access.level(...) answers PARTICIPANT
		assertThatThrownBy(() -> service.markRead(gm, conversationId, messageId)).isInstanceOf(ForbiddenException.class);
	}
```

(Use the file's existing mocks `messages`, `reads`, `query`, `access`; if a name differs, use the file's name — the assertions are what matter.)

- [ ] **Step 6: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=MessageServiceTest`
Expected: FAIL / compile error (`authorRole` missing; `reads.advance` still called for a GM).

- [ ] **Step 7: Implement the message changes**

`ChatViews.MessageView` — append `String authorRole`:

```java
	public record MessageView(UUID id, UUID conversationId, ParticipantKind authorKind, UUID authorId,
			String authorName, String body, UUID parentId, int replyCount, Instant createdAt, Instant editedAt,
			boolean deleted, Map<Reaction, List<Reactor>> reactions, boolean mine, String authorRole) {
	}
```

`ChatInboxQuery` — add, next to `names`:

```java
	/** Which of these staff ids are General Managers; their messages carry the "GM" label (D75). */
	public Set<UUID> gmAmong(Collection<UUID> staffIds) {
		List<UUID> distinct = staffIds.stream().distinct().toList();
		if (distinct.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(jdbc.query("SELECT id FROM team_member WHERE role = 'GM' AND id IN ("
				+ placeholders(distinct) + ")", (rs, n) -> rs.getObject(1, UUID.class), distinct.toArray()));
	}
```

`MessageService.views(...)` — resolve once for the page, then pass into both constructions:

```java
		Set<UUID> gms = query.gmAmong(rows.stream().filter((r) -> r.authorKind() == ParticipantKind.STAFF)
				.map(ChatInboxQuery.Row::authorId).toList());
		// ...in the final map, append as the last constructor argument:
		//     gms.contains(row.authorId()) && row.authorKind() == ParticipantKind.STAFF ? "GM" : null
```

`conversationViews(...)` — the same for `lastView` (collect the last-message author ids; one `gmAmong` call), and replace the access value:

```java
		ChatAccessLevel level = who.staffRole() == Role.GM ? ChatAccessLevel.PARTICIPANT
				: who.isViewerRole() ? ChatAccessLevel.VIEWER : ChatAccessLevel.MEMBER;
		// ...use `level` where `viewer ? VIEWER : MEMBER` was
```

`send(...)` — write the read position only for a member:

```java
		if (access.level(who, conversation) == ChatAccessLevel.MEMBER) {
			reads.advance(conversation.getBrandId(), conversationId, who.kind().name(), who.id(), saved.getId(),
					saved.getCreatedAt());
		}
```

`markRead(...)` is unchanged (`!= MEMBER` already refuses `PARTICIPANT`); reword its message to `"Only a member of a conversation has a read position."`. `unreadTotal` is unchanged (`isViewerRole()` includes the GM, so it stays 0). Fix the two `MessageView` constructions in `MessageService` and the one in `live/ChatFanoutTest.java:39` (pass `null` for `authorRole`).

- [ ] **Step 8: Run the chat tests**

Run: `cd backend && ./mvnw -q test -Dtest='Chat*Test,MessageServiceTest,ConversationServiceTest'`
Expected: PASS, 0 failures.

- [ ] **Step 9: Edit the spec line and commit**

In `context/specs/77-gm-targets-and-chat.md` §2.2 replace "`ChatRole` gains `GM`, and the web labels render it" with "the message carries `authorRole = \"GM\"` (`ChatRole` labels stored member rows and is left alone), and the web renders a General Manager badge". Update `implementation-status.md` row and the Serena memory.

```bash
git add backend/src context/specs/77-gm-targets-and-chat.md .claude .serena
git commit -m "feat(chat): the GM takes part in every open conversation without joining it (D75)"
```

---

### Task 2: Chat UI — the GM composes, and its messages say General Manager

**Files:**
- Modify: `packages/evalos-chat/src/core/types.ts:27-29,51`
- Modify: `packages/evalos-chat/src/react/ConversationView.tsx:40,68`
- Modify: `packages/evalos-chat/src/react/MessageList.tsx:160`
- Test: `packages/evalos-chat/src/core/reducer.test.ts` (fixture), a new `packages/evalos-chat/src/react/gmChat.test.tsx` only if the package already tests React components; otherwise cover by the type change plus the manual check below.

**Interfaces:**
- Consumes: `MessageView.authorRole` and `ConversationView.access = 'PARTICIPANT'` from Task 1.
- Produces: `Message.authorRole: string | null`; `Conversation.access: 'MEMBER' | 'VIEWER' | 'PARTICIPANT'`.

- [ ] **Step 1: Update the types and fixtures**

`types.ts`: add `authorRole: string | null` to `Message`, and widen `access`:

```ts
  access: 'MEMBER' | 'VIEWER' | 'PARTICIPANT'
```

Add `authorRole: null` to every `Message` fixture in `reducer.test.ts` and `client.test.ts` so `tsc` is clean.

- [ ] **Step 2: Run the package typecheck and tests**

Run: `cd packages/evalos-chat && npx tsc --noEmit && npx vitest run`
Expected: PASS. (A `PARTICIPANT` conversation is not `viewer`, so `ConversationView` already shows the composer; confirm by reading `ConversationView.tsx:40` — `viewer = access === 'VIEWER'` — no change needed there.)

- [ ] **Step 3: Show the oversight banner for a participant, and the badge**

`ConversationView.tsx` — after the `viewer` banner line add:

```tsx
      {conversation.access === 'PARTICIPANT' && conversation.status !== 'READ_ONLY' && (
        <p className="ec-banner">You are taking part as General Manager. You are not listed as a member of this conversation.</p>
      )}
```

`MessageList.tsx:160` — after the `<strong>` name:

```tsx
              {message.authorRole === 'GM' && <span className="ec-muted"> · General Manager</span>}
```

- [ ] **Step 4: Manual check** (no browser harness in the package): run the staff app, sign in as the GM, open any case conversation, confirm the composer is present, send a message, and confirm a second signed-in member sees "General Manager" beside the name; confirm a closed case shows no composer.

- [ ] **Step 5: Commit**

```bash
git add packages/evalos-chat
git commit -m "feat(chat-ui): GM composes in any open conversation; its messages say General Manager"
```

---

### Task 3: Targets — table, service, and the rules

**Files:**
- Create: `backend/src/main/resources/db/migration/V86__member_monthly_target.sql`
- Create: `backend/src/main/java/com/ie/evalos/service/MemberTargetService.java`
- Test: `backend/src/test/java/com/ie/evalos/service/MemberTargetServiceTest.java`

**Interfaces:**
- Produces:
  - `MemberTargetService.set(UUID teamMemberId, LocalDate month, BigDecimal amount, UUID setBy) : void`
  - `MemberTargetService.current(UUID brandId, UUID teamMemberId, LocalDate month) : Optional<BigDecimal>`
  - `MemberTargetService.kindOf(Role role) : Optional<TargetKind>` and `enum TargetKind { WON_VALUE, LEADS }` (nested in the service).
- Consumes: `JdbcTemplate`, `TeamMemberRepository.findById`.

- [ ] **Step 1: Write the migration** (`V86__member_monthly_target.sql`)

```sql
-- A monthly target per sales or marketing member, set by the GM (D75, Unit 77).
--
-- Append-only, shaped like sales_monthly_goal (V77): a change is a new row, the newest row for
-- (member, month) is the target, older rows are who moved it and when. A month with no row is
-- "not set", which is not the same as a target of 0.
CREATE TABLE member_monthly_target (
    id             uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id       uuid          NOT NULL REFERENCES brand (id),
    team_member_id uuid          NOT NULL REFERENCES team_member (id),
    month          date          NOT NULL CHECK (extract(day FROM month) = 1),
    kind           text          NOT NULL CHECK (kind IN ('WON_VALUE', 'LEADS')),
    amount         numeric(12,2) NOT NULL CHECK (amount >= 0),
    set_by         uuid          NOT NULL REFERENCES team_member (id),
    set_at         timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX member_monthly_target_latest
    ON member_monthly_target (brand_id, team_member_id, month, set_at DESC);
```

- [ ] **Step 2: Write the failing tests** (`MemberTargetServiceTest`) — an in-memory `JdbcTemplate` mock matches the style of `GmOverviewServiceTest`, but the append-only and "newest wins" rules are SQL behaviour, so the integration cases run on the project's Postgres test setup. Read an existing `*HibernateTest`/`*PostgresTest` in `backend/src/test` for the base class and use it for the cases marked (db):

```java
	@Test
	void aSalesMemberGetsAWonValueTargetAndAMarketingMemberALeadsTarget() {
		assertThat(MemberTargetService.kindOf(Role.SALES)).contains(MemberTargetService.TargetKind.WON_VALUE);
		assertThat(MemberTargetService.kindOf(Role.MARKETING)).contains(MemberTargetService.TargetKind.LEADS);
		assertThat(MemberTargetService.kindOf(Role.PROJECT_MANAGER)).isEmpty();
	}

	@Test
	void aMemberWhoIsNotSalesOrMarketingCannotBeGivenATarget() {
		// team member with Role.PROJECT_MANAGER
		assertThatThrownBy(() -> service.set(pm.getId(), LocalDate.of(2026, 10, 15), new BigDecimal("5"), gmId))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void aLeadsTargetMustBeAWholeNumberAndNoTargetIsNegative() {
		assertThatThrownBy(() -> service.set(marketing.getId(), OCT, new BigDecimal("2.5"), gmId))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.set(sales.getId(), OCT, new BigDecimal("-1"), gmId))
				.isInstanceOf(InvalidRequestException.class);
	}

	// (db) two sets for the same member and month: both rows remain, the newest wins
	@Test
	void theNewestRowIsTheTargetAndTheHistoryIsKept() {
		service.set(sales.getId(), OCT, new BigDecimal("1000"), gmId);
		service.set(sales.getId(), OCT, new BigDecimal("1500"), gmId);
		assertThat(service.current(brand, sales.getId(), OCT)).contains(new BigDecimal("1500.00"));
		assertThat(rowCount("member_monthly_target")).isEqualTo(2);
	}

	// (db) not set is empty; zero is zero
	@Test
	void aMonthWithNoRowIsNotSetAndAZeroTargetIsZero() {
		assertThat(service.current(brand, sales.getId(), OCT)).isEmpty();
		service.set(sales.getId(), OCT, BigDecimal.ZERO, gmId);
		assertThat(service.current(brand, sales.getId(), OCT)).contains(new BigDecimal("0.00"));
	}

	// (db) brand scoping
	@Test
	void aTargetIsInvisibleToAnotherBrand() {
		service.set(sales.getId(), OCT, new BigDecimal("1000"), gmId);
		assertThat(service.current(otherBrand, sales.getId(), OCT)).isEmpty();
	}
```

- [ ] **Step 3: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=MemberTargetServiceTest`
Expected: COMPILATION ERROR (`MemberTargetService` does not exist).

- [ ] **Step 4: Implement the service**

```java
package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.TeamMemberRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A monthly target per sales or marketing member (D75). Append-only, like the brand goal
 * ({@code GmOverviewService#setGoal}): the newest row for (member, month) is the target.
 */
@Service
public class MemberTargetService {

	/** What a target counts. The member's role decides it; the caller never does. */
	public enum TargetKind { WON_VALUE, LEADS }

	private final JdbcTemplate jdbc;
	private final TeamMemberRepository members;

	MemberTargetService(JdbcTemplate jdbc, TeamMemberRepository members) {
		this.jdbc = jdbc;
		this.members = members;
	}

	public static Optional<TargetKind> kindOf(Role role) {
		return switch (role) {
			case SALES -> Optional.of(TargetKind.WON_VALUE);
			case MARKETING -> Optional.of(TargetKind.LEADS);
			default -> Optional.empty();
		};
	}

	/** Appends a target for one member and month. The brand is the member's own. */
	@Transactional
	public void set(UUID teamMemberId, LocalDate month, BigDecimal amount, UUID setBy) {
		TeamMember member = members.findById(teamMemberId)
				.orElseThrow(() -> new InvalidRequestException("That team member does not exist"));
		TargetKind kind = kindOf(member.getRole()).orElseThrow(() -> new InvalidRequestException(
				"Only Sales and Marketing members have a monthly target"));
		if (amount == null || amount.signum() < 0) {
			throw new InvalidRequestException("A monthly target is zero or more");
		}
		if (kind == TargetKind.LEADS && amount.stripTrailingZeros().scale() > 0) {
			throw new InvalidRequestException("A leads target is a whole number");
		}
		jdbc.update("INSERT INTO member_monthly_target (brand_id, team_member_id, month, kind, amount, set_by) "
				+ "VALUES (?, ?, ?, ?, ?, ?)", member.getBrandId(), teamMemberId, month.withDayOfMonth(1),
				kind.name(), amount, setBy);
	}

	/** The newest target for this member and month in this brand; empty means "not set". */
	public Optional<BigDecimal> current(UUID brandId, UUID teamMemberId, LocalDate month) {
		return jdbc.query("SELECT amount FROM member_monthly_target WHERE brand_id = ? AND team_member_id = ? "
				+ "AND month = ? ORDER BY set_at DESC LIMIT 1", (rs, n) -> rs.getBigDecimal(1), brandId,
				teamMemberId, month.withDayOfMonth(1)).stream().findFirst();
	}
}
```

- [ ] **Step 5: Run to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=MemberTargetServiceTest`
Expected: PASS. Also run `./mvnw -q test -Dtest='*Migration*,*Flyway*'` if such tests exist, to confirm V86 applies.

- [ ] **Step 6: Update `.claude/data-model.md` (REQUIRED FUTURE → CURRENT for `member_monthly_target`), `implementation-status.md`, the Serena memory, then commit**

```bash
git add backend/src .claude .serena
git commit -m "feat(targets): an append-only monthly target per sales and marketing member (D75)"
```

---

### Task 4: Targets — the API (GM list and set, member's own)

**Files:**
- Create: `backend/src/main/java/com/ie/evalos/web/MemberTargetController.java`
- Modify: `backend/src/main/java/com/ie/evalos/service/MemberTargetService.java` (add `overview`, `mine`)
- Test: `backend/src/test/java/com/ie/evalos/web/MemberTargetRouteTest.java` (style of `GmOverviewRouteTest`)

**Interfaces:**
- Consumes: `MemberTargetService.set/current`, `GmOverviewService.forCaller(DateWindow, UUID) : GmOverview` whose `desks()` is `List<DeskRow(memberId, name, role, newLeads, won, wonValue, open, openValue)>`.
- Produces (JSON, inside `ApiResponse`):
  - `GET /api/gm/targets?month=YYYY-MM` → `List<TargetRow(memberId, name, role, kind, target|null, progress)>`
  - `PUT /api/gm/targets/{memberId}` body `{ "month": "2026-10-01", "amount": 1500 }` → `204`-style `ApiResponse<Void>`
  - `GET /api/me/target?month=YYYY-MM` → `TargetRow` for the caller, `404` if the caller's role has no target kind.
- `progress` is `wonValue` for `WON_VALUE`, `newLeads` for `LEADS`, from the same `DeskRow` the overview shows.

- [ ] **Step 1: Write the failing route tests**

```java
	@Test
	void onlyTheGmMaySetATarget() {
		// as a SALES principal: PUT /api/gm/targets/{id} -> 403
	}

	@Test
	void theGmListSaysNotSetAsNullAndCarriesTheDeskProgress() {
		// GmOverviewService stubbed with one SALES DeskRow(wonValue=900) and one MARKETING DeskRow(newLeads=7);
		// MemberTargetService.current: sales -> 1000.00, marketing -> empty.
		// GET /api/gm/targets?month=2026-10
		// expect: sales {kind: WON_VALUE, target: 1000.00, progress: 900}; marketing {kind: LEADS, target: null, progress: 7}
	}

	@Test
	void aMemberReadsOnlyTheirOwnTargetWhateverIdTheyPass() {
		// principal = sales member A; GET /api/me/target?month=2026-10&memberId=<B's id>
		// expect: A's row; B's id is ignored
	}

	@Test
	void aMemberWithNoTargetKindGets404() {
		// principal = PROJECT_MANAGER; GET /api/me/target -> 404
	}
```

Fill the arrange/act code from `GmOverviewRouteTest.java` (it shows how the MVC slice, the principal and `ApiResponse` are set up) — keep its structure; the assertions above are the contract.

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=MemberTargetRouteTest`
Expected: FAIL / compile error (controller missing).

- [ ] **Step 3: Implement**

`MemberTargetService` — add:

```java
	public record TargetRow(UUID memberId, String name, Role role, TargetKind kind, BigDecimal target,
			BigDecimal progress) {
	}

	/** One row per desk the overview lists, with its target (null = not set) and progress. */
	public java.util.List<TargetRow> overview(UUID brandId, LocalDate month,
			java.util.List<GmOverviewService.DeskRow> desks) {
		return desks.stream().flatMap((desk) -> kindOf(desk.role()).stream().map((kind) -> new TargetRow(
				desk.memberId(), desk.name(), desk.role(), kind,
				current(brandId, desk.memberId(), month).orElse(null),
				kind == TargetKind.WON_VALUE ? desk.wonValue() : BigDecimal.valueOf(desk.newLeads())))).toList();
	}
```

`MemberTargetController`:

```java
package com.ie.evalos.web;

@RestController
@RequestMapping("/api")
public class MemberTargetController {

	public record SetRequest(@NotNull LocalDate month, @NotNull @PositiveOrZero BigDecimal amount) {
	}

	private final MemberTargetService targets;
	private final GmOverviewService overview;
	private final SellingBrand sellingBrand;

	// constructor injection of the three above

	@GetMapping("/gm/targets")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<List<MemberTargetService.TargetRow>> list(@RequestParam String month) {
		LocalDate first = YearMonth.parse(month).atDay(1);
		DateWindow window = DateWindow.of("month", null, null, BusinessCalendar.clock()); // replaced below
		return ApiResponse.ok(targets.overview(sellingBrand.id(), first, overview.forCaller(windowFor(first), null).desks()));
	}

	@PutMapping("/gm/targets/{memberId}")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<Void> set(@PathVariable UUID memberId, @Valid @RequestBody SetRequest request,
			@AuthenticationPrincipal StaffPrincipal principal) {
		targets.set(memberId, request.month(), request.amount(), principal.memberId());
		return ApiResponse.ok(null);
	}

	@GetMapping("/me/target")
	@PreAuthorize("hasAnyRole('SALES', 'MARKETING')")
	public ApiResponse<MemberTargetService.TargetRow> mine(@RequestParam String month,
			@AuthenticationPrincipal StaffPrincipal principal) {
		LocalDate first = YearMonth.parse(month).atDay(1);
		return targets.overview(sellingBrand.id(), first, overview.forCaller(windowFor(first), null).desks()).stream()
				.filter((row) -> row.memberId().equals(principal.memberId())).findFirst()
				.map(ApiResponse::ok).orElseThrow(() -> new NotFoundException("No target applies to you"));
	}
}
```

`windowFor(first)` builds the month's `DateWindow` the way `MetricsController.gm` does: `DateWindow.of("month"|"last_month", …)` is range-based, so use the custom form `DateWindow.of("custom", first.toString(), first.with(lastDayOfMonth()).toString(), BusinessCalendar.clock())` — **check `DateWindow.of`'s accepted range names in `common/DateWindow.java` and use exactly its custom-range spelling** (Step 3a), and delete the stray first `window` line in `list` once `windowFor` exists. Use the project's own not-found exception (`grep -rn "class NotFoundException" backend/src/main`) and `StaffPrincipal`/`@PreAuthorize` imports as `MetricsController` does. Cache `overview.forCaller` per month for 60 s inside `MemberTargetController` (a `ConcurrentHashMap<LocalDate, Cached>`), so a desk page does not trigger a full GHL read on every load; state this as a `ponytail:` comment ("60 s cache; per-member mirror query if the desk count grows").

- [ ] **Step 3a:** open `DateWindow.java`, confirm the custom-range arguments, and fix `windowFor`.

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=MemberTargetRouteTest,MemberTargetServiceTest,GmOverviewRouteTest`
Expected: PASS.

- [ ] **Step 5: Commit** (with `workflows.md` / status / Serena updates)

```bash
git add backend/src .claude .serena
git commit -m "feat(targets): GM lists and sets member targets; a member reads their own (D75)"
```

---

### Task 5: GM overview — a target column on "By desk"

**Files:**
- Modify: `frontend/src/features/dashboards/pmMetricsApi.ts` (add `TargetRow`, `fetchTargets`, `setMemberTarget`)
- Create: `frontend/src/features/dashboards/DeskTarget.tsx`
- Modify: `frontend/src/features/dashboards/GmDashboard.tsx:218-262`
- Test: `frontend/src/features/dashboards/DeskTarget.test.tsx` if the folder already has component tests; otherwise a pure-function test for `progressPct`.

**Interfaces:**
- Consumes: `GET /gm/targets?month=`, `PUT /gm/targets/{id}` (Task 4); `data.headline.goalMonth` (the month the overview is on, or null when the range is not a month).
- Produces: `fetchTargets(month: string): Promise<TargetRow[]>`, `setMemberTarget(memberId: string, month: string, amount: number): Promise<void>`, `progressPct(progress: number, target: number | null): number | null`.

- [ ] **Step 1: Write the failing test** (pure function, always testable)

```ts
import { describe, expect, it } from 'vitest'
import { progressPct } from './DeskTarget'

describe('progressPct', () => {
  it('is null when no target is set, so "not set" never reads as 0%', () => {
    expect(progressPct(900, null)).toBeNull()
  })
  it('is null for a zero target rather than dividing by zero', () => {
    expect(progressPct(900, 0)).toBeNull()
  })
  it('rounds to a whole percent', () => {
    expect(progressPct(900, 1000)).toBe(90)
    expect(progressPct(7, 20)).toBe(35)
  })
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/features/dashboards/DeskTarget.test.tsx`
Expected: FAIL (module not found).

- [ ] **Step 3: Implement**

`pmMetricsApi.ts`:

```ts
export type TargetRow = {
  memberId: string
  name: string
  role: 'SALES' | 'MARKETING'
  kind: 'WON_VALUE' | 'LEADS'
  target: number | null
  progress: number
}

export async function fetchTargets(month: string, signal?: AbortSignal): Promise<TargetRow[]> {
  return unwrap<TargetRow[]>(api.get('/gm/targets', { params: { month: month.slice(0, 7) }, signal }))
}

/** Append-only on the server: the newest target for the member and month counts. */
export async function setMemberTarget(memberId: string, month: string, amount: number): Promise<void> {
  await unwrap<null>(api.put(`/gm/targets/${memberId}`, { month, amount }))
}
```

`DeskTarget.tsx` — exports `progressPct` and a `DeskTarget` cell component: shows `formatMoney` for `WON_VALUE` and a plain count for `LEADS`; "Not set" when `target === null`; an inline number input and Save/Cancel modelled on `GoalButton` (same `save`/`refusal` pattern, `step={1}`, `min={0}`).

`GmDashboard.tsx` — in the "By desk" `Card`: when `data?.headline?.goalMonth` is non-null (a monthly range), load `fetchTargets(goalMonth)` into state (refetch after a save) and add a `Target` header and a cell per row that renders `<DeskTarget row={targetFor(row.memberId)} month={goalMonth} onSaved={reloadTargets} />`. When the range is not a month the column is omitted, and the card note says "Targets show for a month range."

- [ ] **Step 4: Run to verify it passes, and typecheck**

Run: `cd frontend && npx vitest run src/features/dashboards && npx tsc --noEmit`
Expected: PASS, no type errors.

- [ ] **Step 5: Manual check:** GM signs in, month range, "By desk": a Sales row shows "Not set", set 1000, see "900 / 1,000 · 90%"; a Marketing row takes `7` but refuses `2.5` with the server's message.

- [ ] **Step 6: Commit**

```bash
git add frontend .claude .serena
git commit -m "feat(targets): the GM sets each desk's monthly target from the By-desk table (D75)"
```

---

### Task 6: A member sees their own target

**Files:**
- Modify: `frontend/src/features/dashboards/pmMetricsApi.ts` (add `fetchMyTarget`)
- Create: `frontend/src/features/opportunities/MyTarget.tsx`
- Modify: `frontend/src/features/opportunities/OpportunityBoardPage.tsx` (render `<MyTarget />` in the board header, for SALES and MARKETING only)
- Test: reuse `progressPct` (Task 5); add a render-free check that `MyTarget` returns nothing when the endpoint 404s.

**Interfaces:**
- Consumes: `GET /me/target?month=` (Task 4), `progressPct`.
- Produces: `fetchMyTarget(month: string): Promise<TargetRow | null>` (`null` on 404).

- [ ] **Step 1: Write the failing test** for `fetchMyTarget` returning `null` on a 404 (mock `api.get` as the other `*Api.test.ts` files in `frontend/src` do).

- [ ] **Step 2: Run to verify it fails.** Run: `cd frontend && npx vitest run src/features/dashboards`. Expected: FAIL.

- [ ] **Step 3: Implement** `fetchMyTarget` (catch an axios 404 → `null`), and `MyTarget`: this month's target and progress as "Target 1,000 · 900 so far (90%)", "No target set for this month" when `target` is null, rendering nothing for a role without a target kind. Place it in the board header beside the existing totals.

- [ ] **Step 4: Run tests and typecheck.** `cd frontend && npx vitest run && npx tsc --noEmit`. Expected: PASS.

- [ ] **Step 5: Manual check:** a Sales member's board header shows their target and progress; a PM sees nothing new.

- [ ] **Step 6: Final pass.** Run the full backend suite (`cd backend && ./mvnw -q test`) and frontend + chat package suites; update `implementation-status.md` (SPECIFIED → BUILT with evidence), `workflows.md` if it describes who may write in chat, and the Serena memories; then:

```bash
git add frontend .claude .serena
git commit -m "feat(targets): a sales or marketing member sees their own target and progress (D75)"
```

---

## Self-review

**Spec coverage:** §1.1 role→kind, §1.2 table, §1.3 three routes → Tasks 3–4; §1.4 screens → Tasks 5–6; §1.5 tests → Tasks 3–4; §2.1 rule, §2.2 no membership, §2.3 points 1–5, §2.4 tests → Task 1 (point 1 resolved: author names come from `query.names` by author id, not member rows — only the *label* needed new work; point 2: no read row for a non-member; point 3: `ownForWrite` already requires `isAuthoredBy`, so a GM edits/deletes only their own — covered by `forWrite` → `requireWrite` now passing for a GM; point 4: `ChatPushNotifier` already excludes the author and notifies the member list only, a non-member author is simply absent; point 5: the GM already hears every brand's live channel, verified in the manual check of Task 2). The "a GM edits only its own message" rule has its own test in Task 1 Step 5 (`aGmCanEditOnlyItsOwnMessages`).

**Placeholders:** the route-test and `MessageServiceTest` bodies name their arrange steps in comments because both files have established fixtures the executor must reuse; the assertions are exact. `windowFor` has an explicit verification step (3a).

**Type consistency:** `MessageView.authorRole`, `ChatAccessLevel.PARTICIPANT`, `TargetRow`, `MemberTargetService.kindOf/set/current/overview` are used identically across tasks.
