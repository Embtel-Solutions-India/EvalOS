import { useCallback, useEffect, useState } from 'react'
import { api, unwrap } from '../../lib/api'

type NotificationView = {
  id: string
  type: string
  caseId: string | null
  body: string
  read: boolean
  createdAt: string
}

type ListState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; items: readonly NotificationView[] }
  | { status: 'failed'; message: string }

/**
 * The staff notification channel over Unit 06's four endpoints. EvalOS sends no email,
 * so this is the only place a staff member is told anything (invariant 14).
 *
 * A native `<details>` element is the dropdown: it already gives a toggle, keyboard
 * activation and a closed-by-default panel. The list is fetched when the panel opens
 * rather than on mount — a bell nobody clicks should cost one count query, not a page
 * of rows.
 */
export default function NotificationBell() {
  const [unread, setUnread] = useState(0)
  const [list, setList] = useState<ListState>({ status: 'idle' })
  const [open, setOpen] = useState(false)

  const refreshCount = useCallback(async (signal?: AbortSignal) => {
    setUnread(await unwrap<number>(api.get('/notifications/unread-count', { signal })))
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    refreshCount(controller.signal).catch(() => {
      // A failed badge is not worth a visible error: the count stays at its last value
      // and the next open re-reads it.
    })
    return () => controller.abort()
  }, [refreshCount])

  const loadList = useCallback(async () => {
    setList({ status: 'loading' })
    try {
      setList({ status: 'ready', items: await unwrap<NotificationView[]>(api.get('/notifications')) })
    } catch (error: unknown) {
      setList({
        status: 'failed',
        message: error instanceof Error ? error.message : 'Could not load notifications',
      })
    }
  }, [])

  /** Both mark routes answer the new badge value, so one call repaints everything. */
  const markRead = useCallback(
    async (id: string) => {
      setUnread(await unwrap<number>(api.post(`/notifications/${id}/read`)))
      await loadList()
    },
    [loadList],
  )

  const markAllRead = useCallback(async () => {
    setUnread(await unwrap<number>(api.post('/notifications/read-all')))
    await loadList()
  }, [loadList])

  return (
    <details
      open={open}
      onToggle={(event) => {
        const isOpen = event.currentTarget.open
        setOpen(isOpen)
        if (isOpen) void loadList()
      }}
      className="relative"
    >
      <summary
        className="flex cursor-pointer list-none items-center gap-1.5 rounded-md px-2.5 py-1.5 text-sm font-medium"
        style={{ background: 'var(--bg-raised)' }}
        aria-label={`Notifications, ${unread} unread`}
      >
        <BellIcon />
        {unread > 0 && (
          <span
            className="font-num rounded-md px-1.5 text-xs font-semibold tabular-nums text-white"
            style={{ background: 'var(--status-red)' }}
          >
            {unread}
          </span>
        )}
      </summary>

      <div
        className="absolute right-0 z-10 mt-2 w-88 rounded-xl border shadow-lg"
        style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
      >
        <div
          className="flex items-center justify-between border-b px-4 py-3"
          style={{ borderColor: 'var(--border-default)' }}
        >
          <span className="text-sm font-semibold">Notifications</span>
          <button
            type="button"
            onClick={() => void markAllRead()}
            disabled={unread === 0}
            className="text-sm font-medium disabled:opacity-40"
            style={{ color: 'var(--accent-primary)' }}
          >
            Mark all read
          </button>
        </div>

        <div className="max-h-96 overflow-y-auto">
          {list.status === 'ready' && list.items.length === 0 && (
            <p className="px-4 py-6 text-sm" style={{ color: 'var(--text-muted)' }}>
              Nothing yet. Case activity for you shows up here.
            </p>
          )}
          {list.status === 'loading' && (
            <p className="px-4 py-6 text-sm" style={{ color: 'var(--text-muted)' }}>
              Loading…
            </p>
          )}
          {list.status === 'failed' && (
            <p className="px-4 py-6 text-sm" style={{ color: 'var(--status-red)' }}>
              {list.message}
            </p>
          )}
          {list.status === 'ready' &&
            list.items.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => void markRead(item.id)}
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
      </div>
    </details>
  )
}

/** Inline rather than a Lucide import: one icon does not earn a dependency. */
function BellIcon() {
  return (
    <svg
      className="h-4 w-4"
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
