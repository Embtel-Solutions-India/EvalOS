import { describe, expect, it } from 'vitest'
import { createChatApi, cursorOf, type Request } from './api'
import type { Message } from './types'

function recorder() {
  const calls: { method: string; path: string; options?: unknown }[] = []
  const request: Request = async <T,>(method: string, path: string, options?: unknown) => {
    calls.push({ method, path, options })
    return undefined as T
  }
  return { calls, api: createChatApi(request) }
}

describe('createChatApi', () => {
  it('speaks the Unit 57 routes', async () => {
    const { calls, api } = recorder()
    await api.me()
    await api.inbox({ caseId: 'c1' })
    await api.messages('v1', { after: '2026-09-27T10:00:00Z|m1' })
    await api.replies('m1')
    await api.send('v1', 'hello', 'm1')
    await api.react('m1', 'HEART', true)
    await api.react('m1', 'HEART', false)
    await api.read('v1', 'm2')
    await api.unread()
    await api.realtimeToken()

    expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual([
      'GET /me',
      'GET /conversations',
      'GET /conversations/v1/messages',
      'GET /messages/m1/replies',
      'POST /conversations/v1/messages',
      'PUT /messages/m1/reactions/HEART',
      'DELETE /messages/m1/reactions/HEART',
      'POST /conversations/v1/read',
      'GET /unread',
      'GET /realtime/token',
    ])
    expect(calls[1].options).toEqual({ params: { caseId: 'c1', limit: 100 } })
    expect(calls[2].options).toEqual({ params: { after: '2026-09-27T10:00:00Z|m1', limit: 50 } })
    expect(calls[4].options).toEqual({ body: { body: 'hello', parentId: 'm1' } })
    expect(calls[7].options).toEqual({ body: { messageId: 'm2' } })
  })

  it('speaks the edit, delete, search, read-state, presence and typing routes', async () => {
    const { calls, api } = recorder()
    await api.edit('m1', 'fixed')
    await api.remove('m1')
    await api.search('passport', { type: 'INTERNAL' })
    await api.readState('v1')
    await api.presence('v1')
    await api.typing('v1')
    expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual([
      'PUT /messages/m1',
      'DELETE /messages/m1',
      'GET /search',
      'GET /conversations/v1/read-state',
      'GET /conversations/v1/presence',
      'POST /conversations/v1/typing',
    ])
    expect(calls[0].options).toEqual({ body: { body: 'fixed' } })
    expect(calls[2].options).toEqual({ params: { q: 'passport', type: 'INTERNAL' } })
  })

  it('builds the server cursor from a message', () => {
    expect(cursorOf({ id: 'm1', createdAt: '2026-09-27T10:00:00.123Z' } as Message)).toBe('2026-09-27T10:00:00.123Z|m1')
  })
})
