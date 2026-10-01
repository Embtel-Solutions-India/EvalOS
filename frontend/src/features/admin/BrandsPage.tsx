import { useQuery } from '@tanstack/react-query'
import { fetchBrands } from './adminApi'

/**
 * The brands (Unit 68), read-only. A brand carries its GHL webhook token and signing secret, which
 * no screen shows, so creating or changing one is a migration (spec 68 §1.9).
 */
export default function BrandsPage() {
  const query = useQuery({ queryKey: ['admin', 'brands'], queryFn: ({ signal }) => fetchBrands(signal) })

  return (
    <section className="space-y-4">
      <header>
        <h1 className="text-xl font-semibold tracking-tight">Brands</h1>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          The brands EvalOS runs. Adding or changing one is done by the developers, because each carries the GHL
          webhook secret.
        </p>
      </header>
      {query.isError && !query.data && <p className="text-sm" style={{ color: 'var(--status-red)' }}>{query.error.message}</p>}
      {!query.data && !query.isError && <p className="text-sm" style={{ color: 'var(--text-muted)' }}>Loading…</p>}
      {query.data && (
        <div className="overflow-x-auto rounded-lg border" style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs" style={{ color: 'var(--text-muted)' }}>
                <th className="px-4 py-2 font-medium">Brand</th>
                <th className="px-4 py-2 font-medium">Slug</th>
                <th className="px-4 py-2 font-medium">Currency</th>
                <th className="px-4 py-2 font-medium">Payout term</th>
              </tr>
            </thead>
            <tbody>
              {query.data.map((b) => (
                <tr key={b.id} className="border-t" style={{ borderColor: 'var(--border-default)' }}>
                  <td className="px-4 py-2 font-medium">{b.name}</td>
                  <td className="px-4 py-2 font-mono text-xs">{b.slug}</td>
                  <td className="px-4 py-2">{b.currency ?? 'not set'}</td>
                  <td className="px-4 py-2">{b.payoutTermDays} days</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
