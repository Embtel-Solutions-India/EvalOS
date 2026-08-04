import type { CaseDetail } from './caseApi'

/**
 * Where the draft stands: the version count and the two approval chips the draft loops turn
 * on. `ui-context.md` asks the Draft / Report column for these as sub-status chips; this is
 * the same information at full size.
 *
 * Both statuses are null until the loop they belong to has started, which is why "not yet"
 * is a state rather than a blank.
 */

type ChipTone = 'pending' | 'good' | 'bad' | 'idle'

const TONE: Record<ChipTone, { fg: string; bg: string }> = {
  pending: { fg: 'var(--status-amber)', bg: 'var(--status-amber-bg)' },
  good: { fg: 'var(--status-green)', bg: 'var(--status-green-bg)' },
  bad: { fg: 'var(--status-red)', bg: 'var(--status-red-bg)' },
  idle: { fg: 'var(--text-muted)', bg: 'var(--bg-raised)' },
}

function approvalTone(status: string | null): ChipTone {
  if (status === 'APPROVED') return 'good'
  if (status === 'PENDING') return 'pending'
  if (status === 'RETURNED' || status === 'REVISION_REQUESTED') return 'bad'
  return 'idle'
}

const APPROVAL_LABEL: Record<string, string> = {
  PENDING: 'awaiting review',
  APPROVED: 'approved',
  RETURNED: 'returned',
  REVISION_REQUESTED: 'revisions requested',
}

export default function DraftPanel({ detail }: { detail: CaseDetail }) {
  const { pmApprovalStatus, clientApprovalStatus, draftVersionCount } = detail.summary

  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold tracking-tight">Draft</h2>
        <span className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
          {draftVersionCount === 0
            ? 'no draft yet'
            : `version ${draftVersionCount}`}
        </span>
      </div>

      <dl className="mt-3 space-y-2">
        <Row label="PM review" status={pmApprovalStatus} />
        <Row label="Client review" status={clientApprovalStatus} />
      </dl>

      {/*
        `draftLink`, not `driveLink`. This link said "Open the current draft" and pointed at the
        client's own *documents folder* from Unit 09 until Unit 14 gave the draft its own column —
        internally a mislabel, and a leak the moment the same field reached a client-facing screen.
        A case with no draft link says so rather than falling back to anything.
      */}
      {draftVersionCount > 0 && (
        <p className="mt-3 text-sm">
          {detail.draftLink ? (
            <a
              href={detail.draftLink}
              target="_blank"
              rel="noreferrer noopener"
              className="font-medium"
              style={{ color: 'var(--accent-primary)' }}
            >
              Open the current draft ↗
            </a>
          ) : (
            <span style={{ color: 'var(--text-muted)' }}>
              No link on this draft — whoever submitted it did not record where it is.
            </span>
          )}
        </p>
      )}
    </section>
  )
}

function Row({ label, status }: { label: string; status: string | null }) {
  const tone = TONE[approvalTone(status)]
  return (
    <div className="flex items-center justify-between gap-2">
      <dt className="text-sm" style={{ color: 'var(--text-muted)' }}>
        {label}
      </dt>
      <dd
        className="rounded-md px-1.5 py-0.5 text-xs font-semibold"
        style={{ color: tone.fg, background: tone.bg }}
      >
        {status ? (APPROVAL_LABEL[status] ?? status.toLowerCase()) : 'not yet'}
      </dd>
    </div>
  )
}
