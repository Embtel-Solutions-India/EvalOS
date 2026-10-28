import { api, unwrap } from "../../lib/api";
import type { Role } from "../../lib/session";

/** Unit 68: the GM's admin reads and writes, over routes the server gates to the GM. */

export type StaffMember = {
  id: string;
  displayName: string;
  email: string;
  role: Role;
  brandId: string | null;
  teamId: string | null;
  active: boolean;
  ghlUserId: string | null;
};

export type StaffForm = {
  displayName: string;
  email: string;
  role: Role;
  brandId: string | null;
  ghlUserId: string | null;
};

export type Brand = {
  id: string;
  name: string;
  slug: string;
  active: boolean;
  currency: string | null;
  payoutTermDays: number;
};

export type GhlUser = { id: string; name: string | null; email: string | null };

export type MirroredPipeline = {
  id: string;
  mirrorId: string;
  name: string;
  position: number | null;
  purpose: string;
  /** A count: the list payload carries no stages (Unit 38); `fetchStages` has them. */
  stages: number;
  syncedAt: string | null;
  missingSince: string | null;
};

/** One mirrored stage. `won`: moving a deal here closes it won (D70); EvalOS's flag, not GHL's (V94). */
export type MirroredStage = {
  id: string;
  name: string;
  position: number;
  won: boolean;
  missingSince: string | null;
};

export type SyncHealth = {
  open: number;
  oldest: string | null;
  needsAHuman: number;
  drifts: {
    id: string;
    entityType: string;
    entityId: string;
    kind: string;
    field: string | null;
    localValue: string | null;
    ghlValue: string | null;
    firstDetectedAt: string;
    lastSeenAt: string;
    owner: string;
    resolution: string;
  }[];
  outbox: {
    pending: number;
    dead: number;
    oldestPending: string | null;
    recentlyDead: {
      id: string;
      entityId: string;
      intent: string;
      attempts: number;
      lastFailure: string | null;
      reason: string | null;
      deadAt: string;
      /** When an Administrator re-queued it; a retried push offers no second retry. */
      retriedAt: string | null;
      /** Re-queued by the drain on its own (spec 86): a timeout, GHL's 5xx, the rate limit or an empty answer. */
      retriedAutomatically: boolean;
    }[];
  };
};

export const PURPOSES = [
  "UNASSIGNED",
  "SALES",
  "MARKETING",
  "DELIVERY",
  "EXPERT_HIRING",
  "MASTER",
] as const;

export const fetchStaff = (signal?: AbortSignal) =>
  unwrap<StaffMember[]>(api.get("/team-members", { signal }));
export const fetchBrands = (signal?: AbortSignal) =>
  unwrap<Brand[]>(api.get("/brands", { signal }));
export const fetchGhlUsers = (signal?: AbortSignal) =>
  unwrap<GhlUser[]>(api.get("/sales/users", { signal }));
export const fetchPipelines = (signal?: AbortSignal) =>
  unwrap<MirroredPipeline[]>(api.get("/ghl/pipelines", { signal }));
export const fetchSyncHealth = (signal?: AbortSignal) =>
  unwrap<SyncHealth>(api.get("/sync/drift", { signal }));
/** Re-queues one failed push; answers the refreshed Sync health. */
export const retryPush = (id: string) =>
  unwrap<SyncHealth>(api.post(`/sync/outbox/${id}/retry`));
export const fetchGrants = (id: string, signal?: AbortSignal) =>
  unwrap<{ id: string; ghlPipelineIds: string[] }>(
    api.get(`/team-members/${id}/pipelines`, { signal }),
  );

export const createStaff = (form: StaffForm, password: string) =>
  unwrap<StaffMember>(api.post("/team-members", { ...form, password }));
export const updateStaff = (id: string, form: StaffForm) =>
  unwrap<StaffMember>(api.put(`/team-members/${id}`, form));
export const setStaffActive = (id: string, active: boolean) =>
  unwrap<StaffMember>(api.put(`/team-members/${id}/active`, { active }));
export const setStaffPassword = (id: string, password: string) =>
  unwrap<void>(api.put(`/team-members/${id}/password`, { password }));
export const grantPipeline = (id: string, pipelineId: string) =>
  unwrap(api.put(`/team-members/${id}/pipelines`, { pipelineId }));
export const revokePipeline = (id: string, pipelineId: string) =>
  unwrap(api.delete(`/team-members/${id}/pipelines/${pipelineId}`));
/** D83: a brand's name, currency and payout term. The webhook token and secret are never sent either way. */
export const updateBrand = (id: string, edit: { name: string; currency: string | null; payoutTermDays: number }) =>
  unwrap<Brand>(api.put(`/brands/${id}`, edit));
export const setPurpose = (mirrorId: string, purpose: string) =>
  unwrap(api.put(`/ghl/pipelines/${mirrorId}/purpose`, { purpose }));
export const fetchStages = (mirrorId: string, signal?: AbortSignal) =>
  unwrap<MirroredStage[]>(api.get(`/ghl/pipelines/${mirrorId}/stages`, { signal }));
export const setStageWon = (stageId: string, won: boolean) =>
  unwrap<MirroredStage>(api.put(`/ghl/pipelines/stages/${stageId}/won`, { won }));

/** Roles in the order a GM thinks of them, and the words they use. */
export const ROLE_LABEL: Record<Role, string> = {
  GM: "General manager",
  ADMIN: "Administrator",
  BRAND_MANAGER: "Brand manager",
  PROJECT_MANAGER: "Project manager",
  PROJECT_COORDINATOR: "Project coordinator",
  CASE_MANAGER: "Case manager",
  EXPERT_NETWORK_MANAGER: "Expert network manager",
  SALES: "Sales",
  MARKETING: "Marketing",
};

export const isDesk = (role: Role) => role === "SALES" || role === "MARKETING";

/** Who can be put on pipelines: the two desks, and (2026-10-09) an expert network manager, on hiring pipelines only. */
export const holdsPipelines = (role: Role) =>
  isDesk(role) || role === "EXPERT_NETWORK_MANAGER";
