import { useState } from 'react'
import type { CaseDetail } from './caseApi'
import type { Role } from '../../lib/session'

/**
 * Who the deliverable is about, what discipline it needs, and the deadline USCIS set (Unit 33).
 *
 * **The applicant is not the contact.** `clientName` is the person we deal with — the attorney,
 * the agent, the HR contact — and for every client type except an individual the letter is
 * about somebody else entirely, whose name EvalOS did not store at all before this panel. The
 * two are drawn one above the other and labelled, because confusing them is the failure this
 * exists to prevent.
 *
 * **The RFE date sits beside the promised deadline and drives nothing.** The Case Manager reads
 * one and sets the other; a rule that moved the deadline automatically would be a rule nobody
 * could see was wrong.
 *
 * `fieldOfExpertise` is read-only here. It is written where the discipline is actually known —
 * the assignment, by the PM who has just read the documents — and a second place to type it
 * would be a second answer to the same question.
 */
export default function CaseFacts({
  detail,
  role,
  onSave,
}: {
  detail: CaseDetail
  role: Role
  onSave: (applicantName: string | null, rfeDate: string | null) => Promise<void>
}) {
  const [editing, setEditing] = useState(false)
  const [applicant, setApplicant] = useState(detail.applicantName ?? '')
  const [rfeDate, setRfeDate] = useState(detail.rfeDate ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // The same three roles the server admits on PATCH /intake-facts. The Case Manager is in the
  // list deliberately: they hold the paperwork these two facts are read off, and gating them to
  // the PM would mean asking somebody else to type what is on the desk in front of you.
  const mayEdit = role === 'GM' || role === 'PROJECT_MANAGER' || role === 'CASE_MANAGER'

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await onSave(applicant.trim() || null, rfeDate || null)
      setEditing(false)
    } catch (caught: unknown) {
      setError(caught instanceof Error ? caught.message : 'Could not save the case facts')
    } finally {
      setSaving(false)
    }
  }

  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold tracking-tight">Case facts</h2>
        {mayEdit && !editing && (
          <button
            type="button"
            onClick={() => {
              setApplicant(detail.applicantName ?? '')
              setRfeDate(detail.rfeDate ?? '')
              setEditing(true)
            }}
            className="text-sm font-medium"
            style={{ color: 'var(--accent-primary)' }}
          >
            Edit
          </button>
        )}
      </div>

      {editing ? (
        <>
          <div className="mt-2 grid gap-3 sm:grid-cols-2">
            <label className="block text-xs font-medium">
              Applicant (the beneficiary)
              <input
                value={applicant}
                onChange={(event) => setApplicant(event.target.value)}
                maxLength={200}
                className="mt-1 w-full rounded-md border px-2.5 py-1.5 text-sm font-normal"
                style={{ background: 'var(--bg-base)', borderColor: 'var(--border-default)' }}
              />
            </label>
            {/* Native date input: the platform has one, and this is a plain calendar date. */}
            <label className="block text-xs font-medium">
              RFE / filing deadline
              <input
                type="date"
                value={rfeDate}
                onChange={(event) => setRfeDate(event.target.value)}
                className="mt-1 w-full rounded-md border px-2.5 py-1.5 text-sm font-normal"
                style={{ background: 'var(--bg-base)', borderColor: 'var(--border-default)' }}
              />
            </label>
          </div>
          {error && (
            <p className="mt-1 text-xs" style={{ color: 'var(--status-red)' }}>
              {error}
            </p>
          )}
          <div className="mt-2 flex justify-end gap-2">
            <button
              type="button"
              disabled={saving}
              onClick={() => setEditing(false)}
              className="rounded-md px-3 py-1.5 text-sm font-medium disabled:opacity-40"
              style={{ background: 'var(--bg-raised)' }}
            >
              Cancel
            </button>
            <button
              type="button"
              disabled={saving}
              onClick={() => void save()}
              className="rounded-md px-3 py-1.5 text-sm font-medium text-white disabled:opacity-40"
              style={{ background: 'var(--accent-primary)' }}
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </>
      ) : (
        <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-3">
          <Fact
            term="Applicant"
            /* Withheld and unset are different facts and must not share a label, the same rule
               the header follows for the client's name. */
            value={
              detail.maySeeCaseContent
                ? (detail.applicantName ?? 'Not recorded')
                : 'Withheld'
            }
          />
          <Fact term="Contact" value={detail.maySeeCaseContent ? (detail.clientName ?? 'Unnamed') : 'Withheld'} />
          <Fact
            term="Discipline"
            /* Null means no match has been run, never "no discipline". */
            value={detail.fieldOfExpertise ? readable(detail.fieldOfExpertise) : 'No match run yet'}
          />
          <Fact term="RFE deadline" value={detail.rfeDate ?? 'None set'} numeric />
        </dl>
      )}
    </section>
  )
}

function Fact({ term, value, numeric }: { term: string; value: string; numeric?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-[10px] font-medium tracking-[0.06em] uppercase" style={{ color: 'var(--text-muted)' }}>
        {term}
      </dt>
      <dd className={`truncate text-sm ${numeric ? 'font-num tabular-nums' : ''}`} title={value}>
        {value}
      </dd>
    </div>
  )
}

const readable = (value: string): string =>
  value
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
