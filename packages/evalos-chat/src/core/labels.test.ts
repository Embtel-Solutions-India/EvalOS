import { describe, expect, it } from 'vitest'
import { accessBanner, authorBadge } from './labels'
import type { Conversation, Message } from './types'

const message = (authorRole: string | null) => ({ authorRole }) as Message
const conversation = (access: Conversation['access'], status: Conversation['status'] = 'ACTIVE') =>
  ({ access, status }) as Conversation

describe('authorBadge', () => {
  it('names a General Manager', () => {
    expect(authorBadge(message('GM'))).toBe('General Manager')
  })
  it('leaves everyone else unlabelled, since members are labelled by the roster', () => {
    expect(authorBadge(message(null))).toBeNull()
  })
})

describe('accessBanner', () => {
  it('tells a viewer the conversation is read only', () => {
    expect(accessBanner(conversation('VIEWER'))).toBe('Oversight — read only')
  })
  it('tells a participating GM they are not a listed member', () => {
    expect(accessBanner(conversation('PARTICIPANT'))).toMatch(/General Manager/)
  })
  it('says the case is closed once it is read only, for a participant too', () => {
    expect(accessBanner(conversation('PARTICIPANT', 'READ_ONLY'))).toMatch(/closed/)
    expect(accessBanner(conversation('MEMBER', 'READ_ONLY'))).toMatch(/closed/)
  })
  it('says nothing to an ordinary member of an open conversation', () => {
    expect(accessBanner(conversation('MEMBER'))).toBeNull()
  })
})
