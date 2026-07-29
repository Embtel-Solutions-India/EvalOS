import { Link } from 'react-router-dom'

/**
 * What a role sees when it reaches a route outside its allow-list — by deep link, a
 * stale bookmark, or a role change since the tab was opened.
 *
 * Deliberately a **screen, not a redirect**: bouncing to the dashboard would leave the
 * user wondering whether the URL was wrong or their access was, and it hides the
 * problem from anyone reporting it. It also says nothing about what lives at the path,
 * because that is information about somebody else's job.
 */
export default function Forbidden() {
  return (
    <section
      className="rounded-lg border p-8"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <p className="font-num text-sm tabular-nums" style={{ color: 'var(--status-red)' }}>
        403
      </p>
      <h1 className="mt-1 text-lg font-semibold tracking-tight">Not available for your role</h1>
      <p className="mt-2 max-w-prose text-sm" style={{ color: 'var(--text-muted)' }}>
        Your account does not have access to this screen. If you think it should, ask your Brand
        Manager or the GM — access is set by role, not per person.
      </p>
      <Link
        to="/dashboard"
        className="mt-5 inline-block rounded-md px-3 py-2 text-sm font-medium text-white"
        style={{ background: 'var(--accent-primary)' }}
      >
        Back to dashboard
      </Link>
    </section>
  )
}
