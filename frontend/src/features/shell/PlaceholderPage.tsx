import { Link, useLocation } from 'react-router-dom'
import { useMe } from '../../lib/authContext'
import { boardPathFor, itemFor } from './navigation'

/**
 * What every nav item other than the dashboard lands on until its unit is built. It
 * reads the label and the "becomes" note off the same nav table the router guards
 * against, so a new route cannot land here unlabelled.
 *
 * A dead end is a design failure, so it also offers the way out — the live board for roles
 * that have one, their dashboard otherwise. Routed through the same allow-list rather than
 * hardcoded, because a link to a screen the reader's role cannot reach is a 403 with extra
 * steps.
 */
export default function PlaceholderPage() {
  const { pathname } = useLocation()
  const me = useMe()
  const item = itemFor(pathname)
  const way = boardPathFor(me.role)

  return (
    <section
      className="rounded-lg border border-dashed p-10"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <p
        className="text-[11px] font-semibold tracking-[0.08em] uppercase"
        style={{ color: 'var(--text-muted)' }}
      >
        Not built yet
      </p>
      <h1 className="mt-1.5 text-xl font-semibold tracking-tight">{item?.label ?? 'Unknown screen'}</h1>
      {/* Two sentences, two elements. The `becomes` strings in the nav table are labels with no
          trailing punctuation ("Document checklist tracking (Unit 10)"), so running the second
          sentence on after one reads as a single broken sentence — which is how it shipped until
          the browser pass looked at it. */}
      <p className="mt-2 max-w-prose text-sm" style={{ color: 'var(--text-muted)' }}>
        {item?.becomes ?? 'This screen arrives with a later unit.'}
      </p>
      <p className="mt-1 max-w-prose text-sm" style={{ color: 'var(--text-muted)' }}>
        Everything else in your scope is already live.
      </p>
      <Link
        to={way.path}
        className="mt-5 inline-block rounded-md px-3 py-1.5 text-sm font-medium text-white"
        style={{ background: 'var(--accent-primary)' }}
      >
        {way.label}
      </Link>
    </section>
  )
}
