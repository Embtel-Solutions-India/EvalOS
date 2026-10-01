import { describe, expect, it, vi } from 'vitest'
import { QUICK_ACTIONS } from '../board/boardRules'
import { endOfDayIso, expertEvidenceRequest, mayManageChecklist, maySetDeadline, nextStep, splitActions, toDateInput } from './caseRules'

// `lib/session` (ROLE_LABELS) reads sessionStorage on import; vitest runs in node.
vi.hoisted(() => {
  const store = new Map<string, string>()
  globalThis.sessionStorage = {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  } as unknown as Storage
})

const byPath = (path: string) => QUICK_ACTIONS.find((a) => a.path === path)!

describe('splitActions', () => {
  it('puts stage transitions and exception exits first, stage-preserving actions under More', () => {
    const { primary, more } = splitActions([byPath('hold'), byPath('send-to-expert'), byPath('resume'), byPath('refund/request')])
    expect(primary.map((a) => a.path)).toEqual(['send-to-expert', 'resume'])
    expect(more.map((a) => a.path)).toEqual(['hold', 'refund/request'])
  })
})

describe('nextStep', () => {
  it("tells the owner what to do and everyone else who it is waiting on", () => {
    expect(nextStep('DRAFT_IN_PROGRESS', 'NONE', 'CASE_MANAGER')).toBe('Your next step: upload and submit the draft')
    expect(nextStep('DRAFT_IN_PROGRESS', 'NONE', 'PROJECT_COORDINATOR')).toBe('Waiting on the Case Manager')
    expect(nextStep('DRAFT_IN_PROGRESS', 'NONE', 'GM')).toBe('Case Manager: upload and submit the draft')
  })
  it('says nothing in an exception state or once nobody owes anything', () => {
    expect(nextStep('DRAFT_IN_PROGRESS', 'ON_HOLD_AWAITING_CLIENT', 'CASE_MANAGER')).toBeNull()
    expect(nextStep('DELIVERED', 'NONE', 'PROJECT_COORDINATOR')).toBeNull()
  })
})

describe('permissions mirrored from the server', () => {
  it('matches PATCH /deadline (GM, PM) and the checklist COORDINATION gate (GM, BM, PC, CM)', () => {
    expect((['GM', 'PROJECT_MANAGER'] as const).every((r) => maySetDeadline(r))).toBe(true)
    expect(maySetDeadline('PROJECT_COORDINATOR')).toBe(false)
    expect((['GM', 'BRAND_MANAGER', 'PROJECT_COORDINATOR', 'CASE_MANAGER'] as const).every((r) => mayManageChecklist(r))).toBe(true)
    expect(mayManageChecklist('PROJECT_MANAGER')).toBe(false)
  })
})

describe('deadline dates', () => {
  it('saves the end of the chosen day in local time', () => {
    const saved = new Date(endOfDayIso('2026-10-04'))
    expect([saved.getFullYear(), saved.getMonth(), saved.getDate(), saved.getHours(), saved.getMinutes()]).toEqual([2026, 9, 4, 23, 59])
  })
  it('round-trips through the date input', () => {
    expect(toDateInput(endOfDayIso('2026-10-04'))).toBe('2026-10-04')
    expect(toDateInput(null)).toBe('')
  })
})

describe('staffing under More (2026-10-01)', () => {
  it('puts both stage-preserving staffing patches under More, never as a button', () => {
    const { primary, more } = splitActions([byPath('expert'), byPath('case-manager'), byPath('assign-cm')])
    expect(primary.map((a) => a.path)).toEqual(['assign-cm'])
    expect(more.map((a) => a.path)).toEqual(['expert', 'case-manager'])
  })
})

describe('expertEvidenceRequest', () => {
  const row = (actorName: string, exceptionState: string | null, note: string | null = null) => ({ actorName, exceptionState, note })
  it('shows the expert request while that hold lasts', () => {
    const trail = [row('Test PM', 'NONE'), row('The expert', 'ON_HOLD_AWAITING_CLIENT', 'Need the 2019 transcript'), row('Test PC', null, 'chased')]
    expect(expertEvidenceRequest(trail, 'ON_HOLD_AWAITING_CLIENT')).toBe('Need the 2019 transcript')
    expect(expertEvidenceRequest(trail, 'NONE')).toBeNull()
  })
  it('does not lend an old request to a later hold', () => {
    const trail = [
      row('The expert', 'ON_HOLD_AWAITING_CLIENT', 'Need the 2019 transcript'),
      row('Test PC', 'NONE'),
      row('Test PC', 'ON_HOLD_AWAITING_CLIENT', 'client travelling'),
    ]
    expect(expertEvidenceRequest(trail, 'ON_HOLD_AWAITING_CLIENT')).toBeNull()
  })
})
