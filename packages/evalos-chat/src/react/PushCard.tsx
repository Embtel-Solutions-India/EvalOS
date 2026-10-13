import { useEffect, useState } from 'react'
import { pushState, type PushState } from '../core/push'
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
 * Why notifications cannot be turned on here: blocked by the browser, or no Push API (an iPhone outside a
 * home-screen app). **Silent while subscribed or merely not subscribed** (2026-10-13): a subscribed person is
 * not nudged anywhere, and asking is `PushPrompt`'s job, on every page load. Renders nothing where the server
 * has no VAPID keys. The × hides it in this browser; that is a per-viewer convenience, so it lives in
 * localStorage.
 *
 * @param workerUrl the app's service worker, e.g. `/sw.js`
 */
export function PushCard({ workerUrl }: { workerUrl: string }) {
  const { api } = useChatClient()
  const [state, setState] = useState<PushState | null>(null)
  const [dismissed] = useState(wasDismissed)
  const [hidden, setHidden] = useState(false)

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

  if ((state !== 'denied' && state !== 'unsupported') || hidden || dismissed) return null

  const dismiss = () => {
    setHidden(true)
    try {
      localStorage.setItem(DISMISSED, '1')
    } catch {
      // Private mode: it stays hidden for this visit only.
    }
  }

  const [title, detail] =
    state === 'unsupported'
      ? ['Notifications are not available here', 'On an iPhone or iPad, add this site to your Home Screen first.']
      : ['Notifications are blocked', "You can allow them in your browser's settings."]

  return (
    <div className="ec-push" role="region" aria-label="Notifications">
      <span className="ec-push__icon">
        <BellIcon size={18} />
      </span>
      <div className="ec-push__text">
        <p className="ec-push__title">{title}</p>
        <p className="ec-muted">{detail}</p>
      </div>
      <button type="button" className="ec-icon-btn" aria-label="Dismiss" onClick={dismiss}>
        <CloseIcon />
      </button>
    </div>
  )
}
