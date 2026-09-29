import { useEffect, useState } from 'react'
import { disablePush, enablePush, pushState, type PushState } from '../core/push'
import { useChatClient } from './ChatProvider'

/**
 * "Turn on notifications" (Unit 57 §6): the only place permission is asked, and only on a click.
 * Renders nothing where push cannot work (no Push API, or no VAPID keys on the server).
 *
 * @param workerUrl the app's service worker, e.g. `/sw.js`
 */
export function PushCard({ workerUrl }: { workerUrl: string }) {
  const { api } = useChatClient()
  const [state, setState] = useState<PushState | null>(null)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)

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

  if (state === null || state === 'unavailable') return null

  const run = (action: typeof enablePush) => {
    setBusy(true)
    setFailed(false)
    action(api, workerUrl)
      .then(setState, () => setFailed(true))
      .finally(() => setBusy(false))
  }

  return (
    <div className="ec-push" role="region" aria-label="Notifications">
      {state === 'unsupported' && (
        <p className="ec-muted">
          This browser cannot show notifications. On an iPhone or iPad, add this site to your Home Screen first.
        </p>
      )}
      {state === 'denied' && <p className="ec-muted">Notifications are blocked. You can allow them in your browser's settings.</p>}
      {state === 'off' && (
        <>
          <p>Get a notification when someone writes to you while this site is closed.</p>
          <button type="button" className="ec-send" disabled={busy} onClick={() => run(enablePush)}>
            {busy ? 'Turning on…' : 'Turn on notifications'}
          </button>
        </>
      )}
      {state === 'on' && (
        <p className="ec-muted">
          Notifications are on.{' '}
          <button type="button" className="ec-link" disabled={busy} onClick={() => run(disablePush)}>
            Turn off
          </button>
        </p>
      )}
      {failed && (
        <p className="ec-error" role="alert">
          That did not work. Please try again.
        </p>
      )}
    </div>
  )
}
