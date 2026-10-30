# Data model

**The authoritative file is `.claude/data-model.md`. It separates CURRENT DATABASE from REQUIRED
FUTURE MODEL — never mix them.**

47 tables plus `flyway_schema_history`; schema migrations `V1`–`V98` in
`backend/src/main/resources/db/migration/` (**no `V71`** — held, never written). Seed trees are siblings,
never nested (`MigrationTreeTest`): `seed-local` V900–V916 (no V906), `seed-testprod` V950–V954,
`seed-prod` V960 (IE desks) + V961 (prod Admin). New schema means a new migration; an applied one is
never edited.

Facts that catch people out:

1. **No request table.** `V78` (Unit 64) dropped `client_application` and `application_document`;
   every client file is a `case_document` (`case_id` NOT NULL).
2. **`client_account` and `contact_snapshot` are joined** (`V55`): `client_account.contact_id` FK,
   and `ghl_contact_id` is unique per brand where set (`uq_client_account_per_brand_ghl_contact`).
3. **`team_member_pipeline` has no `brand_id`** — scoped via the member; most reads key on ids alone.
   Revoke stamps `revoked_at` (`V64`); re-grant is `ON CONFLICT DO UPDATE`, overwriting
   `granted_at`/`granted_by` — history only in `audit_event`.
4. `V39` once forbade emptying `team_member.ghl_pipeline_id` for SALES/MARKETING; **`V84` removed
   that** — the column is vestigial and optional.

Append-only: `audit_event`, `draft_comments`, `case_client_remark`, `member_monthly_target` (`V87`)
and `conversation_members` (UPDATE only to stamp `left_at`) by trigger; **`sales_monthly_goal` by
convention only, no trigger**. `opportunity_note` lost its trigger in `V67` (author edit/delete).
`opportunity_note_ghl_link` (`V66`–`V68`): no FK, no trigger, null GHL id = delete marker.

Mirrors (exist): `pipeline`, `pipeline_stage` (`V50`; `won` `V94` is EvalOS-owned; `purpose` gains
`EXPERT_HIRING` `V76`, `MASTER` `V93`), `opportunity` (`V51`; `custom_fields` `V62`,
`locally_edited_fields` `V63`, `ghl_base` `V96`), `ghl_custom_field` / `ghl_calendar` / `ghl_user`
(`V60`), `ghl_note` / `ghl_tag` (`V62`), `sync_drift` (`V56`), `sync_outbox` (`V57`/`V58`;
`retried_at`/`retried_by` `V95`). `ghl_opportunity_cache` dropped `V52`; `ghl_funnel_cache`
orphaned, not dropped.

Other landmarks: `V69` case chat (conversations, members, messages, reactions, reads,
push_subscriptions); `V70` draft files + `draft_comments`; `V72` `expert_account` +
`expert_credential_token`; `V77` `sales_monthly_goal`; `V79`/`V80` offer fee + version; `V81`
contact details; `V82` offer note + handoff note; `V83` terms acceptance; `V85` `case_client_remark`;
`V86`/`V87` `member_monthly_target`; `V88` role `ADMIN`; `V90` `app_setting`; `V91`
`team_member.segment` vestigial; `V92` `push_outbox`; `V97` `scheduled_job(started_at)` index;
`V98` `team_member.archived_at`. `team_id` vestigial (D20).

Dead: `TeamMemberRepository.findPipelinesOfActiveMembers` (no callers).

FUTURE: nothing approved is unbuilt (expert accounts and chat exist; outbound webhook queue dropped
with D53; `expert_application` replaced by D61).
