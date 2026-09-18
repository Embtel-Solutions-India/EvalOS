import { useEffect, useState } from 'react'

import {
  fetchRequestDocuments,
  requestDocumentUrl,
  type RequestDocument,
} from './opportunityApi'

/**
 * The documents the client sent with their request — Unit 53 (D33, D34).
 *
 * **The other half of the review step.** `DealApplication` beside this shows what the client
 * *said*; this shows what they *sent*. D34 put them on one screen because a salesperson pricing
 * the work reads both in one act — the answers describe a degree, and the transcript is the degree.
 *
 * **It renders nothing for a deal with no documents**, exactly as the application panel renders
 * nothing for a deal with no request. Most of the board is deals somebody phoned in, and a panel
 * saying "no documents" on every card is noise — worse, it is indistinguishable from a client who
 * sent none, which is the ambiguity `DealPage`'s own note said to avoid until this table existed.
 *
 * **A five-minute URL is fetched on the click, never rendered into an `href` at load.** A presigned
 * URL is a credential: putting one in the DOM leaves it there for as long as the tab is open, and
 * it expires while the reader is still looking at the page. So the row is a button, and the button
 * asks for a fresh URL and opens it.
 */
export default function DealDocuments({ opportunityId }: { opportunityId: string }) {
  const [documents, setDocuments] = useState<readonly RequestDocument[]>([])
  const [error, setError] = useState<string | null>(null)
  const [opening, setOpening] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    fetchRequestDocuments(opportunityId, controller.signal)
      .then(setDocuments)
      .catch((failure) => {
        // An aborted request is the effect cleaning up after itself, not a failure to report.
        if (!controller.signal.aborted) {
          setError(failure instanceof Error ? failure.message : 'Could not load the documents')
        }
      })
    return () => controller.abort()
  }, [opportunityId])

  async function open(document: RequestDocument) {
    setOpening(document.id)
    setError(null)
    try {
      // `noopener` because the opened tab is a presigned S3 URL: without it that page gets a
      // handle on this one through `window.opener`.
      window.open(await requestDocumentUrl(opportunityId, document.id), '_blank', 'noopener')
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not open that document')
    } finally {
      setOpening(null)
    }
  }

  if (error && documents.length === 0) {
    return <p className="border-t border-slate-200 pt-2 text-xs text-rose-700">{error}</p>
  }
  if (documents.length === 0) return null

  return (
    <section className="space-y-2 border-t border-slate-200 pt-2">
      <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">
        Documents sent with the request
      </p>

      <ul className="space-y-1">
        {documents.map((document) => (
          <li key={document.id} className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
            <button
              type="button"
              onClick={() => void open(document)}
              disabled={opening === document.id}
              className="text-left text-sm text-blue-600 hover:underline disabled:opacity-50"
            >
              {opening === document.id ? 'Opening…' : document.filename}
            </button>
            <span className="text-xs text-slate-500">
              {formatSize(document.sizeBytes)}
              {' · '}
              {new Date(document.uploadedAt).toLocaleDateString()}
            </span>
            {/*
              Only when it HAS been carried. A Coordinator asks "is this on the case yet"; the
              answer "not yet" is the default state of every document on an open deal, and
              labelling it would put a badge on every row to say nothing.
            */}
            {document.carriedToCase && (
              <span className="rounded bg-emerald-100 px-1.5 py-0.5 text-[11px] font-medium text-emerald-900">
                on the case
              </span>
            )}
          </li>
        ))}
      </ul>

      {error && <p className="text-xs text-rose-700">{error}</p>}
    </section>
  )
}

/**
 * Bytes as something a person reads.
 *
 * An em dash rather than "0 B" when the size is absent: the column is nullable, and a zero is a
 * claim about the file rather than about what we recorded.
 */
function formatSize(bytes: number | null): string {
  if (bytes === null || bytes <= 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
