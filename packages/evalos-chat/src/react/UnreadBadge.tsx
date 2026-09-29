import { useChat } from './ChatProvider'

/** The nav badge: total unread across every conversation the caller can open. */
export function UnreadBadge() {
  const total = useChat((s) => s.unreadTotal)
  if (total === 0) return null
  return (
    <span className="ec-badge" aria-label={`${total} unread messages`}>
      {total > 99 ? '99+' : total}
    </span>
  )
}
