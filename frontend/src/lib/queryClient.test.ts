import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { afterRequest, refreshesCases } from './queryClient'

function cacheWith(...keys: unknown[][]): QueryClient {
  const client = new QueryClient()
  for (const key of keys) client.setQueryData(key, 'x')
  return client
}

const stale = (client: QueryClient, key: unknown[]) => client.getQueryState(key)?.isInvalidated

describe('afterRequest (Unit 70a)', () => {
  it('a write refreshes every case-shaped query and nothing else', () => {
    const client = cacheWith(['case', 'c1'], ['case', 'c1', 'timeline'], ['board', null, null], ['notifications', 'count'], ['experts'])
    afterRequest('post', '/cases/c1/assign-coordinator', client)
    expect(stale(client, ['case', 'c1'])).toBe(true)
    expect(stale(client, ['case', 'c1', 'timeline'])).toBe(true)
    expect(stale(client, ['board', null, null])).toBe(true)
    expect(stale(client, ['notifications', 'count'])).toBe(true)
    expect(stale(client, ['experts'])).toBe(false)
  })

  it('a read, a chat write and a sign-in refresh nothing', () => {
    expect(refreshesCases('get', '/cases/c1')).toBe(false)
    expect(refreshesCases('post', '/chat/conversations/x/typing')).toBe(false)
    expect(refreshesCases('post', '/auth/login')).toBe(false)
    for (const method of ['post', 'put', 'patch', 'delete']) expect(refreshesCases(method, '/cases/c1/notes')).toBe(true)
  })
})
