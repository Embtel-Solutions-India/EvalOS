import { describe, expect, it } from 'vitest'
import { mayComment, mayUploadDraft } from './draftRules'

describe('mayUploadDraft', () => {
  it('lets the case team upload while the draft is being written', () => {
    expect(mayUploadDraft('DRAFT_IN_PROGRESS', 'CASE_MANAGER')).toBe(true)
    expect(mayUploadDraft('DRAFT_IN_PROGRESS', 'PROJECT_COORDINATOR')).toBe(true)
    expect(mayUploadDraft('DRAFT_IN_PROGRESS', 'PROJECT_MANAGER')).toBe(true)
    expect(mayUploadDraft('DRAFT_IN_PROGRESS', 'GM')).toBe(true)
  })

  it('offers nothing at another stage or to another role', () => {
    expect(mayUploadDraft('DRAFT_REVIEW', 'CASE_MANAGER')).toBe(false)
    expect(mayUploadDraft('DRAFT_IN_PROGRESS', 'EXPERT_NETWORK_MANAGER')).toBe(false)
    expect(mayUploadDraft('DRAFT_IN_PROGRESS', 'SALES')).toBe(false)
  })
})

describe('mayComment', () => {
  it('is open only on the PM-approved version while the client answer is pending', () => {
    expect(mayComment('PM_APPROVED', 'PENDING')).toBe(true)
    expect(mayComment('PM_APPROVED', null)).toBe(false)
    expect(mayComment('CHANGES_REQUESTED', 'REVISION_REQUESTED')).toBe(false)
  })
})
