import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { SheetContent, SheetRoot } from '../../components/ui/dialog'
import { useMe } from '../../lib/authContext'
import { fetchBrands, updateBrand, type Brand } from './adminApi'

/**
 * The brands (Unit 68). The GM reads them; the Administrator also edits a brand's name, currency and payout term
 * (D83). Creating a brand, and its GHL webhook token and signing secret, stay with the developers — no screen
 * shows them.
 */
export default function BrandsPage() {
  const me = useMe()
  const query = useQuery({ queryKey: ['admin', 'brands'], queryFn: ({ signal }) => fetchBrands(signal) })
  const [editing, setEditing] = useState<Brand | null>(null)
  const canEdit = me.role === 'ADMIN'

  return (
    <section className="space-y-4">
      <header>
        <h1 className="text-xl font-semibold tracking-tight">Brands</h1>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          The brands EvalOS runs.{' '}
          {canEdit
            ? 'You can change a brand’s name, currency and payout term. Adding a brand is done by the developers, because each carries the GHL webhook secret.'
            : 'Adding or changing one is done by an administrator.'}
        </p>
      </header>
      {query.isError && !query.data && <p className="text-sm" style={{ color: 'var(--status-red)' }}>{query.error.message}</p>}
      {!query.data && !query.isError && <p className="text-sm" style={{ color: 'var(--text-muted)' }}>Loading…</p>}
      {query.data && (
        <div className="relative overflow-x-auto rounded-lg border" style={{ borderColor: 'var(--border-default)', background: 'var(--bg-raised)' }}>
          {/* `relative`: the sr-only Edit header is absolutely positioned; without a positioned box it escapes this
              scroll and widens the whole page on a phone. */}
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs" style={{ color: 'var(--text-muted)' }}>
                <th className="px-4 py-2 font-medium">Brand</th>
                <th className="px-4 py-2 font-medium">Slug</th>
                <th className="px-4 py-2 font-medium">Currency</th>
                <th className="px-4 py-2 font-medium">Payout term</th>
                {canEdit && <th className="px-4 py-2"><span className="sr-only">Edit</span></th>}
              </tr>
            </thead>
            <tbody>
              {query.data.map((b) => (
                <tr key={b.id} className="border-t" style={{ borderColor: 'var(--border-default)' }}>
                  <td className="px-4 py-2 font-medium">{b.name}</td>
                  <td className="px-4 py-2 font-mono text-xs">{b.slug}</td>
                  <td className="px-4 py-2">{b.currency ?? 'not set'}</td>
                  <td className="px-4 py-2 whitespace-nowrap">{b.payoutTermDays} days</td>
                  {canEdit && (
                    <td className="px-4 py-2 text-right">
                      <button type="button" className="btn" onClick={() => setEditing(b)}>
                        Edit
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && (
        <BrandSheet
          brand={editing}
          onClose={() => setEditing(null)}
          onDone={() => {
            void query.refetch()
            setEditing(null)
          }}
        />
      )}
    </section>
  )
}

const INPUT = 'mt-1 w-full rounded-md border px-2.5 py-1.5 text-sm'
const INPUT_STYLE = { borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }

function BrandSheet({ brand, onClose, onDone }: { brand: Brand; onClose: () => void; onDone: () => void }) {
  // Copied once when the sheet opens, so a background re-read never overwrites what is being typed.
  const [name, setName] = useState(brand.name)
  const [currency, setCurrency] = useState(brand.currency ?? '')
  const [days, setDays] = useState(String(brand.payoutTermDays))
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  const save = async () => {
    setBusy(true)
    setFailure(null)
    try {
      await updateBrand(brand.id, { name, currency: currency.trim() || null, payoutTermDays: Number(days) })
      onDone()
    } catch (error: unknown) {
      setFailure(error instanceof Error ? error.message : 'The brand was not saved')
    } finally {
      setBusy(false)
    }
  }

  return (
    <SheetRoot open onOpenChange={(next) => !next && onClose()}>
      <SheetContent
        title={brand.name}
        description={`Slug ${brand.slug} — it does not change, because settings and seeds refer to it.`}
        footer={
          <button type="button" className="btn chip-accent" disabled={busy} onClick={() => void save()}>
            {busy ? 'Saving…' : 'Save changes'}
          </button>
        }
      >
        <div className="flex flex-col gap-3">
          {failure && (
            <p className="text-sm" style={{ color: 'var(--status-red)' }} role="alert">
              {failure}
            </p>
          )}
          <label className="text-sm">
            Name
            <input className={INPUT} style={INPUT_STYLE} value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <label className="text-sm">
            Currency
            <input
              className={INPUT}
              style={INPUT_STYLE}
              value={currency}
              maxLength={3}
              placeholder="USD"
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
            />
            <span className="mt-1 block text-xs" style={{ color: 'var(--text-muted)' }}>
              A three-letter ISO code. Payouts and fees are shown in it.
            </span>
          </label>
          <label className="text-sm">
            Payout term (days)
            <input
              className={INPUT}
              style={INPUT_STYLE}
              type="number"
              inputMode="numeric"
              min={0}
              max={365}
              value={days}
              onChange={(e) => setDays(e.target.value)}
            />
          </label>
        </div>
      </SheetContent>
    </SheetRoot>
  )
}
