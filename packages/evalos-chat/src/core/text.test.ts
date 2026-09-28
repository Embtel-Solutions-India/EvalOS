import { describe, expect, it } from 'vitest'
import { linkify, rowsWithDays } from './text'
import type { Message } from './types'

describe('linkify', () => {
  /** Review Focus 4. */
  it('onlyHttpLinksAreLinksAndNothingIsHtml', () => {
    expect(linkify('<script>alert(1)</script> javascript:alert(1)')).toEqual([
      { text: '<script>alert(1)</script> javascript:alert(1)' },
    ])
    expect(linkify('See https://example.com/a?b=1, then http://x.test.')).toEqual([
      { text: 'See ' },
      { text: 'https://example.com/a?b=1', href: 'https://example.com/a?b=1' },
      { text: ', then ' },
      { text: 'http://x.test', href: 'http://x.test' },
      { text: '.' },
    ])
  })

  it('leaves plain text alone', () => {
    expect(linkify('no links here')).toEqual([{ text: 'no links here' }])
    expect(linkify('')).toEqual([])
  })
})

describe('rowsWithDays', () => {
  const at = (id: string, createdAt: string) => ({ id, createdAt }) as Message

  it('puts a day row before the first message of each local day', () => {
    const rows = rowsWithDays([at('a', '2026-09-26T09:00:00'), at('b', '2026-09-26T18:00:00'), at('c', '2026-09-27T08:00:00')])
    expect(rows.map((r) => (r.kind === 'day' ? `day:${r.key}` : r.message.id))).toEqual([
      'day:2026-09-26', 'a', 'b', 'day:2026-09-27', 'c',
    ])
  })
})
