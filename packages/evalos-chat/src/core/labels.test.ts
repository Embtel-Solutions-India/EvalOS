import { describe, expect, it } from 'vitest'
import { accessBanner, authorBadge, typingNames } from './labels'
import type { Conversation, Message, Participant } from './types'

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

describe('typingNames', () => {
  const participants = [{ kind: 'STAFF', id: 'pm-1', role: 'PM', name: 'Pat' }] as Participant[]
  const said = (id: string, name: string) => ({ authorKind: 'STAFF', authorId: id, authorName: name }) as Message

  it('names a member from the roster', () => {
    expect(typingNames(['STAFF:pm-1'], participants, [])).toEqual(['Pat'])
  })
  it('names a GM taking part from the messages they sent, since the roster does not list them', () => {
    expect(typingNames(['STAFF:gm-1'], participants, [said('gm-1', 'Gina')])).toEqual(['Gina'])
  })
  it('says Someone only when neither the roster nor any message knows the name', () => {
    expect(typingNames(['STAFF:gm-2'], participants, [said('gm-1', 'Gina')])).toEqual(['Someone'])
    expect(typingNames(['STAFF:pm-1'], undefined, [])).toEqual(['Someone'])
  })
})
