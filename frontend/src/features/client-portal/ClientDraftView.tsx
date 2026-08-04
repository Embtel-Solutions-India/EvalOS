import { useCallback, useState } from 'react'
import { approveDraft, requestRevisions, statusOf } from './portalApi'
import {
  draftReady,
  failureMessage,
  mayAct,
  PROFILE_SANDBOX,
  statusMessage,
  type ClientDraftView as DraftView,
} from './portalRules'

/**
 * The client's one screen: what they ordered, the draft, the anonymous expert profile, and the two
 * answers they can give.
 *
 * One page and no navigation, because a token admits one case — there is no list to return to and
 * nothing else to reach. No shell, no nav, no brand switcher: a client is not a staff user with
 * fewer links.
 *
 * Both actions confirm first. Approving is what commits the letter to an expert's signature, and a
 * revision request goes to a person who then does work — neither is worth a stray click. The confirm
 * is inline rather than a browser dialog, so the wording can say what actually happens next.
 */

type Pending = 'approve' | 'revisions' | null

const SERVICE_LABEL: Record<string, string> = {
  CREDENTIAL_EVALUATION: 'Credential evaluation',
  EXPERT_OPINION_LETTER: 'Expert opinion letter',
  PERM: 'PERM',
  RFE_RESPONSE: 'RFE response',
  TRANSLATION: 'Translation',
}

export default function ClientDraftView({
  view,
  onUpdated,
}: {
  view: DraftView
  onUpdated: (next: DraftView) => void
}) {
  const [pending, setPending] = useState<Pending>(null)
  const [notes, setNotes] = useState('')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  const send = useCallback(
    async (action: 'approve' | 'revisions') => {
      setBusy(true)
      setFailure(null)
      try {
        onUpdated(action === 'approve' ? await approveDraft() : await requestRevisions(notes))
        setPending(null)
        setNotes('')
      } catch (error: unknown) {
        // The client is shown `failureMessage`, not the server's words: a 409's "no draft is with
        // the client" is written for a case manager reading a log.
        setFailure(failureMessage(statusOf(error)))
      } finally {
        setBusy(false)
      }
    },
    [notes, onUpdated],
  )

  const actionable = mayAct(view)

  return (
    <main
      className="mx-auto w-full max-w-2xl px-5 py-10"
      style={{ color: 'var(--text-primary)' }}
    >
      <header>
        <p className="font-mono text-xs tracking-wide" style={{ color: 'var(--text-muted)' }}>
          {view.caseReference}
        </p>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight">
          {view.clientName ? `${view.clientName}, your draft is here` : 'Your draft'}
        </h1>
        {view.serviceType && (
          <p className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>
            {SERVICE_LABEL[view.serviceType] ?? view.serviceType}
            {view.draftVersion > 0 && (
              <>
                {' · '}
                <span className="font-num tabular-nums">version {view.draftVersion}</span>
              </>
            )}
          </p>
        )}
      </header>

      <p className="mt-5 text-sm leading-relaxed">{statusMessage(view)}</p>

      {draftReady(view) && (
        <a
          href={view.draftLink ?? undefined}
          target="_blank"
          // noreferrer as well as noopener: this URL identifies a document about this client and
          // does not belong in a Referer header sent onward.
          rel="noopener noreferrer"
          className="mt-5 inline-block rounded-md px-3.5 py-2 text-sm font-semibold"
          style={{ background: 'var(--accent-primary)', color: '#fff' }}
        >
          Read the draft ↗
        </a>
      )}

      {view.expertProfile && (
        <section className="mt-8">
          <div className="flex items-baseline justify-between gap-2">
            <h2 className="text-sm font-semibold tracking-tight">The expert behind your letter</h2>
            <span className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
              {view.expertReference}
            </span>
          </div>
          <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
            Their credentials, without their name — the identity is released once the engagement is
            confirmed.
          </p>
          {/*
            An iframe with an empty `sandbox`, never dangerouslySetInnerHTML. The document is
            rendered and escaped on the server; withholding every capability here is the second
            layer. `srcdoc` rather than a URL so nothing is stored anywhere.
          */}
          <iframe
            title={`Expert profile for ${view.caseReference}`}
            sandbox={PROFILE_SANDBOX}
            srcDoc={view.expertProfile}
            className="mt-3 h-96 w-full rounded-md border"
            style={{ borderColor: 'var(--border-default)', background: '#fff' }}
          />
        </section>
      )}

      {actionable && (
        <section
          className="mt-8 rounded-lg border p-4"
          style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
        >
          {pending === null && (
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => setPending('approve')}
                className="rounded-md px-3.5 py-2 text-sm font-semibold"
                style={{ background: 'var(--status-green)', color: '#fff' }}
              >
                Approve this draft
              </button>
              <button
                type="button"
                onClick={() => setPending('revisions')}
                className="rounded-md border px-3.5 py-2 text-sm font-semibold"
                style={{ borderColor: 'var(--border-default)', color: 'var(--text-primary)' }}
              >
                Ask for changes
              </button>
            </div>
          )}

          {pending === 'approve' && (
            <div>
              <p className="text-sm font-medium">Approve this draft?</p>
              <p className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>
                It will go to the expert to sign, and cannot be changed afterwards from here.
              </p>
              <Confirm
                label="Yes, approve it"
                busy={busy}
                onConfirm={() => void send('approve')}
                onCancel={() => setPending(null)}
              />
            </div>
          )}

          {pending === 'revisions' && (
            <form
              onSubmit={(event) => {
                event.preventDefault()
                void send('revisions')
              }}
            >
              <label className="block">
                <span className="text-sm font-medium">What needs to change?</span>
                {/*
                  Required, and that is not a formality: a revision request with no reason gives the
                  case manager nothing to work from, and they will only have to come back and ask.
                */}
                <textarea
                  required
                  rows={4}
                  value={notes}
                  onChange={(event) => setNotes(event.target.value)}
                  placeholder="For example: my job title is wrong, or the dates in the second paragraph."
                  className="mt-2 w-full rounded-md border px-2.5 py-2 text-sm"
                  style={{ background: 'var(--bg-base)', borderColor: 'var(--border-default)' }}
                />
              </label>
              <Confirm
                label="Send this request"
                busy={busy}
                submit
                onCancel={() => setPending(null)}
              />
            </form>
          )}

          {failure && (
            <p className="mt-3 text-sm" style={{ color: 'var(--status-red)' }}>
              {failure}
            </p>
          )}
        </section>
      )}

      <footer className="mt-10 text-xs" style={{ color: 'var(--text-muted)' }}>
        This page was opened with a private link. Please do not forward it — anyone who has it can
        see this draft. If it stops working, ask whoever sent it for a new one.
      </footer>
    </main>
  )
}

function Confirm({
  label,
  busy,
  submit,
  onConfirm,
  onCancel,
}: {
  label: string
  busy: boolean
  submit?: boolean
  onConfirm?: () => void
  onCancel: () => void
}) {
  return (
    <div className="mt-3 flex flex-wrap gap-2">
      <button
        type={submit ? 'submit' : 'button'}
        onClick={submit ? undefined : onConfirm}
        disabled={busy}
        className="rounded-md px-3.5 py-2 text-sm font-semibold disabled:opacity-50"
        style={{ background: 'var(--accent-primary)', color: '#fff' }}
      >
        {busy ? 'Sending…' : label}
      </button>
      <button
        type="button"
        onClick={onCancel}
        disabled={busy}
        className="rounded-md px-3.5 py-2 text-sm font-medium disabled:opacity-50"
        style={{ background: 'var(--bg-raised)' }}
      >
        Cancel
      </button>
    </div>
  )
}
