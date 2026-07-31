import { useCallback, useState } from 'react'
import { useFilters } from '../shell/filtersContext'
import { useMe } from '../../lib/authContext'
import ImportReport from './ImportReport'
import { importSheet, validateSheet } from './expertApi'
import {
  MAPPABLE_FIELDS,
  csvHeaders,
  type ExpertForm,
  type ImportReport as Report,
  guessMapping,
} from './expertRules'

/**
 * Pick a file → map the columns → read the validation report → confirm.
 *
 * The order is the point. Validation is a separate call that writes nothing, so the ENM
 * sees every bad row before anything happens; the import is all-or-nothing, so a sheet with
 * one typo leaves the roster exactly as it was.
 *
 * The mapping is guessed from the headers and then shown for confirmation — the ENM does not
 * have to rename their spreadsheet, and does not have to fill in fifteen dropdowns either.
 * It is sent explicitly with the file; the server never infers it.
 */
export default function SheetUpload({ onImported }: { onImported: () => void }) {
  const me = useMe()
  const { activeBrandId } = useFilters()
  const [file, setFile] = useState<File | null>(null)
  const [headers, setHeaders] = useState<string[]>([])
  const [mapping, setMapping] = useState<Record<string, keyof ExpertForm | ''>>({})
  const [report, setReport] = useState<Report | null>(null)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  // A GM has no brand of their own, so there is nowhere for the rows to land until they
  // pick one. Refused here as well as by the server, because "which roster?" is a question
  // the switcher already answers on every other screen.
  const brandMissing = me.role === 'GM' && !activeBrandId

  const reset = useCallback(() => {
    setFile(null)
    setHeaders([])
    setMapping({})
    setReport(null)
    setFailure(null)
  }, [])

  const onFile = useCallback(async (chosen: File | null) => {
    setReport(null)
    setFailure(null)
    setFile(chosen)
    if (!chosen) return setHeaders([])

    if (chosen.name.toLowerCase().endsWith('.csv')) {
      // Read the header row locally so the mapping can be filled in before uploading. An
      // .xlsx is a zip and is not read here — its headers come back from the server's parse,
      // so those columns are mapped by name typed to match. Reading the file to guess is a
      // convenience; the server's parse is the truth.
      const firstLine = (await chosen.slice(0, 8_192).text()).split(/\r?\n/)[0] ?? ''
      const found = csvHeaders(firstLine)
      setHeaders(found)
      setMapping(guessMapping(found))
      return
    }
    setHeaders([])
    setMapping({})
  }, [])

  const submit = useCallback(
    async (run: boolean) => {
      if (!file) return
      const columns = Object.fromEntries(
        Object.entries(mapping).filter(([, field]) => field !== ''),
      ) as Record<string, string>

      setBusy(true)
      setFailure(null)
      try {
        const answer = run
          ? await importSheet(activeBrandId, file, columns)
          : await validateSheet(activeBrandId, file, columns)
        setReport(answer)
        if (answer.imported) onImported()
      } catch (error: unknown) {
        setFailure(error instanceof Error ? error.message : 'The sheet could not be read')
      } finally {
        setBusy(false)
      }
    },
    [activeBrandId, file, mapping, onImported],
  )

  if (brandMissing) {
    return (
      <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
        Pick a brand in the header first — an imported expert belongs to one roster, and you read
        both.
      </p>
    )
  }

  if (report) {
    return (
      <ImportReport
        report={report}
        busy={busy}
        onConfirm={report.imported ? undefined : () => void submit(true)}
        onStartOver={reset}
      />
    )
  }

  return (
    <section className="flex flex-col gap-4">
      <header>
        <h2 className="text-base font-semibold tracking-tight">Upload a roster sheet</h2>
        <p className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>
          A <code>.csv</code> or <code>.xlsx</code> with one expert per row. Matching is on email, so
          re-uploading the same sheet updates those experts instead of duplicating them. Nothing is
          written until you confirm the report.
        </p>
      </header>

      <div>
        <label className="block text-xs font-medium" htmlFor="expert-sheet">
          Sheet
        </label>
        <input
          id="expert-sheet"
          type="file"
          accept=".csv,.xlsx,.xlsm"
          onChange={(event) => void onFile(event.target.files?.[0] ?? null)}
          className="mt-1 w-full rounded-md border px-2.5 py-1.5 text-sm"
          style={{ background: 'var(--bg-base)', borderColor: 'var(--border-default)' }}
        />
        <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
          The file is parsed and thrown away — EvalOS stores no documents.
        </p>
      </div>

      {file && headers.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold tracking-tight">Map the columns</h3>
          <p className="mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
            Guessed from your headers. Anything left as “Not imported” is ignored. A full name and an
            email are both required.
          </p>
          <ul className="mt-2 flex flex-col gap-1.5">
            {headers.map((header) => (
              <li key={header} className="flex items-center gap-3">
                <span className="w-48 truncate font-mono text-xs" title={header}>
                  {header}
                </span>
                <span aria-hidden style={{ color: 'var(--text-muted)' }}>
                  →
                </span>
                <select
                  aria-label={`Map the column ${header}`}
                  value={mapping[header] ?? ''}
                  onChange={(event) =>
                    setMapping((current) => ({
                      ...current,
                      [header]: event.target.value as keyof ExpertForm | '',
                    }))
                  }
                  className="rounded-md border px-2 py-1 text-sm"
                  style={{ background: 'var(--bg-base)', borderColor: 'var(--border-default)' }}
                >
                  <option value="">Not imported</option>
                  {MAPPABLE_FIELDS.map((candidate) => (
                    <option key={candidate.field} value={candidate.field}>
                      {candidate.label}
                    </option>
                  ))}
                </select>
              </li>
            ))}
          </ul>
          {/* Payment details are absent from the list above, and the server refuses a mapping
              that names one. A bank reference in a spreadsheet that has been mailed around is
              the exposure the encrypted field exists to end. */}
          <p className="mt-2 text-xs" style={{ color: 'var(--text-muted)' }}>
            Payment details are never imported. Set one on the expert's profile.
          </p>
        </div>
      )}

      {file && headers.length === 0 && (
        <div>
          <h3 className="text-sm font-semibold tracking-tight">Map the columns</h3>
          <p className="mt-0.5 text-sm" style={{ color: 'var(--text-muted)' }}>
            The headers of an <code>.xlsx</code> are read on the server, so type them exactly as they
            appear in the sheet.
          </p>
          <ManualMapping mapping={mapping} onChange={setMapping} />
        </div>
      )}

      {failure && (
        <p className="text-sm font-medium" style={{ color: 'var(--status-red)' }}>
          {failure}
        </p>
      )}

      <div>
        <button
          type="button"
          disabled={!file || busy}
          onClick={() => void submit(false)}
          className="rounded-md px-3 py-1.5 text-sm font-medium text-white disabled:opacity-60"
          style={{ background: 'var(--accent-primary)' }}
        >
          {busy ? 'Checking…' : 'Check the sheet'}
        </button>
      </div>
    </section>
  )
}

/**
 * Column names typed by hand, for a format whose headers this screen cannot read.
 *
 * One row per expert field rather than per sheet column, because that is the direction the
 * user knows here: they can see the field list, and they type what their spreadsheet calls
 * it.
 */
function ManualMapping({
  mapping,
  onChange,
}: {
  mapping: Record<string, keyof ExpertForm | ''>
  onChange: (next: Record<string, keyof ExpertForm | ''>) => void
}) {
  const columnFor = (field: keyof ExpertForm) =>
    Object.entries(mapping).find(([, mapped]) => mapped === field)?.[0] ?? ''

  return (
    <ul className="mt-2 flex flex-col gap-1.5">
      {MAPPABLE_FIELDS.map((candidate) => (
        <li key={candidate.field} className="flex items-center gap-3">
          <span className="w-44 text-xs font-medium">{candidate.label}</span>
          <span aria-hidden style={{ color: 'var(--text-muted)' }}>
            ←
          </span>
          <input
            type="text"
            aria-label={`Sheet column for ${candidate.label}`}
            placeholder="column header in your sheet"
            defaultValue={columnFor(candidate.field)}
            onBlur={(event) => {
              const next = Object.fromEntries(
                Object.entries(mapping).filter(([, mapped]) => mapped !== candidate.field),
              ) as Record<string, keyof ExpertForm | ''>
              if (event.target.value.trim()) next[event.target.value.trim()] = candidate.field
              onChange(next)
            }}
            className="w-56 rounded-md border px-2 py-1 text-sm"
            style={{ background: 'var(--bg-base)', borderColor: 'var(--border-default)' }}
          />
        </li>
      ))}
    </ul>
  )
}
