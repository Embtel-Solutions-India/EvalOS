import { useEffect, useState } from 'react'
import { api, unwrap } from '../../lib/api'
import { useMe } from '../../lib/auth'
import { useFilters } from './filters'

type BrandOption = { id: string; name: string; slug: string }

type State =
  | { status: 'loading' }
  | { status: 'ready'; brands: readonly BrandOption[] }
  | { status: 'failed' }

/**
 * The GM's cross-brand control, and **only** the GM's. Every other role gets a static
 * label: their brand is fixed on `/api/me`, `GET /api/brands` answers them 403, and
 * there is nothing for them to choose between.
 *
 * A native `<select>` rather than a headless combobox — six brands at most, and the
 * platform already ships keyboard handling, mobile pickers and a11y semantics that a
 * hand-rolled dropdown would have to reimplement.
 */
export default function BrandSwitcher() {
  const me = useMe()
  const { activeBrandId, setActiveBrandId } = useFilters()
  const [state, setState] = useState<State>({ status: 'loading' })

  const isGm = me.role === 'GM'

  useEffect(() => {
    if (!isGm) return
    const controller = new AbortController()

    unwrap<BrandOption[]>(api.get('/brands', { signal: controller.signal }))
      .then((brands) => setState({ status: 'ready', brands }))
      .catch(() => {
        if (controller.signal.aborted) return
        setState({ status: 'failed' })
      })

    return () => controller.abort()
  }, [isGm])

  if (!isGm) {
    return (
      <span
        className="rounded-md px-2.5 py-1.5 text-sm font-medium"
        style={{ background: 'var(--bg-raised)', color: 'var(--text-muted)' }}
        title="Your brand is fixed by your role"
      >
        {me.brandId ? 'Your brand' : 'No brand'}
      </span>
    )
  }

  if (state.status !== 'ready') {
    return (
      <span className="text-sm" style={{ color: 'var(--text-muted)' }}>
        {state.status === 'loading' ? 'Loading brands…' : 'Brands unavailable'}
      </span>
    )
  }

  return (
    <label className="flex items-center gap-2 text-sm">
      <span className="sr-only">Active brand</span>
      <select
        className="rounded-md border px-2.5 py-1.5 text-sm"
        style={{
          background: 'var(--bg-surface)',
          borderColor: 'var(--border-default)',
          color: 'var(--text-primary)',
        }}
        value={activeBrandId ?? ''}
        onChange={(event) => setActiveBrandId(event.target.value === '' ? null : event.target.value)}
      >
        <option value="">All brands</option>
        {state.brands.map((brand) => (
          <option key={brand.id} value={brand.id}>
            {brand.name}
          </option>
        ))}
      </select>
    </label>
  )
}
