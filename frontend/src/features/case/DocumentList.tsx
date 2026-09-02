import { useEffect, useState } from 'react'
import { fetchCaseDocuments, fetchDocumentUrl, type DraftVersion } from './caseApi'

/**
 * The client's uploaded documents, each opening through a short-lived URL.
 *
 * **Nothing here holds a URL.** One is fetched at the moment of the click and used immediately —
 * a presigned link kept in state expires while the page sits open, and a user clicking a dead link
 * cannot tell that from a missing document.
 *
 * `window.open` after an await is deliberately preceded by opening the tab synchronously: a popup
 * blocker rejects a window opened from an async continuation, which would look like the button
 * doing nothing at all.
 */
export default function DocumentList({ caseId, maySee }: { caseId: string; maySee: boolean }) {
  const [state, setState] = useState<
    { status: 'loading' } | { status: 'ready'; docs: DraftVersion[] } | { status: 'failed' }
  >({ status: 'loading' })
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!maySee) return
    const controller = new AbortController()
    fetchCaseDocuments(caseId, 'CLIENT_UPLOAD', controller.signal)
      .then((docs) => setState({ status: 'ready', docs }))
      .catch(() => {
        if (!controller.signal.aborted) setState({ status: 'failed' })
      })
    return () => controller.abort()
  }, [caseId, maySee])

  const open = async (documentId: string) => {
    // **The tab is opened synchronously and WITHOUT `noopener`, and both halves matter.**
    // Synchronously, because a popup blocker rejects a window opened from an async continuation —
    // the click has to be what opens it. Without `noopener`, because per the HTML spec
    // `window.open` returns **null** whenever `noopener` is in the features string, so the handle
    // needed to navigate the tab afterwards would never exist and the button would silently do
    // nothing. The opener reference is severed on the line below instead, which is the same
    // protection by a different route.
    const tab = window.open('', '_blank')
    if (tab) tab.opener = null
    try {
      const url = await fetchDocumentUrl(caseId, documentId)
      if (tab) tab.location.href = url
      // A blocked popup is not a failure of the fetch, so it is reported as itself.
      else setError('Allow pop-ups for this site to open documents.')
    } catch {
      tab?.close()
      setError('That document could not be opened. The store may be unavailable.')
    }
  }

  if (!maySee) {
    return (
      <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
        Client documents are not available to your role.
      </p>
    )
  }

  if (state.status === 'loading') return null

  if (state.status === 'failed') {
    return (
      <p className="mt-2 text-sm" style={{ color: 'var(--status-red)' }}>
        Documents could not be loaded.
      </p>
    )
  }

  return (
    <>
      {state.docs.length === 0 ? (
        // Operational copy: an empty list is a statement about the case, not about the screen.
        <p className="mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
          The client has not uploaded anything yet.
        </p>
      ) : (
        <ul className="mt-2 flex flex-col gap-1">
          {state.docs.map((doc) => (
            <li key={doc.id}>
              <button
                type="button"
                onClick={() => void open(doc.id)}
                className="text-sm font-medium"
                style={{ color: 'var(--accent-primary)' }}
              >
                {doc.filename ?? `Document ${doc.version}`} ↗
              </button>
            </li>
          ))}
        </ul>
      )}
      {error && (
        <p className="mt-2 text-sm" style={{ color: 'var(--status-red)' }}>
          {error}
        </p>
      )}
    </>
  )
}
