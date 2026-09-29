import { describe, expect, it } from 'vitest'
import { caseTitle, initials, linkify, rowsWithDays, serviceLabel } from './text'
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

  it('keeps balanced parentheses in URLs', () => {
    expect(linkify('(see https://x.test/a).')).toEqual([
      { text: '(see ' },
      { text: 'https://x.test/a', href: 'https://x.test/a' },
      { text: ').' },
    ])
    expect(linkify('https://en.wikipedia.org/wiki/Foo_(bar).')).toEqual([
      { text: 'https://en.wikipedia.org/wiki/Foo_(bar)', href: 'https://en.wikipedia.org/wiki/Foo_(bar)' },
      { text: '.' },
    ])
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

describe('labels', () => {
  it('initialsTakeTheFirstAndLastWord', () => {
    expect(initials('Dr Miriam Osei')).toBe('DO')
    expect(initials('priya')).toBe('P')
    expect(initials('  ')).toBe('?')
    expect(initials(null)).toBe('?')
  })

  it('aCaseReadsAsItsClientThenItsCode', () => {
    expect(caseTitle({ clientName: 'Ana Ruiz', caseCode: 'IE-1' })).toBe('Ana Ruiz (IE-1)')
    expect(caseTitle({ clientName: null, caseCode: 'IE-1' })).toBe('IE-1')
    expect(serviceLabel('EXPERT_OPINION_LETTER')).toBe('Expert opinion letter')
    expect(serviceLabel(null)).toBeNull()
  })
})
