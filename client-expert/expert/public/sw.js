/*
 * Chat push (Unit 57 §6) — the expert portal's copy of client/public/sw.js. Push only.
 *
 * **Known limit until experts have accounts (Q6):** the credential is the link's fragment, held in
 * memory, so a click with no portal tab open reaches /case without it and the page asks for the
 * link. With a tab open, that tab is focused and keeps its token.
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
  let target = new URL('/case', self.location.origin)
  try {
    const url = new URL(event.notification.data?.url || '/case', self.location.origin)
    if (url.origin === self.location.origin) target = url
  } catch {
    // Fall back to Home.
  }
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      const open = windows.find((w) => new URL(w.url).origin === target.origin)
      if (!open) return self.clients.openWindow(target.href)
      // Focus only: the expert app is one page, and a navigate() reload would drop the token.
      return open.focus()
    }),
  )
})
