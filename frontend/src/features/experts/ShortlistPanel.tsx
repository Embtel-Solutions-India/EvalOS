import { useEffect, useState } from 'react'
import { fetchShortlist } from './expertApi'
import { FIELD_TAGS, label, type FieldTag } from './expertRules'
import {
  breakdownAddsUp,
  factorShare,
  rankLabel,
  tagLabel,
  type ShortlistCard,
  type ShortlistView,
} from './shortlistRules'

/**
 * The match engine, at the moment of assignment: pick the discipline, get three ranked experts
 * with the arithmetic behind each.
 *
 * **Assist mode, and it shows.** Nothing here assigns anybody — picking a card fills in the
 * expert field of the dialog this panel sits inside, and the full `/api/experts` dropdown stays
 * directly underneath as "choose someone else". A PM who wants the fourth-ranked expert, or one
 * the engine would never propose, is never made to go through the ranking.
 *
 * The field tag is a select over the closed vocabulary, never free text — same reasoning as the
 * roster form: `"mechanical engg"` does not match `MECHANICAL_ENGINEERING`, and a box invites
 * exactly that. It starts unset, because guessing the discipline is the one thing this panel
 * must not do: the PM has just read the documents and a prefilled wrong answer is worse than a
 * prompt.
 */

type State =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; view: ShortlistView }
  | { status: 'failed' }

export default function ShortlistPanel({
  caseId,
  selectedExpertId,
  onPick,
}: {
  caseId: string
  selectedExpertId: string
  onPick: (expertId: string) => void
}) {
  const [fieldTag, setFieldTag] = useState<FieldTag | ''>('')
  const [state, setState] = useState<State>({ status: 'idle' })

  useEffect(() => {
    if (!fieldTag) {
      setState({ status: 'idle' })
      return
    }
    const controller = new AbortController()
    setState({ status: 'loading' })
    fetchShortlist(caseId, fieldTag, controller.signal)
      .then((view) => setState({ status: 'ready', view }))
      .catch(() => {
        if (!controller.signal.aborted) setState({ status: 'failed' })
      })
    return () => controller.abort()
  }, [caseId, fieldTag])

  return (
    <section
      className="rounded-lg border p-3"
      style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}
    >
      <h3 className="text-xs font-semibold tracking-tight">Suggested experts</h3>
      <p className="mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
        Ranked, not decided. Assign anybody available below.
      </p>

      <label className="mt-2.5 block">
        <span className="block text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
          What discipline does this case need?
        </span>
        <select
          value={fieldTag}
          onChange={(event) => setFieldTag(event.target.value as FieldTag | '')}
          className="mt-1 w-full rounded-md border px-2.5 py-1.5 text-sm"
          style={{ background: 'var(--bg-base)', borderColor: 'var(--border-default)' }}
        >
          <option value="">Choose a field…</option>
          {FIELD_TAGS.map((tag) => (
            // `tagLabel`, not the roster's `label`: the server's empty state names the tag in title
            // case, and one panel spelling the same discipline two ways reads as two disciplines.
            <option key={tag} value={tag}>
              {tagLabel(tag)}
            </option>
          ))}
        </select>
      </label>

      <div className="mt-2.5">
        {state.status === 'idle' && (
          <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
            Pick a field to rank the roster.
          </p>
        )}
        {state.status === 'loading' && (
          <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
            Ranking…
          </p>
        )}
        {state.status === 'failed' && (
          <p className="text-xs" style={{ color: 'var(--status-red)' }}>
            Could not rank the roster. Nothing was changed — pick an expert below instead.
          </p>
        )}
        {state.status === 'ready' && state.view.experts.length === 0 && (
          // The server's own sentence, verbatim. It names which factor emptied the list, which is
          // what makes it actionable — the ENM recruits, or frees somebody up.
          <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
            {state.view.emptyReason ?? `No available expert carries the ${tagLabel(fieldTag as FieldTag)} tag.`}
          </p>
        )}
        {state.status === 'ready' && state.view.experts.length > 0 && (
          <ul className="space-y-2">
            {state.view.experts.map((card, index) => (
              <Card
                key={card.id}
                card={card}
                rank={rankLabel(index)}
                selected={card.id === selectedExpertId}
                onPick={() => onPick(card.id)}
              />
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}

function Card({
  card,
  rank,
  selected,
  onPick,
}: {
  card: ShortlistCard
  rank: string
  selected: boolean
  onPick: () => void
}) {
  return (
    <li>
      <button
        type="button"
        onClick={onPick}
        aria-pressed={selected}
        className="w-full rounded-md border p-2.5 text-left"
        style={{
          background: 'var(--bg-surface)',
          borderColor: selected ? 'var(--accent-primary)' : 'var(--border-default)',
        }}
      >
        <div className="flex items-baseline justify-between gap-2">
          <span className="truncate text-sm font-medium">{card.fullName ?? 'Unnamed expert'}</span>
          <span className="font-num tabular-nums text-sm font-semibold">{card.score}</span>
        </div>

        <p className="truncate text-xs" style={{ color: 'var(--text-muted)' }}>
          {[rank, card.institution, card.tier && label(card.tier)].filter(Boolean).join(' · ')}
        </p>

        <dl className="mt-1.5 space-y-1">
          {card.factors.map((factor) => (
            <div key={factor.label} className="flex items-center gap-2">
              <dt className="w-32 shrink-0 truncate text-xs" style={{ color: 'var(--text-muted)' }}>
                {factor.label}
              </dt>
              <div
                className="h-1 flex-1 overflow-hidden rounded-full"
                style={{ background: 'var(--border-default)' }}
                role="img"
                aria-label={`${factor.label}: ${factor.earned} of ${factor.weight}, ${factor.why}`}
              >
                <div
                  className="h-full rounded-full"
                  style={{
                    width: `${factorShare(factor) * 100}%`,
                    background: 'var(--accent-primary)',
                  }}
                />
              </div>
              <dd
                className="font-num tabular-nums w-12 shrink-0 text-right text-xs"
                style={{ color: 'var(--text-muted)' }}
              >
                {factor.earned}/{factor.weight}
              </dd>
            </div>
          ))}
        </dl>

        <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
          {card.factors.map((factor) => factor.why).join(' · ')}
        </p>

        <p className="font-num tabular-nums mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
          {card.activeLoad === 0 ? 'No open cases' : `${card.activeLoad} open case(s)`}
        </p>

        {/* Warnings, not points. A CLIENT_COMPLAINT folded into a number is the one thing a human
            should see before assigning, hidden. */}
        {card.flags.length > 0 && (
          <p className="mt-1 text-xs font-medium" style={{ color: 'var(--status-amber)' }}>
            {card.flags.map((flag) => label(flag)).join(' · ')}
          </p>
        )}

        {!breakdownAddsUp(card) && (
          <p className="mt-1 text-xs" style={{ color: 'var(--status-amber)' }}>
            The breakdown does not add up to {card.score} — treat this ranking as unverified.
          </p>
        )}
      </button>
    </li>
  )
}
