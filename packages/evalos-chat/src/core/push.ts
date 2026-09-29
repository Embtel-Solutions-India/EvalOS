import type { ChatApi } from './api'

/**
 * Web push for chat (Unit 57 §6): the browser half. The app ships the service worker; this
 * registers it, asks permission (only ever from a click — never on load) and hands the
 * subscription to the server, which pushes "who and where, never what".
 */

export type PushState =
  /** This browser has no Push API (iOS outside a home-screen app, old browsers). */
  | 'unsupported'
  /** The server has no VAPID keys, so there is nothing to subscribe to. */
  | 'unavailable'
  /** The person blocked notifications; only the browser's own settings can undo it. */
  | 'denied'
  | 'off'
  | 'on'

export function pushSupported(): boolean {
  return typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window
}

/** A VAPID public key (base64url, as the server sends it) as the bytes `subscribe` wants. */
export function keyBytes(base64url: string): Uint8Array<ArrayBuffer> {
  const base64 = (base64url + '='.repeat((4 - (base64url.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(base64)
  const bytes = new Uint8Array(new ArrayBuffer(raw.length))
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i)
  return bytes
}

/** Where this browser stands, without prompting. */
export async function pushState(api: ChatApi, workerUrl: string): Promise<PushState> {
  if (!pushSupported()) return 'unsupported'
  try {
    await api.pushPublicKey()
  } catch {
    return 'unavailable'
  }
  if (Notification.permission === 'denied') return 'denied'
  const registration = await navigator.serviceWorker.getRegistration(workerUrl)
  const subscription = await registration?.pushManager.getSubscription()
  if (Notification.permission === 'granted' && subscription) {
    // Re-sent every visit: a shared browser belongs to whoever subscribed it last (ChatApi.subscribe).
    await api.subscribePush(subscription.toJSON())
    return 'on'
  }
  return 'off'
}

/** Call from a click. Resolves to the new state; a refused prompt answers `denied` or `off`. */
export async function enablePush(api: ChatApi, workerUrl: string): Promise<PushState> {
  const permission = await Notification.requestPermission()
  if (permission !== 'granted') return permission === 'denied' ? 'denied' : 'off'
  const registration = await navigator.serviceWorker.register(workerUrl)
  await navigator.serviceWorker.ready
  const subscription =
    (await registration.pushManager.getSubscription()) ??
    (await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: keyBytes(await api.pushPublicKey()),
    }))
  await api.subscribePush(subscription.toJSON())
  return 'on'
}

export async function disablePush(api: ChatApi, workerUrl: string): Promise<PushState> {
  const registration = await navigator.serviceWorker.getRegistration(workerUrl)
  const subscription = await registration?.pushManager.getSubscription()
  if (subscription) {
    // Server first: if that fails the browser is still subscribed, so "on" stays true.
    await api.unsubscribePush(subscription.endpoint)
    await subscription.unsubscribe()
  }
  return 'off'
}
