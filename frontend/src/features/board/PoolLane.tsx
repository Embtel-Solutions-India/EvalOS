import type { BoardCard } from './boardRules'

/**
 * The unassigned queue, pinned above the columns for the roles that can act on it.
 *
 * Not a stage — a case in the pool is in `DOC_COLLECTION` like any other and appears in
 * that column too. This is the same work seen through the question that matters to a GM,
 * Brand Manager or PM first thing in the morning: what has nobody picked up.
 */
export default function PoolLane({
  cards,
  onAssign,
}: {
  cards: readonly BoardCard[]
  onAssign: (card: BoardCard) => void
}) {
  return (
    <section
      className="rounded-lg border p-3"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <div className="flex items-baseline gap-2">
        <h2 className="text-sm font-semibold tracking-tight">Pool</h2>
        <span className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {cards.length} unassigned
        </span>
      </div>

      {cards.length === 0 ? (
        <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>
          Every case has an owner.
        </p>
      ) : (
        <ul className="mt-2 flex flex-wrap gap-2">
          {cards.map((card) => (
            <li key={card.id}>
              <button
                type="button"
                onClick={() => onAssign(card)}
                className="flex items-baseline gap-2 rounded-md border px-2.5 py-1.5 text-left"
                style={{ background: 'var(--bg-raised)', borderColor: 'var(--border-default)' }}
              >
                <span className="font-mono text-xs" style={{ color: 'var(--text-muted)' }}>
                  {card.caseCode}
                </span>
                <span className="text-sm font-medium">{card.clientName ?? 'Unnamed contact'}</span>
                <span className="text-xs" style={{ color: 'var(--accent-primary)' }}>
                  Assign PM
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
