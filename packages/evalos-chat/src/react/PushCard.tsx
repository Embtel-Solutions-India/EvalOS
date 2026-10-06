import { useEffect, useState } from 'react'
import { disablePush, enablePush, pushState, type PushState } from '../core/push'
import { useChatClient } from './ChatProvider'
import { BellIcon, CloseIcon } from './icons'

const DISMISSED = 'evalos-chat-push-dismissed'

function wasDismissed(): boolean {
  try {
    return localStorage.getItem(DISMISSED) === '1'
  } catch {
    return false
  }
}

/**
 * "Enable notifications" (Unit 57 §6): the only place permission is asked, and only on a click.
 * Renders nothing where push cannot work (no Push API, or no VAPID keys on the server). The ×
 * hides it in this browser; that is a per-viewer convenience, so it lives in localStorage.
 *
 * @param workerUrl the app's service worker, e.g. `/sw.js`
 */
export function PushCard({ workerUrl }: { workerUrl: string }) {
  const { api } = useChatClient()
  const [state, setState] = useState<PushState | null>(null)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)
  const [dismissed, setDismissed] = useState(wasDismissed)

  useEffect(() => {
    let live = true
    pushState(api, workerUrl).then(
      (s) => live && setState(s),
      () => live && setState('off'),
    )
    return () => {
      live = false
    }
  }, [api, workerUrl])

  if (dismissed || state === null || state === 'unavailable') return null

  const run = (action: typeof enablePush) => {
    setBusy(true)
    setFailed(false)
    action(api, workerUrl)
      .then(setState, () => setFailed(true))
      .finally(() => setBusy(false))
  }

  const dismiss = () => {
    setDismissed(true)
    try {
      localStorage.setItem(DISMISSED, '1')
    } catch {
      // Private mode: it stays hidden for this visit only.
    }
  }

  const [title, detail] =
    state === 'unsupported'
      ? ['Notifications are not available here', 'On an iPhone or iPad, add this site to your Home Screen first.']
      : state === 'denied'
        ? ['Notifications are blocked', "You can allow them in your browser's settings."]
        : state === 'on'
          ? ['Notifications are on', 'You will hear about new messages and case updates even when this site is closed.']
          : ['Get notified of new messages and updates', "Stay up to date even when you're away."]

  return (
    <div className="ec-push" role="region" aria-label="Notifications">
      <span className="ec-push__icon">
        <BellIcon size={18} />
      </span>
      <div className="ec-push__text">
        <p className="ec-push__title">{title}</p>
        <p className="ec-muted">{failed ? 'That did not work. Please try again.' : detail}</p>
      </div>
      {state === 'off' && (
        <button type="button" className="ec-send" disabled={busy} onClick={() => run(enablePush)}>
          {busy ? 'Turning on…' : 'Enable notifications'}
        </button>
      )}
      {state === 'on' && (
        <button type="button" className="ec-link" disabled={busy} onClick={() => run(disablePush)}>
          Turn off
        </button>
      )}
      <button type="button" className="ec-icon-btn" aria-label="Dismiss" onClick={dismiss}>
        <CloseIcon />
      </button>
    </div>
  )
}
