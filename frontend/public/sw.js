/*
 * Chat push (Unit 57 §6) — the staff app's copy of client-expert/client/public/sw.js. Push only — no caching, no offline.
 *
 * The payload is ChatPushNotifier's { title, body, url, tag }: who and where, never what. `tag` is
 * the conversation id, so a later message replaces the earlier notification rather than stacking.
 */

self.addEventListener('push', (event) => {
  let data = {}
  try {
    data = event.data ? event.data.json() : {}
  } catch {
    // A payload we cannot read still deserves a notification: the person has a message.
  }
  event.waitUntil(
    self.registration.showNotification(data.title || 'New message', {
      body: data.body || '',
      tag: data.tag,
      renotify: Boolean(data.tag),
      icon: '/favicon.svg',
      data: { url: data.url },
    }),
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  // Only ever this portal's own pages: a push URL on another origin is not followed.
  let target = new URL('/', self.location.origin)
  try {
    const url = new URL(event.notification.data?.url || '/', self.location.origin)
    if (url.origin === self.location.origin) target = url
  } catch {
    // Fall back to Home.
  }
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      const open = windows.find((w) => new URL(w.url).origin === target.origin)
      if (!open) return self.clients.openWindow(target.href)
      // Not open.navigate(): routing in place keeps whatever the user had open (AppShell listens).
      open.postMessage({ type: 'evalos:open', path: target.pathname + target.search })
      return open.focus()
    }),
  )
})
