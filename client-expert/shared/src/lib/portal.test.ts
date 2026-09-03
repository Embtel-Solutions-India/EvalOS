import { describe, expect, it } from 'vitest'
import {
  actionFirst,
  CHECKLIST_STATUS,
  failureMessage,
  needsClientAction,
  tokenFromFragment,
  type ChecklistItem,
  type ChecklistItemStatus,
} from '@shared/lib/portal'

const ALL_STATUSES: ChecklistItemStatus[] = ['REQUIRED', 'UPLOADED', 'APPROVED', 'MISSING', 'INCORRECT']

function item(status: ChecklistItemStatus, label = status): ChecklistItem {
  return { id: `id-${status}`, label, status }
}

describe('tokenFromFragment', () => {
  it('takes the fragment verbatim, with or without the hash', () => {
    expect(tokenFromFragment('#abc123')).toBe('abc123')
    expect(tokenFromFragment('abc123')).toBe('abc123')
  })

  it('is null when there is nothing there, so the page can say so instead of sending an empty header', () => {
    expect(tokenFromFragment('')).toBeNull()
    expect(tokenFromFragment('#')).toBeNull()
    expect(tokenFromFragment('#   ')).toBeNull()
  })
})

describe('CHECKLIST_STATUS', () => {
  // If EvalOS adds a sixth ChecklistItemStatus, this is what fails rather than a blank badge
  // shipping to a client. The map is presentation of a server-owned vocabulary — it is not
  // allowed to be missing a value the server can send.
  it('names every status EvalOS can send', () => {
    for (const status of ALL_STATUSES) {
      expect(CHECKLIST_STATUS[status]?.label).toBeTruthy()
    }
  })

  it('paints the two that are the client fault red, and nothing else', () => {
    const red = ALL_STATUSES.filter((status) => CHECKLIST_STATUS[status].variant === 'destructive')
    expect(red).toEqual(['MISSING', 'INCORRECT'])
  })
})

describe('needsClientAction', () => {
  it('is true exactly for the states waiting on the client', () => {
    expect(ALL_STATUSES.filter(needsClientAction)).toEqual(['REQUIRED', 'MISSING', 'INCORRECT'])
  })
})

describe('actionFirst', () => {
  it('lifts outstanding items above finished ones without dropping any', () => {
    const sorted = actionFirst([item('APPROVED'), item('INCORRECT'), item('UPLOADED'), item('REQUIRED')])

    expect(sorted.map((row) => row.status)).toEqual(['INCORRECT', 'REQUIRED', 'APPROVED', 'UPLOADED'])
  })

  it('does not mutate what it was given', () => {
    const original = [item('APPROVED'), item('REQUIRED')]
    actionFirst(original)
    expect(original.map((row) => row.status)).toEqual(['APPROVED', 'REQUIRED'])
  })
})

describe('failureMessage', () => {
  it('never offers a login, because the client has no account to log into', () => {
    for (const status of [401, 403, 429, 502, 500, undefined]) {
      expect(failureMessage(status).toLowerCase()).not.toContain('log in')
      expect(failureMessage(status).toLowerCase()).not.toContain('password')
    }
  })

  it('says the same thing about expired, revoked and unknown, because the server does', () => {
    expect(failureMessage(401)).toContain('no longer valid')
  })

  it('tells a client nothing was lost when the store is down, so they do not re-send in a panic', () => {
    expect(failureMessage(502)).toContain('Nothing was lost')
  })
})
