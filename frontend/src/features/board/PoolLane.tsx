import type { BoardCard } from './boardRules'

/**
 * The unassigned queue, pinned above the columns for the roles that can act on it.
 *
 * Not a stage — a case in the pool is in `DOC_COLLECTION` like any other and appears in
 * that column too. This is the same work seen through the question that matters to a GM,
 * Brand Manager or PM first thing in the morning: what has nobody picked up.
 *
 * Drawn as a strip of pills rather than a column of cards, because that is the shape of the
 * job: read the list, hand each one to somebody, watch it empty. The accent left edge marks
 * it as the only thing on the board asking for a decision right now, and goes quiet when the
 * queue is empty.
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
      className="rounded-lg border border-l-[3px] p-3"
      style={{
        background: 'var(--bg-surface)',
        borderColor: 'var(--border-default)',
        borderLeftColor: cards.length > 0 ? 'var(--accent-primary)' : 'var(--border-default)',
        boxShadow: 'var(--shadow-card)',
      }}
    >
      <div className="flex items-baseline gap-2">
        <h2 className="text-sm font-semibold tracking-tight">Pool</h2>
        <span className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {cards.length} waiting for a project manager
        </span>
      </div>

      {cards.length === 0 ? (
        <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>
          Every case has an owner.
        </p>
      ) : (
        <ul className="mt-2.5 flex flex-wrap gap-2">
          {cards.map((card) => (
            <li key={card.id}>
              <button
                type="button"
                onClick={() => onAssign(card)}
                className="flex items-baseline gap-2 rounded-md border border-(--border-default) bg-(--bg-raised) px-2.5 py-1.5 text-left transition-colors hover:border-(--accent-primary)"
              >
                <span className="font-mono text-[11px]" style={{ color: 'var(--text-muted)' }}>
                  {card.caseCode}
                </span>
                <span className="text-sm font-medium">{card.clientName ?? 'Unnamed contact'}</span>
                <span className="text-xs font-medium" style={{ color: 'var(--accent-primary)' }}>
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
