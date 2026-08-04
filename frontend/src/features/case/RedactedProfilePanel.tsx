import { useCallback, useState } from 'react'
import type { Role } from '../../lib/session'
import {
  fetchFullProfile,
  fetchRedactedProfile,
  fileProfileToDrive,
  type CaseDetail,
} from './caseApi'
import {
  fullProfileGate,
  hasExpert,
  mayPublishToDrive,
  PREVIEW_SANDBOX,
  type DriveWriteView,
  type ProfileView,
} from './redactionRules'

/**
 * The document the client approves the expert from (Unit 13).
 *
 * Sits beside `ExpertCard`, which names the expert to staff. This one exists so somebody who
 * must *not* know that name can still judge the credentials — otherwise they contact the
 * expert directly and EvalOS is cut out of the work it sourced.
 *
 * Three things happen here: preview the anonymous profile, file it into the case's Drive
 * folder, and — once the case is paid — read the identified one. Nothing is generated until
 * asked: the profile is built from the roster row on every request and stored nowhere, so
 * fetching it on mount would be work for a panel nobody opened.
 */

type Loaded = { which: 'redacted' | 'full'; profile: ProfileView }

export default function RedactedProfilePanel({
  detail,
  role,
}: {
  detail: CaseDetail
  role: Role
}) {
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [filed, setFiled] = useState<DriveWriteView | null>(null)
  const [busy, setBusy] = useState<'redacted' | 'full' | 'drive' | null>(null)
  const [error, setError] = useState<string | null>(null)

  const gate = fullProfileGate(detail)
  const assigned = hasExpert(detail)
  const mayPublish = mayPublishToDrive(role)

  const show = useCallback(async (which: 'redacted' | 'full') => {
    setBusy(which)
    setError(null)
    try {
      const profile = which === 'redacted' ? await fetchRedactedProfile(detail.summary.id) : await fetchFullProfile(detail.summary.id)
      setLoaded({ which, profile })
    } catch (caught: unknown) {
      // The server's own reason, lifted onto the Error by the api interceptor — it names
      // which precondition failed (unpaid, no expert), which no client copy has to restate.
      setError(caught instanceof Error ? caught.message : 'That profile could not be generated')
    } finally {
      setBusy(null)
    }
  }, [detail.summary.id])

  const fileToDrive = useCallback(async () => {
    setBusy('drive')
    setError(null)
    try {
      setFiled(await fileProfileToDrive(detail.summary.id))
    } catch (caught: unknown) {
      // A 409 names the unusable Drive link and a 502 says Drive refused. Both are shown as
      // sent: the panel deliberately does not pre-check the link, because restating a server
      // rule in the client is the copy that goes stale (the Unit 10 lesson).
      setError(caught instanceof Error ? caught.message : 'The profile could not be filed to Drive')
    } finally {
      setBusy(null)
    }
  }, [detail.summary.id])

  return (
    <section
      className="rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold tracking-tight">Expert profile for the client</h2>
        {loaded && (
          <span className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
            {loaded.profile.reference}
          </span>
        )}
      </div>

      {!assigned ? (
        <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
          No expert is assigned yet, so there is no profile to generate.
        </p>
      ) : (
        <>
          <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
            The redacted profile carries the credentials and none of the expert’s name,
            institution or contact details.
          </p>

          <div className="mt-3 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => void show('redacted')}
              disabled={busy !== null}
              className="rounded-md px-2.5 py-1.5 text-xs font-semibold disabled:opacity-50"
              style={{ background: 'var(--accent-primary)', color: '#fff' }}
            >
              {busy === 'redacted' ? 'Generating…' : 'Preview redacted profile'}
            </button>

            {mayPublish && (
              <button
                type="button"
                onClick={() => void fileToDrive()}
                disabled={busy !== null}
                className="rounded-md border px-2.5 py-1.5 text-xs font-semibold disabled:opacity-50"
                style={{ borderColor: 'var(--border-default)', color: 'var(--text-primary)' }}
              >
                {busy === 'drive' ? 'Filing…' : 'Save to the case’s Drive folder'}
              </button>
            )}

            {/*
              Shown whether or not the gate has opened. An unpaid case gets a disabled control
              that says why, not a missing one: absence is indistinguishable from "you are not
              allowed", and a PM cannot act on that.
            */}
            <button
              type="button"
              onClick={() => void show('full')}
              disabled={busy !== null || !gate.released}
              title={gate.reason ?? undefined}
              className="rounded-md border px-2.5 py-1.5 text-xs font-semibold disabled:opacity-50"
              style={{ borderColor: 'var(--border-default)', color: 'var(--text-primary)' }}
            >
              {busy === 'full' ? 'Generating…' : 'Show full profile'}
            </button>
          </div>

          {gate.reason && (
            <p className="mt-2 text-xs" style={{ color: 'var(--status-amber)' }}>
              {gate.reason}
            </p>
          )}

          {filed && (
            <p className="mt-2 text-xs">
              <span style={{ color: 'var(--status-green)' }}>Filed as {filed.reference}. </span>
              {/*
                Drive's own webViewLink, straight from the response. rel=noreferrer as well as
                noopener: this URL identifies a client's case folder and does not belong in a
                Referer header sent to Google.
              */}
              <a
                href={filed.link}
                target="_blank"
                rel="noopener noreferrer"
                style={{ color: 'var(--accent-primary)' }}
              >
                Open it in Drive
              </a>
            </p>
          )}

          {error && (
            <p className="mt-2 text-xs" style={{ color: 'var(--status-red)' }}>
              {error} Nothing was changed.
            </p>
          )}

          {loaded && (
            <div className="mt-3">
              <p className="mb-1 text-xs" style={{ color: 'var(--text-muted)' }}>
                {loaded.which === 'redacted'
                  ? 'Anonymous — safe to send to the client.'
                  : 'Identified. Released because this case is paid.'}
              </p>
              {/*
                An iframe with `sandbox`, never dangerouslySetInnerHTML. The server escapes
                every interpolated roster field, and this withholds every capability the frame
                could otherwise use — see PREVIEW_SANDBOX. srcdoc rather than a blob URL so
                nothing is ever written anywhere, which is the same invariant 14 reason the
                document is generated on demand in the first place. It prints from here.
              */}
              <iframe
                title={`${loaded.which} expert profile for ${detail.summary.caseCode}`}
                sandbox={PREVIEW_SANDBOX}
                srcDoc={loaded.profile.html}
                className="h-80 w-full rounded-md border"
                style={{ borderColor: 'var(--border-default)', background: '#fff' }}
              />
            </div>
          )}
        </>
      )}
    </section>
  )
}
