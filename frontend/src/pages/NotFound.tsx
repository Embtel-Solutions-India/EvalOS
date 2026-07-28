import { Link } from 'react-router-dom'

export default function NotFound() {
  return (
    <div className="space-y-3">
      <h1 className="text-3xl font-semibold tracking-tight">404</h1>
      <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
        That page doesn&apos;t exist.
      </p>
      <Link
        to="/dashboard"
        className="inline-block text-sm underline-offset-4 hover:underline"
        style={{ color: 'var(--accent-primary)' }}
      >
        Back to dashboard
      </Link>
    </div>
  )
}
