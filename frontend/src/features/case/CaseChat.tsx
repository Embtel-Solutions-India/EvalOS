import { useState } from 'react'
import { CaseChatPanel, type ConversationType } from '@evalos/chat'

const TYPES: { type: ConversationType; label: string }[] = [
  { type: 'CLIENT', label: 'Client' },
  { type: 'INTERNAL', label: 'Internal' },
  { type: 'EXPERT', label: 'Expert' },
]

/**
 * The case's three conversations (Unit 57): one tab each. A type the caller is not in says so
 * inside the panel; GM and Brand Manager read without a composer (oversight).
 */
export default function CaseChat({ caseId }: { caseId: string }) {
  const [type, setType] = useState<ConversationType>('CLIENT')

  return (
    <section
      className="flex h-[32rem] flex-col gap-2 rounded-lg border p-4"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <div className="flex gap-1" role="tablist" aria-label="Conversations">
        {TYPES.map((t) => (
          <button
            key={t.type}
            type="button"
            role="tab"
            aria-selected={type === t.type}
            onClick={() => setType(t.type)}
            className="rounded-md px-3 py-1 text-sm font-medium"
            style={
              type === t.type
                ? { background: 'var(--accent-soft)', color: 'var(--accent-primary)' }
                : { color: 'var(--text-muted)' }
            }
          >
            {t.label}
          </button>
        ))}
      </div>
      <CaseChatPanel key={type} caseId={caseId} type={type} />
    </section>
  )
}
