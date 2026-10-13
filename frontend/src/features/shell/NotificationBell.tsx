import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  AlertTriangle,
  BellOff,
  Briefcase,
  CheckCircle2,
  Clock,
  FileWarning,
  GitBranch,
  Inbox,
  PenLine,
  UserPlus,
  Wallet,
} from 'lucide-react'
import { PushCard, PushPrompt, PushRefresh } from '@evalos/chat'
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
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [onlyUnread, setOnlyUnread] = useState(false)
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

  // Opening one marks it read and goes to its case; one without a case is only marked.
  const open_ = (item: NotificationView) => {
    if (!item.read) markRead(item.id)
    if (item.caseId) {
      setOpen(false)
      navigate(`/cases/${item.caseId}`)
    }
  }
  const shown = (list.data ?? []).filter((item) => !onlyUnread || !item.read)
  const groups = byDay(shown)

  return (
    <>
      {mayPush && <PushRefresh workerUrl="/sw.js" />}
      {mayPush && <PushPrompt workerUrl="/sw.js" />}
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
          borderRadius: '999px',
          boxShadow: 'var(--shadow-soft)',
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
        className="absolute right-0 z-10 mt-3 w-[min(24rem,calc(100vw-2rem))] overflow-hidden"
        style={{
          background: 'var(--bg-surface)',
          borderRadius: 'var(--radius-xl)',
          boxShadow: 'var(--shadow-pop)',
        }}
      >
        <div className="px-4 pt-4 pb-3" style={{ background: 'var(--bg-raised)' }}>
          <div className="flex items-center justify-between gap-2">
            <span className="flex items-center gap-2 text-base font-semibold">
              Notifications
              {unread > 0 && (
                <span
                  className="font-num rounded-full px-2 py-0.5 text-xs font-semibold tabular-nums text-white"
                  style={{ background: 'var(--accent-primary)' }}
                >
                  {unread} new
                </span>
              )}
            </span>
            <button
              type="button"
              onClick={markAllRead}
              disabled={unread === 0}
              className="inline-flex items-center gap-1 text-xs font-medium disabled:opacity-40"
              style={{ color: 'var(--accent-primary)' }}
            >
              <CheckCircle2 className="h-3.5 w-3.5" aria-hidden />
              Mark all read
            </button>
          </div>
          <div className="mt-3 inline-flex rounded-lg p-0.5" role="tablist" aria-label="Show" style={{ background: 'var(--bg-surface)' }}>
            {([['All', false], ['Unread', true]] as const).map(([label, value]) => (
              <button
                key={label}
                type="button"
                role="tab"
                aria-selected={onlyUnread === value}
                onClick={() => setOnlyUnread(value)}
                className="rounded-md px-3 py-1 text-xs font-medium"
                style={
                  onlyUnread === value
                    ? { background: 'var(--accent-primary)', color: '#fff' }
                    : { color: 'var(--text-muted)' }
                }
              >
                {label}
              </button>
            ))}
          </div>
        </div>

        <div className="max-h-[26rem] overflow-y-auto">
          {list.isPending && list.isFetching && (
            <p className="px-4 py-8 text-center text-sm" style={{ color: 'var(--text-muted)' }}>
              Loading…
            </p>
          )}
          {list.isError && !list.data && (
            <p className="px-4 py-6 text-sm" style={{ color: 'var(--status-red)' }}>
              {list.error instanceof Error ? list.error.message : 'Could not load notifications'}
            </p>
          )}
          {list.data && shown.length === 0 && (
            <div className="flex flex-col items-center gap-2 px-4 py-10 text-center">
              <span
                className="flex h-11 w-11 items-center justify-center rounded-full"
                style={{ background: 'var(--accent-soft)', color: 'var(--accent-primary)' }}
              >
                <BellOff className="h-5 w-5" aria-hidden />
              </span>
              <p className="text-sm font-medium">{onlyUnread ? "You're all caught up" : 'Nothing yet'}</p>
              <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
                Case activity for you shows up here.
              </p>
            </div>
          )}
          {groups.map(([heading, items]) => (
            <section key={heading}>
              <h3
                className="px-4 pt-3 pb-1 text-[11px] font-semibold tracking-wider uppercase"
                style={{ color: 'var(--text-muted)' }}
              >
                {heading}
              </h3>
              {items.map((item) => {
                const kind = kindOf(item.type)
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => open_(item)}
                    className="flex w-full items-start gap-3 px-4 py-3 text-left transition-colors hover:bg-[var(--bg-raised)]"
                  >
                    <span
                      className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl [&>svg]:h-4 [&>svg]:w-4"
                      style={{ background: `color-mix(in srgb, ${kind.color} 14%, transparent)`, color: kind.color }}
                      aria-hidden
                    >
                      {kind.icon}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="flex items-baseline justify-between gap-2">
                        <span className="truncate text-xs font-semibold" style={{ color: kind.color }}>
                          {kind.label}
                        </span>
                        <span className="font-num shrink-0 text-[11px] tabular-nums" style={{ color: 'var(--text-muted)' }}>
                          {ago(item.createdAt)}
                        </span>
                      </span>
                      <span
                        className="mt-0.5 block text-sm"
                        style={{ color: item.read ? 'var(--text-muted)' : 'var(--text-primary)', fontWeight: item.read ? 400 : 500 }}
                      >
                        {item.body}
                      </span>
                    </span>
                    {!item.read && (
                      <span
                        className="mt-2 h-2 w-2 shrink-0 rounded-full"
                        style={{ background: 'var(--accent-primary)' }}
                        aria-label="Unread"
                      />
                    )}
                  </button>
                )
              })}
            </section>
          ))}
        </div>
        {open && mayPush && (
          <div className="border-t p-3" style={{ borderColor: 'var(--border-default)' }}>
            <PushCard workerUrl="/sw.js" />
          </div>
        )}
      </div>
    </details>
    </>
  )
}

type Kind = { label: string; icon: ReactNode; color: string }

const RED = 'var(--status-red)'
const AMBER = 'var(--status-amber)'
const GREEN = 'var(--status-green)'
const ACCENT = 'var(--accent-primary)'

/**
 * How each kind reads in the panel. Red / amber / green only where the notification IS a status — something late,
 * at risk or done (`ui-context.md`); everything else wears the accent. An unknown type falls back to its own name.
 */
const KINDS: Record<string, Kind> = {
  NEW_LEAD: { label: 'New lead', icon: <UserPlus />, color: ACCENT },
  NEW_CASE_IN_POOL: { label: 'New case in the pool', icon: <Inbox />, color: ACCENT },
  CASE_ASSIGNED: { label: 'Case assigned', icon: <Briefcase />, color: ACCENT },
  STAGE_CHANGED: { label: 'Stage changed', icon: <GitBranch />, color: ACCENT },
  SLA_AT_RISK: { label: 'Deadline at risk', icon: <Clock />, color: AMBER },
  SLA_OVERDUE: { label: 'Deadline overdue', icon: <AlertTriangle />, color: RED },
  EXCEPTION_RAISED: { label: 'Case blocked', icon: <AlertTriangle />, color: AMBER },
  DOC_CHASE_DUE: { label: 'Documents to chase', icon: <FileWarning />, color: AMBER },
  DOCS_ESCALATED: { label: 'Documents escalated', icon: <FileWarning />, color: RED },
  EXPERT_SIGN_AT_RISK: { label: 'Expert signing at risk', icon: <PenLine />, color: AMBER },
  EXPERT_SIGN_OVERDUE: { label: 'Expert signing overdue', icon: <PenLine />, color: RED },
  HIRING_PIPELINE_UPDATED: { label: 'Hiring pipeline', icon: <GitBranch />, color: ACCENT },
  PAYOUT_DUE: { label: 'Payout due', icon: <Wallet />, color: AMBER },
  PAYOUT_CONFIRMED: { label: 'Payout confirmed', icon: <Wallet />, color: GREEN },
}

function kindOf(type: string): Kind {
  return KINDS[type] ?? { label: type.charAt(0) + type.slice(1).toLowerCase().replaceAll('_', ' '), icon: <Inbox />, color: ACCENT }
}

/** Newest first, under Today and Earlier (the server already sends newest first). */
function byDay(items: NotificationView[]): [string, NotificationView[]][] {
  const today = new Date().toDateString()
  const groups: [string, NotificationView[]][] = [
    ['Today', items.filter((item) => new Date(item.createdAt).toDateString() === today)],
    ['Earlier', items.filter((item) => new Date(item.createdAt).toDateString() !== today)],
  ]
  return groups.filter(([, list]) => list.length > 0)
}

/** "now", "5m", "3h", "2d", then the date. */
function ago(iso: string): string {
  const minutes = Math.floor((Date.now() - new Date(iso).getTime()) / 60_000)
  if (minutes < 1) return 'now'
  if (minutes < 60) return `${minutes}m ago`
  if (minutes < 24 * 60) return `${Math.floor(minutes / 60)}h ago`
  if (minutes < 7 * 24 * 60) return `${Math.floor(minutes / (24 * 60))}d ago`
  return new Date(iso).toLocaleDateString()
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
