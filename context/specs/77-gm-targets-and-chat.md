# Unit 77 — Monthly targets per member, and the GM takes part in every chat

**Decided 2026-10-07 by the business (D75).** **Status: SPECIFIED, not built** (see
`implementation-status.md`). Two independent changes shipped as one unit because the GM asked for
both together; they share no code and can be built and merged separately.

## 1. Monthly targets per sales and marketing member

### 1.1 What a target is

| Member's role | `kind` | `amount` means | Progress is |
|---|---|---|---|
| Sales | `WON_VALUE` | money, same currency as the deal values | the value of deals the member's desk **won** in the month |
| Marketing | `LEADS` | a count of new opportunities | opportunities **created** on the member's desk in the month |

The role decides the kind. The GM never picks a unit, and a member whose role is neither Sales nor
Marketing cannot be given a target (422). Progress reuses the per-desk figures
`GmOverviewService` already derives (`deskWonValue`, `deskNew`), so there is one source of truth
for "what did this desk do this month" and no second calculation.

The existing `sales_monthly_goal` (V77) is the brand-wide goal and is **unchanged**. It is not
re-derived as the sum of member targets in this unit; that is a separate decision if wanted.

### 1.2 Data

New table, **append-only**, shaped like `sales_monthly_goal`: a change is a new row, the newest
row for (member, month) is the target, older rows are the history of who moved it and when.

```sql
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

Ships as a Flyway migration in the main migration tree (never a hand-run script). Rows are never
updated or deleted. A `LEADS` amount is a whole number: the API rejects a fraction.

### 1.3 API (GM only; every query brand-scoped)

- `GET /api/gm/targets?month=YYYY-MM` → `[{ memberId, target }]` for the members who have one; a
  member with none is absent ("not set"). **Amounts only and no GHL read:** the GM dashboard already
  holds each desk's progress from the overview it loaded, and a second overview read per page load
  would double its heaviest request. The unit follows the desk's role, which the overview carries.
- `PUT /api/gm/targets/{teamMemberId}` `{ month, amount }` → appends a row; the `kind` is taken
  from the member's role, not the body. 403 for anyone but the GM; 404 for a member outside the
  caller's scope.
- A member reads **only their own** target and progress (one overview read, filtered to the
  caller's own row server-side): `GET /api/me/target?month=` (any Sales or
  Marketing member; returns the same shape for themselves, 404 if the caller has no target kind).

A month with no row is **"not set"**, never 0: a zero target is a deliberate choice and must not
look like an absent one.

### 1.4 Screens

- **GM overview, "By desk"**: each desk row gets a target cell for the selected month: the amount,
  progress and a percentage; the GM edits it inline. Month is the page's existing month filter.
- **Sales and Marketing desk pages**: the member's own target and progress beside the figure they
  already see. Read-only.

### 1.5 Tests (written first)

Role → kind mapping; a Sales/Marketing-only guard; append-only (two sets → two rows, newest wins,
history kept); "not set" vs 0; brand scoping (a target in one brand is invisible to another); a
member reads only their own; progress equals the overview's per-desk figure for the same month.

## 2. The GM takes part in every conversation

### 2.1 Rule

`ChatAccess.level` returns `VIEWER` for a GM who is not a member. **Change:** a GM may write in any
conversation of any brand, **except** where the conversation is read-only (case `CLOSED`), which
still wins. Brand Manager is unchanged: a viewer.

```java
// ChatAccess.requireWrite
if (level == ChatAccessLevel.VIEWER && who.staffRole() != Role.GM) {
    throw new ForbiddenException("Oversight reads conversations; it does not take part in them.");
}
```

### 2.2 The GM does not become a member (decided)

Membership is computed from assignments; the recompute is untouched. A GM post therefore:

- is shown as sent by the **General Manager**: the message carries `authorRole = "GM"` (`ChatRole`
  labels stored member rows and a GM is never one, so it is left alone) and the web renders a
  General Manager badge;
- creates **no** `conversation_member` row, so the GM's inbox is not filled with every case and the
  assignment recompute has nothing to evict;
- reaches the conversation's members through the existing publish and push paths;
- gets no unread count for replies. The GM sees replies by opening the conversation. This is the
  accepted cost of not joining.

### 2.3 Points to verify while planning (not assumed)

1. `MessageService` resolves author name and role label from member rows
   (`ChatInboxQuery.currentMembers`). A GM is not a member, so their name and the new `GM` label
   must be resolved some other way, and a message from a non-member must not render blank.
2. `send` calls `reads.advance` for the sender, which creates a read-watermark row. For a
   non-member GM decide whether that row is wanted (the existing comment says oversight "leaves no
   trace"); if not, skip it for non-members.
3. `edit` / `delete` use `ownForWrite`; confirm a GM can edit and delete their **own** messages and
   no one else's.
4. Push and unread for the other members work from the member list; the GM's own message must not
   push to the GM.
5. The GM already hears every brand's live channel (`live:brand:{id}`), so the posted message
   should appear in the GM's open conversation without a refresh; confirm.

### 2.4 Tests (written first)

A GM non-member posts in each of the three conversation types of a case in any brand; a post in a
`CLOSED` case is refused with `ConversationReadOnlyException`; a Brand Manager still gets 403; a
client and an expert still cannot reach an internal conversation; the posted message carries the
`GM` label and its author's name; no member row is created; the other members receive it; the GM
can edit and delete only their own message.

## 3. Documents this unit edits when it is built

`current-decisions.md` (**D50 edited in place and D75 added; both are in this spec's commit**),
`data-model.md` (new table, REQUIRED FUTURE → CURRENT when built), `workflows.md`,
`implementation-status.md`, and the matching Serena memories.

## 4. Out of scope

Targets for roles other than Sales and Marketing; the team goal as a sum of member targets;
notifications when a target is reached or missed; a target-history screen (the rows are kept,
nothing reads them yet); letting a Brand Manager post; the GM joining a conversation as a member.
