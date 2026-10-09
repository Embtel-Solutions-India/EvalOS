import { useState } from 'react'
import { CaseChatPanel, type ConversationType } from '@evalos/chat'
import { useCaseUnread } from './useCaseUnread'

const TYPES: { type: ConversationType; label: string }[] = [
  { type: 'CLIENT', label: 'Client' },
  { type: 'INTERNAL', label: 'Internal' },
  { type: 'EXPERT', label: 'Expert' },
]

/**
 * The case's three conversations (Unit 57): one tab each, each with its unread count. A type the
 * caller is not in says so inside the panel; GM and Brand Manager read without a composer (oversight).
 *
 * The strip above the messages names the conversations that have unread messages while you are
 * reading another one, and opens them, so nothing new is missed on a tab you are not looking at.
 */
export default function CaseChat({ caseId }: { caseId: string }) {
  const [type, setType] = useState<ConversationType>('CLIENT')
  const unread = useCaseUnread(caseId)
  const elsewhere = TYPES.filter((t) => t.type !== type && unread[t.type] > 0)

  return (
    <section
      className="flex h-[min(40rem,calc(100svh-15rem))] min-h-[22rem] min-w-0 flex-col gap-2 rounded-[1.25rem] p-4"
      style={{ background: 'var(--bg-surface)', boxShadow: 'var(--shadow-soft)' }}
    >
      <div className="flex gap-1" role="tablist" aria-label="Conversations">
        {TYPES.map((t) => (
          <button
            key={t.type}
            type="button"
            role="tab"
            aria-selected={type === t.type}
            onClick={() => setType(t.type)}
            className="inline-flex items-center gap-1.5 rounded-md px-3 py-1 text-sm font-medium focus-visible:shadow-[var(--ring-focus)] focus-visible:outline-none"
            style={
              type === t.type
                ? { background: 'var(--accent-soft)', color: 'var(--accent-primary)' }
                : { color: 'var(--text-muted)' }
            }
          >
            {t.label}
            {unread[t.type] > 0 && <span className="ec-badge">{unread[t.type]}</span>}
          </button>
        ))}
      </div>

      {elsewhere.length > 0 && (
        <div
          role="status"
          className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg px-3 py-2 text-sm"
          style={{ background: 'var(--accent-soft)', color: 'var(--accent-primary)' }}
        >
          <span className="font-medium">Unread elsewhere</span>
          {elsewhere.map((t) => (
            <button
              key={t.type}
              type="button"
              onClick={() => setType(t.type)}
              className="font-medium underline underline-offset-2"
            >
              {unread[t.type]} in {t.label}
            </button>
          ))}
        </div>
      )}

      <CaseChatPanel key={type} caseId={caseId} type={type} />
    </section>
  )
}
