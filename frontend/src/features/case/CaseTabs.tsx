import { CASE_TABS, type CaseTab } from './useCaseTab'

/** The case page's tab bar. Work holds the chat, so it carries the case's unread count and a new message is seen from Overview. */
export default function CaseTabs({
  tab,
  onChange,
  chatUnread,
}: {
  tab: CaseTab
  onChange: (next: CaseTab) => void
  chatUnread: number
}) {
  return (
    <div
      role="tablist"
      aria-label="Case sections"
      className="scroll-slim mb-4 flex gap-1 overflow-x-auto border-b"
      style={{ borderColor: 'var(--border-default)' }}
    >
      {CASE_TABS.map((t) => {
        const active = tab === t.id
        return (
          <button
            key={t.id}
            type="button"
            role="tab"
            id={`case-tab-${t.id}`}
            aria-selected={active}
            aria-controls="case-tabpanel"
            onClick={() => onChange(t.id)}
            className="-mb-px inline-flex shrink-0 items-center gap-1.5 border-b-2 px-3 py-2 text-sm font-medium focus-visible:shadow-[var(--ring-focus)] focus-visible:outline-none"
            style={{
              borderColor: active ? 'var(--accent-primary)' : 'transparent',
              color: active ? 'var(--accent-primary)' : 'var(--text-muted)',
            }}
          >
            {t.label}
            {t.id === 'work' && chatUnread > 0 && (
              <span
                className="font-num rounded-full px-1.5 text-[11px] leading-5 font-semibold tabular-nums"
                style={{ background: 'var(--accent-primary)', color: '#fff' }}
                aria-label={`${chatUnread} unread messages`}
              >
                {chatUnread > 99 ? '99+' : chatUnread}
              </span>
            )}
          </button>
        )
      })}
    </div>
  )
}
