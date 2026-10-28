/**
 * A GHL note as plain text (2026-10-28).
 *
 * GHL stores some notes as HTML — its AI call notes are `<p>` blocks with `<span data-cv-variable>` wrappers —
 * and the deal screen printed the markup. This keeps the words and the line breaks and drops the tags; it never
 * renders HTML, so nothing in a note can run or style the page. Text with no tag is returned as it was.
 */
export function noteText(body: string | null | undefined): string {
  if (!body) return ''
  if (!/<[a-z!/]/i.test(body)) return body
  const text = body
    .replace(/<(script|style)\b[\s\S]*?<\/\1\s*>/gi, '')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|li|h[1-6]|tr)\s*>/gi, '\n')
    .replace(/<li\b[^>]*>/gi, '• ')
    .replace(/<[^>]*>/g, '')
  return decodeEntities(text)
    .replace(/\r\n?/g, '\n')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

const NAMED: Record<string, string> = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ',
  lsquo: '‘', rsquo: '’', ldquo: '“', rdquo: '”',
  ndash: '–', mdash: '—', hellip: '…', bull: '•',
}

function decodeEntities(text: string): string {
  return text.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (whole, code: string) => {
    if (code[0] === '#') {
      const point = code[1] === 'x' || code[1] === 'X' ? parseInt(code.slice(2), 16) : parseInt(code.slice(1), 10)
      return Number.isFinite(point) && point > 0 && point <= 0x10ffff ? String.fromCodePoint(point) : whole
    }
    return NAMED[code.toLowerCase()] ?? whole
  })
}
