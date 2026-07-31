import type { ImportReport as Report } from './expertRules'

/**
 * The screen that matters in the upload flow: every bad row, with its number, its column
 * and what is wrong with it.
 *
 * **There is no "import anyway".** The vocabulary is closed, so a typo is an error rather
 * than a variant, and a half-imported roster is worse than a rejected one — the ENM cannot
 * tell which half landed. They fix the sheet and upload it again.
 *
 * Row numbers are the sheet's own, header included, so "row 34" is row 34 in the
 * spreadsheet rather than the 34th data row.
 */
export default function ImportReport({
  report,
  onConfirm,
  onStartOver,
  busy,
}: {
  report: Report
  /** Absent once the import has actually run — there is nothing left to confirm. */
  onConfirm?: () => void
  onStartOver: () => void
  busy?: boolean
}) {
  const clean = report.problems.length === 0

  return (
    <section className="flex flex-col gap-4">
      <header>
        <h2 className="text-base font-semibold tracking-tight">
          {report.imported ? 'Imported' : clean ? 'Ready to import' : 'This sheet was not imported'}
        </h2>
        <p className="font-num mt-1 text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
          <span className="font-mono">{report.file}</span> · {report.rows}{' '}
          {report.rows === 1 ? 'row' : 'rows'}
          {clean && ` · ${report.created} new, ${report.updated} to update`}
          {!clean && ` · ${report.problems.length} to fix`}
        </p>
      </header>

      {clean && !report.imported && (
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          Nothing has been written yet. Importing updates the {report.updated} expert
          {report.updated === 1 ? '' : 's'} already on the roster and adds {report.created} new one
          {report.created === 1 ? '' : 's'}. Experts missing from this sheet are left alone — set
          somebody inactive on their profile rather than deleting a row here.
        </p>
      )}

      {report.imported && (
        <p
          className="rounded-lg border p-3 text-sm"
          style={{ background: 'var(--status-green-bg)', borderColor: 'var(--border-default)' }}
        >
          <span className="font-semibold" style={{ color: 'var(--status-green)' }}>
            {report.created} added, {report.updated} updated.
          </span>{' '}
          The roster below has been re-read.
        </p>
      )}

      {!clean && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="mb-2 text-left text-sm" style={{ color: 'var(--text-muted)' }}>
              Fix these in the sheet and upload it again — nothing was written.
            </caption>
            <thead>
              <tr style={{ color: 'var(--text-muted)' }}>
                <Th className="w-16">Row</Th>
                <Th className="w-40">Column</Th>
                <Th>What is wrong</Th>
              </tr>
            </thead>
            <tbody>
              {report.problems.map((problem, index) => (
                <tr
                  key={`${problem.row}-${problem.column}-${index}`}
                  className="border-t"
                  style={{ borderColor: 'var(--border-default)' }}
                >
                  <td className="font-num py-1.5 pr-3 tabular-nums">{problem.row}</td>
                  <td className="py-1.5 pr-3 font-medium">{problem.column}</td>
                  <td className="py-1.5" style={{ color: 'var(--status-red)' }}>
                    {problem.reason}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="flex gap-2">
        {clean && onConfirm && (
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            className="rounded-md px-3 py-1.5 text-sm font-medium text-white disabled:opacity-60"
            style={{ background: 'var(--accent-primary)' }}
          >
            {busy ? 'Importing…' : `Import ${report.rows} ${report.rows === 1 ? 'row' : 'rows'}`}
          </button>
        )}
        <button
          type="button"
          onClick={onStartOver}
          className="rounded-md px-3 py-1.5 text-sm font-medium"
          style={{ background: 'var(--bg-raised)' }}
        >
          {report.imported ? 'Upload another sheet' : 'Choose a different file'}
        </button>
      </div>
    </section>
  )
}

function Th({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <th
      scope="col"
      className={`pb-1 text-left text-[11px] font-semibold tracking-[0.06em] uppercase ${className ?? ''}`}
    >
      {children}
    </th>
  )
}
