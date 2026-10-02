/**
 * The case screen's two shared visual pieces: one button hierarchy and one status vocabulary.
 *
 * **Buttons, three weights.** `primary` is the one move the page expects next (solid accent);
 * `secondary` is any other legal move (outlined); `tertiary` is a quiet text action inside a
 * panel ("Edit", "Manage checklist"). All three share a height, a focus ring and a disabled look,
 * so a row of mixed buttons lines up and a keyboard user can always see where they are.
 */
const BASE =
  'inline-flex items-center justify-center gap-1.5 rounded-md text-sm font-medium transition-colors ' +
  'focus-visible:outline-none focus-visible:shadow-[var(--ring-focus)] disabled:cursor-not-allowed disabled:opacity-45 ' +
  '[&>svg]:h-4 [&>svg]:w-4 [&>svg]:shrink-0'

export const button = {
  primary: `${BASE} h-9 px-3.5 bg-[var(--accent-primary)] text-white hover:bg-[var(--accent-hover)]`,
  secondary:
    `${BASE} h-9 px-3 border border-[var(--border-default)] bg-[var(--bg-surface)] text-[var(--text-primary)] ` +
    'hover:border-[var(--accent-primary)] hover:text-[var(--accent-primary)]',
  tertiary: `${BASE} h-8 px-2 -mx-2 text-[var(--accent-primary)] hover:bg-[var(--accent-soft)]`,
  /** A compact secondary for row-level actions (View / Download on a document). */
  small:
    `${BASE} h-7 px-2 text-xs border border-[var(--border-default)] bg-[var(--bg-surface)] text-[var(--text-primary)] ` +
    'hover:border-[var(--accent-primary)] hover:text-[var(--accent-primary)] [&>svg]:h-3.5 [&>svg]:w-3.5',
} as const

/**
 * **Status, five tones**, each meaning one thing everywhere on the page:
 * `done` (green, finished), `pending` (amber, waiting on someone), `active` (accent, in progress
 * now), `blocked` (red, overdue, refused or stopped), `idle` (grey, not started / not applicable).
 * RAG colours stay reserved for status (ui-context.md); `active` uses the accent so "in progress"
 * never reads as "on track".
 */
export type Tone = 'done' | 'pending' | 'active' | 'blocked' | 'idle'

export const TONES: Record<Tone, { fg: string; bg: string }> = {
  done: { fg: 'var(--status-green)', bg: 'var(--status-green-bg)' },
  pending: { fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)' },
  active: { fg: 'var(--accent-primary)', bg: 'var(--accent-soft)' },
  blocked: { fg: 'var(--status-red)', bg: 'var(--status-red-bg)' },
  idle: { fg: 'var(--text-muted)', bg: 'var(--bg-raised)' },
}

/** The tone of a checklist item, as the coordinator reads it. An unsent item is invisible to the client. */
export function checklistTone(status: string, sent: boolean): Tone {
  if (!sent) return 'idle'
  if (status === 'APPROVED') return 'done'
  if (status === 'UPLOADED') return 'active'
  if (status === 'MISSING' || status === 'INCORRECT') return 'blocked'
  return 'pending'
}

/** The domain's acronyms, which stay in capitals inside a sentence-case label. */
const ACRONYMS = new Set(['PERM', 'RFE', 'NACES', 'PM', 'QC'])

/** "COURSE_BY_COURSE" → "Course by course", "RFE_RESPONSE" → "RFE response": the app's label style. */
export function sentence(value: string | null | undefined): string {
  if (!value) return '—'
  const words = value.split('_').map((word) => (ACRONYMS.has(word) ? word : word.toLowerCase()))
  const label = words.join(' ')
  return label.charAt(0).toUpperCase() + label.slice(1)
}
