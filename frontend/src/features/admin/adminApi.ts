import { api, unwrap } from '../../lib/api'
import type { Role } from '../../lib/session'

/** Unit 68: the GM's admin reads and writes, over routes the server gates to the GM. */

export type Segment = 'ATTORNEY' | 'EMPLOYER_FIRM' | 'INDIVIDUAL'

export type StaffMember = {
  id: string
  displayName: string
  email: string
  role: Role
  brandId: string | null
  teamId: string | null
  active: boolean
  segment: Segment | null
  ghlUserId: string | null
}

export type StaffForm = {
  displayName: string
  email: string
  role: Role
  brandId: string | null
  segment: Segment | null
  ghlUserId: string | null
}

export type Brand = { id: string; name: string; slug: string; active: boolean; currency: string | null; payoutTermDays: number }

export type GhlUser = { id: string; name: string | null; email: string | null }

export type MirroredPipeline = {
  id: string
  mirrorId: string
  name: string
  position: number | null
  purpose: string
  stages: unknown[]
  syncedAt: string | null
  missingSince: string | null
}

export type SyncHealth = {
  open: number
  oldest: string | null
  needsAHuman: number
  drifts: {
    id: string
    entityType: string
    entityId: string
    kind: string
    field: string | null
    localValue: string | null
    ghlValue: string | null
    firstDetectedAt: string
    lastSeenAt: string
    owner: string
    resolution: string
  }[]
  outbox: {
    pending: number
    dead: number
    oldestPending: string | null
    recentlyDead: { id: string; entityId: string; intent: string; attempts: number; lastFailure: string | null; reason: string | null; deadAt: string }[]
  }
}

export const PURPOSES = ['UNASSIGNED', 'SALES', 'MARKETING', 'DELIVERY', 'EXPERT_HIRING'] as const
export const SEGMENTS: readonly Segment[] = ['ATTORNEY', 'EMPLOYER_FIRM', 'INDIVIDUAL']

export const fetchStaff = (signal?: AbortSignal) => unwrap<StaffMember[]>(api.get('/team-members', { signal }))
export const fetchBrands = (signal?: AbortSignal) => unwrap<Brand[]>(api.get('/brands', { signal }))
export const fetchGhlUsers = (signal?: AbortSignal) => unwrap<GhlUser[]>(api.get('/sales/users', { signal }))
export const fetchPipelines = (signal?: AbortSignal) => unwrap<MirroredPipeline[]>(api.get('/ghl/pipelines', { signal }))
export const fetchSyncHealth = (signal?: AbortSignal) => unwrap<SyncHealth>(api.get('/sync/drift', { signal }))
export const fetchGrants = (id: string, signal?: AbortSignal) =>
  unwrap<{ id: string; ghlPipelineIds: string[] }>(api.get(`/team-members/${id}/pipelines`, { signal }))

export const createStaff = (form: StaffForm, password: string) =>
  unwrap<StaffMember>(api.post('/team-members', { ...form, password }))
export const updateStaff = (id: string, form: StaffForm) => unwrap<StaffMember>(api.put(`/team-members/${id}`, form))
export const setStaffActive = (id: string, active: boolean) =>
  unwrap<StaffMember>(api.put(`/team-members/${id}/active`, { active }))
export const setStaffPassword = (id: string, password: string) =>
  unwrap<void>(api.put(`/team-members/${id}/password`, { password }))
export const grantPipeline = (id: string, pipelineId: string) =>
  unwrap(api.put(`/team-members/${id}/pipelines`, { pipelineId }))
export const revokePipeline = (id: string, pipelineId: string) =>
  unwrap(api.delete(`/team-members/${id}/pipelines/${pipelineId}`))
export const setPurpose = (mirrorId: string, purpose: string) =>
  unwrap(api.put(`/ghl/pipelines/${mirrorId}/purpose`, { purpose }))

/** Roles in the order a GM thinks of them, and the words they use. */
export const ROLE_LABEL: Record<Role, string> = {
  GM: 'General manager',
  ADMIN: 'Administrator',
  BRAND_MANAGER: 'Brand manager',
  PROJECT_MANAGER: 'Project manager',
  PROJECT_COORDINATOR: 'Project coordinator',
  CASE_MANAGER: 'Case manager',
  EXPERT_NETWORK_MANAGER: 'Expert network manager',
  SALES: 'Sales',
  MARKETING: 'Marketing',
}

export const isDesk = (role: Role) => role === 'SALES' || role === 'MARKETING'
