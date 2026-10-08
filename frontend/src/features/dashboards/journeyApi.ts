import { api, unwrap } from '../../lib/api'

/** `PipelineJourneyService.Journey` — one read behind the Sales and Marketing dashboards. */
export type Audience = 'sales' | 'marketing'

export type JourneyMonth = { month: number; leads: number; leadValue: number; won: number; wonValue: number }
export type JourneySource = { source: string; leads: number; value: number; monthlyLeads: number[] }
export type JourneyStage = { stageId: string; name: string; position: number; deals: number; value: number }
export type JourneyDesk = {
  memberId: string
  name: string
  leads: number
  leadValue: number
  won: number
  wonValue: number
  open: number
  openValue: number
}

export type Journey = {
  year: number
  audience: Audience
  /** Null for the whole audience (GM) — the desk being read otherwise. */
  member: { memberId: string; name: string } | null
  months: JourneyMonth[]
  /** Always the whole year's sources, whatever `source` narrowed the rest to. */
  sources: JourneySource[]
  stages: JourneyStage[]
  /** `target` is null for "not set", never 0. */
  target: { kind: 'WON_VALUE' | 'LEADS'; month: string; target: number | null; progress: number }
  /** Only the GM's team view carries desks: they are the drill-down list. */
  desks: JourneyDesk[]
}

export type JourneyQuery = { year: number; audience: Audience; memberId: string | null; source: string | null }

/** The scope is the server's; these parameters only narrow it. */
export function fetchJourney(query: JourneyQuery, signal?: AbortSignal): Promise<Journey> {
  const params: Record<string, string | number> = { year: query.year, audience: query.audience }
  if (query.memberId) params.memberId = query.memberId
  if (query.source) params.source = query.source
  return unwrap<Journey>(api.get('/metrics/journey', { params, signal }))
}
