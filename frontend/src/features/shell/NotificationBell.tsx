import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { PushCard } from '@evalos/chat'
import { api, unwrap } from '../../lib/api'
import { useMe } from '../../lib/authContext'
import { chatsFor } from '../../lib/chat'

type NotificationView = {
  id: string
  type: string
  caseId: string | null
  body: string
  read: boolean
  createdAt: string
}

/**
 * The staff notification channel over Unit 06's four endpoints. EvalOS sends no email,
 * so this is the only place a staff member is told anything (invariant 14).
 *
 * A native `<details>` element is the dropdown: it already gives a toggle, keyboard
 * activation and a closed-by-default panel. The list is fetched only while the panel is open
 * — a bell nobody clicks should cost one count query, not a page of rows.
 *
 * **The count re-reads every minute and on tab focus** (Unit 70a): somebody else's action — a PM
 * putting a coordinator on a case — raises the notification, and nothing else would tell this
 * browser. Marking read is a POST, so the `api` interceptor refreshes both queries.
 */
export default function NotificationBell() {
  const me = useMe()
  const [open, setOpen] = useState(false)
  const root = useRef<HTMLDetailsElement>(null)

  // A click anywhere outside the panel, or Escape, closes it — not only the bell itself.
  useEffect(() => {
    if (!open) return
    const outside = (event: PointerEvent) => {
      if (root.current && !root.current.contains(event.target as Node)) setOpen(false)
    }
    const escape = (event: KeyboardEvent) => event.key === 'Escape' && setOpen(false)
    document.addEventListener('pointerdown', outside)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('pointerdown', outside)
      document.removeEventListener('keydown', escape)
    }
  }, [open])
  // D37: the bell also pushes. The opt-in needs the chat connection (Marketing has none) and a brand
  // to file the browser under (the GM has none), so those two stay in-app only.
  const mayPush = chatsFor(me.role) && me.role !== 'GM'

  const count = useQuery({
    queryKey: ['notifications', 'count'],
    queryFn: ({ signal }) => unwrap<number>(api.get('/notifications/unread-count', { signal })),
    refetchInterval: 60_000,
  })
  // A failed badge is not worth a visible error: the count stays at its last value.
  const unread = count.data ?? 0

  const list = useQuery({
    queryKey: ['notifications', 'list'],
    queryFn: ({ signal }) => unwrap<NotificationView[]>(api.get('/notifications', { signal })),
    enabled: open,
  })

  const markRead = (id: string) => void api.post(`/notifications/${id}/read`).catch(() => undefined)
  const markAllRead = () => void api.post('/notifications/read-all').catch(() => undefined)

  return (
    <details
      ref={root}
      open={open}
      onToggle={(event) => {
        setOpen(event.currentTarget.open)
      }}
      className="relative"
    >
      {/* The count is a badge pinned to the glyph rather than a pill beside it — the
          template's `.noti-icon .badge` treatment, and it keeps the control square. */}
      <summary
        className="relative grid h-9 w-9 cursor-pointer list-none place-items-center"
        style={{
          background: 'var(--bg-surface)',
          borderRadius: 'var(--radius-md)',
          boxShadow: 'var(--shadow-card)',
          color: 'var(--text-muted)',
        }}
        aria-label={`Notifications, ${unread} unread`}
      >
        <BellIcon />
        {unread > 0 && (
          <span
            className="font-num absolute -top-1 -right-1 grid h-[17px] min-w-[17px] place-items-center px-1 text-[10px] font-semibold tabular-nums text-white"
            style={{ background: 'var(--status-red)', borderRadius: '999px' }}
          >
            {unread}
          </span>
        )}
      </summary>

      <div
        className="absolute right-0 z-10 mt-3 w-88 overflow-hidden"
        style={{
          background: 'var(--bg-surface)',
          borderRadius: 'var(--radius-xl)',
          boxShadow: 'var(--shadow-pop)',
        }}
      >
        <div
          className="flex items-center justify-between border-b px-4 py-3"
          style={{ borderColor: 'var(--border-default)' }}
        >
          <span className="text-sm font-semibold">Notifications</span>
          <button
            type="button"
            onClick={markAllRead}
            disabled={unread === 0}
            className="text-sm font-medium disabled:opacity-40"
            style={{ color: 'var(--accent-primary)' }}
          >
            Mark all read
          </button>
        </div>

        <div className="max-h-96 overflow-y-auto">
          {list.data?.length === 0 && (
            <p className="px-4 py-6 text-sm" style={{ color: 'var(--text-muted)' }}>
              Nothing yet. Case activity for you shows up here.
            </p>
          )}
          {list.isPending && list.isFetching && (
            <p className="px-4 py-6 text-sm" style={{ color: 'var(--text-muted)' }}>
              Loading…
            </p>
          )}
          {list.isError && !list.data && (
            <p className="px-4 py-6 text-sm" style={{ color: 'var(--status-red)' }}>
              {list.error instanceof Error ? list.error.message : 'Could not load notifications'}
            </p>
          )}
          {list.data?.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => markRead(item.id)}
                disabled={item.read}
                className="block w-full border-b px-4 py-3 text-left last:border-b-0"
                style={{
                  borderColor: 'var(--border-default)',
                  background: item.read ? 'transparent' : 'var(--bg-raised)',
                }}
              >
                <span className="block text-sm" style={{ color: 'var(--text-primary)' }}>
                  {item.body}
                </span>
                <span
                  className="font-num mt-1 block text-xs tabular-nums"
                  style={{ color: 'var(--text-muted)' }}
                >
                  {item.type} · {new Date(item.createdAt).toLocaleString()}
                </span>
              </button>
            ))}
        </div>
        {open && mayPush && <PushCard workerUrl="/sw.js" />}
      </div>
    </details>
  )
}

/** Inline rather than a Lucide import: one icon does not earn a dependency. */
function BellIcon() {
  return (
    <svg
      className="h-5 w-5"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
      <path d="M13.7 21a2 2 0 0 1-3.4 0" />
    </svg>
  )
}
