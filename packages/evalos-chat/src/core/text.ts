import type { Message } from './types'

export type Part = { text: string; href?: string }

const URL = /\bhttps?:\/\/[^\s<>"']+/gi
const TRAILING = /[.,!?;:)\]}]+$/

/**
 * A body as text parts; only http(s) URLs become links (spec 57 §7: no HTML, ever). Rendered by
 * React as text nodes, so markup in a body stays visible characters.
 */
export function linkify(text: string): Part[] {
  const parts: Part[] = []
  let last = 0
  for (const match of text.matchAll(URL)) {
    const url = match[0].replace(TRAILING, '')
    const start = match.index ?? 0
    if (start > last) parts.push({ text: text.slice(last, start) })
    parts.push({ text: url, href: url })
    last = start + url.length
  }
  if (last < text.length) parts.push({ text: text.slice(last) })
  return parts
}

export type Row = { kind: 'day'; key: string; iso: string } | { kind: 'message'; message: Message }

/** Messages with a day row before the first of each local day; the view formats `iso` for its locale. */
export function rowsWithDays(messages: Message[]): Row[] {
  const rows: Row[] = []
  let current = ''
  for (const message of messages) {
    const d = new Date(message.createdAt)
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    if (key !== current) {
      rows.push({ kind: 'day', key, iso: message.createdAt })
      current = key
    }
    rows.push({ kind: 'message', message })
  }
  return rows
}
