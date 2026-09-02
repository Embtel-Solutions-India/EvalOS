import type { CaseDetail } from './caseApi'

/**
 * Why this expert was chosen (Unit 32).
 *
 * **Three states, and the middle one is the point** — the same shape `StrategyNotes` uses, for the
 * same reason: a role that may not read this is told the field exists and is not theirs, rather
 * than shown an empty box that looks like nobody has written anything.
 *
 * **Read-only here on purpose.** The rationale is written where the expert is chosen — the
 * `assign-cm` and `reassign-expert` dialogs — so that the reason and the decision are one act. An
 * edit box here would invite a reason written after the fact, which is the one kind of rationale
 * worth nothing.
 */
export default function ExpertRationale({ detail }: { detail: CaseDetail }) {
  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <h2 className="text-sm font-semibold tracking-tight">Expert selection rationale</h2>

      {!detail.maySeeExpertRationale ?
        <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
          Not visible to your role.
        </p>
      : detail.expertSelectionRationale ?
        <p className="mt-2 text-sm whitespace-pre-wrap" style={{ color: 'var(--text-primary)' }}>
          {detail.expertSelectionRationale}
        </p>
      : <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
          No reason recorded. It is written when the expert is assigned or reassigned.
        </p>
      }
    </section>
  )
}
