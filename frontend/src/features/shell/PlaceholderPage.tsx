import { useLocation } from 'react-router-dom'
import { itemFor } from './navigation'

/**
 * What every nav item other than the dashboard lands on until its unit is built. It
 * reads the label and the "becomes" note off the same nav table the router guards
 * against, so a new route cannot land here unlabelled.
 */
export default function PlaceholderPage() {
  const { pathname } = useLocation()
  const item = itemFor(pathname)

  return (
    <section
      className="rounded-lg border p-8"
      style={{ background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }}
    >
      <h1 className="text-lg font-semibold tracking-tight">{item?.label ?? 'Not built yet'}</h1>
      <p className="mt-2 max-w-prose text-sm" style={{ color: 'var(--text-muted)' }}>
        {item?.becomes ?? 'This screen arrives with a later unit.'} The shell, your role scope and the
        notification bell are live — this region is where the screen mounts.
      </p>
    </section>
  )
}
