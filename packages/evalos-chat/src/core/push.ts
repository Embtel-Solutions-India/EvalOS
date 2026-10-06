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

/** Whether a browser subscription was made with this VAPID key. Unreadable options count as a match. */
export function sameKey(subscription: PushSubscription, key: Uint8Array): boolean {
  const have = subscription.options?.applicationServerKey
  if (!have) return true
  const bytes = new Uint8Array(have)
  return bytes.length === key.length && bytes.every((b, i) => b === key[i])
}

/**
 * Keeps a person who already allowed notifications actually reachable, **without ever prompting**.
 * Run on every signed-in load. A subscription silently stops working when (a) the server's VAPID key
 * was changed or the person subscribed against another environment — the push service then answers
 * 403 forever while this browser still reads "on"; (b) the browser expired or dropped it, which
 * Chrome does for sites not visited in a while; or (c) the server deleted it after a 404/410. Each
 * is repaired here by subscribing again with the current key and telling the server.
 */
export async function refreshPush(api: ChatApi, workerUrl: string): Promise<void> {
  if (!pushSupported() || Notification.permission !== 'granted') return
  const key = keyBytes(await api.pushPublicKey())
  const registration = await navigator.serviceWorker.register(workerUrl)
  await navigator.serviceWorker.ready
  let subscription = await registration.pushManager.getSubscription()
  if (subscription && !sameKey(subscription, key)) {
    await subscription.unsubscribe()
    subscription = null
  }
  subscription ??= await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key })
  await api.subscribePush(subscription.toJSON())
}

/** Call from a click. Resolves to the new state; a refused prompt answers `denied` or `off`. */
export async function enablePush(api: ChatApi, workerUrl: string): Promise<PushState> {
  const permission = await Notification.requestPermission()
  if (permission !== 'granted') return permission === 'denied' ? 'denied' : 'off'
  await refreshPush(api, workerUrl)
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
