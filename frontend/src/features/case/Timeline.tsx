import type { TimelineEntry } from './caseApi'

/**
 * The append-only audit trail, read forwards.
 *
 * There is no edit control anywhere on this panel and no endpoint behind one: the trail is
 * the record of what happened (invariant 13), and `AuditEventRepository` has no method that
 * could change it. What the reader sees is a projection — never the stored snapshot.
 */

const ACTION_LABEL: Record<string, string> = {
  CREATED: 'created',
  UPDATED: 'updated',
  ASSIGNED: 'assigned',
  STAGE_CHANGED: 'moved stage',
  DELETED: 'deleted',
  EXPORTED: 'exported',
  LOGIN: 'signed in',
}

function readable(value: string | null): string | null {
  return value ? value.replaceAll('_', ' ').toLowerCase() : null
}

export default function Timeline({ entries }: { entries: readonly TimelineEntry[] }) {
  return (
    <section
      className="rounded-lg border"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <header className="border-b px-4 py-3" style={{ borderColor: 'var(--border-default)' }}>
        <h2 className="text-sm font-semibold tracking-tight">Timeline</h2>
        <p className="mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
          Every change, oldest first. Append-only — nothing here can be edited.
        </p>
      </header>

      {entries.length === 0 ? (
        <p className="px-4 py-6 text-sm" style={{ color: 'var(--text-muted)' }}>
          Nothing recorded yet.
        </p>
      ) : (
        <ol className="max-h-[32rem] overflow-y-auto">
          {entries.map((entry, index) => (
            <li
              key={`${entry.at}-${index}`}
              className="border-b px-4 py-3 last:border-b-0"
              style={{ borderColor: 'var(--border-default)' }}
            >
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-sm font-medium">
                  {entry.actorName} {ACTION_LABEL[entry.action] ?? entry.action.toLowerCase()}
                </span>
                <time
                  className="font-num shrink-0 text-xs tabular-nums"
                  dateTime={entry.at}
                  style={{ color: 'var(--text-muted)' }}
                >
                  {new Date(entry.at).toLocaleString()}
                </time>
              </div>

              {(entry.stage || entry.exceptionState) && (
                <p className="mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
                  {readable(entry.stage)}
                  {entry.exceptionState && entry.exceptionState !== 'NONE'
                    ? ` · ${readable(entry.exceptionState)}`
                    : ''}
                </p>
              )}

              {/* The reason somebody typed is the point of the entry, not decoration. */}
              {entry.note && (
                <p className="mt-1 text-sm" style={{ color: 'var(--text-primary)' }}>
                  “{entry.note}”
                </p>
              )}
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
