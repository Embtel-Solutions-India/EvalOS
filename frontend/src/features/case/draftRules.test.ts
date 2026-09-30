import { describe, expect, it } from 'vitest'
import { mayComment, mayUploadDraft, submitDraft } from './draftRules'

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

describe('submitDraft', () => {
  it('posts the note only after the upload succeeds', async () => {
    const calls: string[] = []
    const result = await submitDraft(
      { upload: async () => { calls.push('upload') }, note: async (t) => { calls.push(`note:${t}`) } },
      '  see p.2  ',
    )
    expect(calls).toEqual(['upload', 'note:see p.2'])
    expect(result).toBe('submitted')
  })

  it('sends no note when the box is blank', async () => {
    const calls: string[] = []
    await submitDraft({ upload: async () => { calls.push('upload') }, note: async () => { calls.push('note') } }, '   ')
    expect(calls).toEqual(['upload'])
  })

  it('never posts the note when the upload fails, and lets the failure reach the dialog', async () => {
    let noted = false
    await expect(
      submitDraft({ upload: async () => { throw new Error('415') }, note: async () => { noted = true } }, 'hi'),
    ).rejects.toThrow('415')
    expect(noted).toBe(false)
  })

  it('keeps the draft when only the note fails', async () => {
    const result = await submitDraft({ upload: async () => {}, note: async () => { throw new Error('500') } }, 'hi')
    expect(result).toBe('note-failed')
  })
})
