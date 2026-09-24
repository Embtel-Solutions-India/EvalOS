import { useEffect, useState } from 'react'
import { Paperclip } from 'lucide-react'

import { Panel } from '../../components/ui/panel'

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
    return (
      <p className="text-xs" style={{ color: 'var(--status-red)' }}>
        {error}
      </p>
    )
  }
  if (documents.length === 0) return null

  return (
    <Panel title="Submitted documents" icon={<Paperclip />}>
      <table className="tbl">
        <thead>
          <tr>
            <th className="num">#</th>
            <th>Document name</th>
            <th>Type</th>
            <th>Uploaded on</th>
            <th>Verification</th>
            <th>On the case</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {documents.map((document, index) => (
            <tr key={document.id}>
              <td className="num">{index + 1}</td>
              <td className="font-medium">
                {document.filename}
                {/* The format keeps its place under the name rather than in the Type column:
                    it is real, it is the thing that tells you whether you can open the file,
                    and it must not be mistaken for the document *category* beside it. */}
                <span className="block text-xs font-normal" style={{ color: 'var(--text-muted)' }}>
                  {fileType(document)} · {formatSize(document.sizeBytes)}
                </span>
              </td>
              {/*
                TYPE AND STATUS ARE PLACEHOLDERS, HELD OPEN ON PURPOSE (2026-09-23).

                The columns are here so the screen matches the agreed design and can be reviewed
                against it. Neither has a field behind it yet:

                  Type    the document CATEGORY — Identity, Education, and the rest. Which
                          documents a request expects, and what each is called, is still being
                          decided. `application_document` stores a filename and a MIME type and
                          nothing that answers "what kind of document is this".

                  Status  the VERIFICATION verdict. That work belongs to the Project Coordinator
                          and is theirs alone; this screen only ever displays it. Nothing sets it
                          today, so every row reads the same.

                Both render an explicit waiting state rather than a plausible value. A row saying
                "Verified" that no one verified is the one outcome worse than an empty column:
                a salesperson would price the work on it.
              */}
              <td style={{ color: 'var(--text-muted)' }}>—</td>
              <td style={{ color: 'var(--text-muted)' }} className="whitespace-nowrap">
                {new Date(document.uploadedAt).toLocaleDateString()}
              </td>
              <td className="whitespace-nowrap">
                <span className="chip">Not reviewed</span>
              </td>
              <td className="whitespace-nowrap">
                {/* `carriedToCase` IS real and stays, because it answers a question somebody
                    actually asks — "has this reached the case yet". It is not the verification
                    verdict and is not labelled as one. */}
                <span className="chip">
                  {document.carriedToCase ? 'On the case' : 'With the request'}
                </span>
              </td>
              <td className="text-right">
                <button
                  type="button"
                  onClick={() => void open(document)}
                  disabled={opening === document.id}
                  className="font-medium disabled:opacity-50"
                  style={{ color: 'var(--accent-primary)' }}
                >
                  {opening === document.id ? 'Opening…' : 'View'}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {/*
        The note is the "space held" made visible, not decoration. Without it the two waiting
        columns read as a screen that failed to load its own data, and somebody files a bug
        against a decision that has not been taken yet.
      */}
      <p
        className="mt-3 border-t pt-3 text-xs"
        style={{ borderColor: 'var(--border-default)', color: 'var(--text-muted)' }}
      >
        These are the files the client attached in their portal. <strong>Type</strong> and{' '}
        <strong>Verification</strong> are not set yet — the document list is still being agreed, and
        verifying a document is the Project Coordinator&rsquo;s work. Sales reads the verdict here;
        it is never set from this screen.
      </p>

      {error && (
        <p className="mt-2 text-xs" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}
    </Panel>
  )
}

/**
 * A word for the file, from the MIME type the upload recorded.
 *
 * **This is the format, not a category.** The design this table follows showed a Type column
 * reading "Identity", "Education", "Other" — a taxonomy of what a document *is*, which
 * `application_document` does not store and nothing on the request asks the client for. Inventing
 * one here would put a classification on screen that no part of the system made. The format is a
 * fact the row actually holds, and it answers the question the column is really asked: can I open
 * this.
 */
function fileType(document: RequestDocument): string {
  const mime = document.contentType
  if (mime) {
    const known: Record<string, string> = {
      'application/pdf': 'PDF',
      'image/jpeg': 'JPEG',
      'image/png': 'PNG',
      'application/msword': 'DOC',
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'DOCX',
    }
    if (known[mime]) return known[mime]
  }
  // Falling back to the extension rather than showing the raw MIME string: `application/vnd.
  // openxmlformats-…` in a table cell is noise, and a file with no recorded type still has a name.
  const dot = document.filename.lastIndexOf('.')
  return dot > 0 ? document.filename.slice(dot + 1).toUpperCase() : '—'
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
