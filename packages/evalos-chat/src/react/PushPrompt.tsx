import { useEffect, useState } from 'react'
import { enablePush, pushState, type PushState } from '../core/push'
import { useChatClient } from './ChatProvider'
import { BellIcon } from './icons'

/**
 * The "Enable notifications" popup. Opens on **every page load** while this browser is not subscribed
 * (`off`), and never otherwise: a subscribed person is not shown it, nor any other notification nudge
 * (`PushCard` stays silent for them too). "Not now" closes it for this visit only; the next load asks
 * again. Permission is still asked only from the button's click, as browsers require.
 *
 * @param workerUrl the app's service worker, e.g. `/sw.js`
 */
export function PushPrompt({ workerUrl }: { workerUrl: string }) {
  const { api } = useChatClient()
  const [state, setState] = useState<PushState | null>(null)
  const [open, setOpen] = useState(true)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let live = true
    pushState(api, workerUrl).then(
      (s) => live && setState(s),
      () => live && setState(null),
    )
    return () => {
      live = false
    }
  }, [api, workerUrl])

  useEffect(() => {
    if (state !== 'off' || !open) return
    const onKey = (event: KeyboardEvent) => event.key === 'Escape' && setOpen(false)
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [state, open])

  // A refusal at the browser's own prompt lands on `denied`, which this keeps open once to say so.
  if (!open || (state !== 'off' && state !== 'denied') || (state === 'denied' && !failed)) return null

  const enable = () => {
    setBusy(true)
    setFailed(false)
    enablePush(api, workerUrl)
      .then((next) => {
        setState(next)
        if (next === 'on') setOpen(false)
        else setFailed(true)
      }, () => setFailed(true))
      .finally(() => setBusy(false))
  }

  const blocked = state === 'denied'
  return (
    <div className="ec-prompt">
      <div className="ec-prompt__card" role="dialog" aria-modal="true" aria-labelledby="ec-prompt-title">
        <span className="ec-push__icon">
          <BellIcon size={20} />
        </span>
        <p id="ec-prompt-title" className="ec-push__title">
          {blocked ? 'Notifications are blocked' : 'Enable notifications'}
        </p>
        <p className="ec-muted">
          {blocked
            ? "You can allow them in your browser's site settings."
            : failed
              ? 'That did not work. Please try again.'
              : 'Hear about new messages and case updates, even when this site is closed.'}
        </p>
        <div className="ec-prompt__actions">
          <button type="button" className="ec-link" onClick={() => setOpen(false)}>
            {blocked ? 'Close' : 'Not now'}
          </button>
          {!blocked && (
            <button type="button" className="ec-send" disabled={busy} autoFocus onClick={enable}>
              {busy ? 'Turning on…' : 'Enable notifications'}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
