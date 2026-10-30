import { useState } from 'react'
import { fetchDocumentUrl } from './caseApi'

/**
 * Opens one document through a URL minted at the click — shared by this list and the Documents panel's rows.
 *
 * **The tab is opened synchronously and WITHOUT `noopener`, and both halves matter.** Synchronously, because
 * a popup blocker rejects a window opened from an async continuation — the click has to be what opens it.
 * Without `noopener`, because per the HTML spec `window.open` returns **null** whenever `noopener` is in the
 * features string, so the handle needed to navigate the tab afterwards would never exist and the button would
 * silently do nothing. The opener reference is severed on the line below instead, which is the same protection
 * by a different route.
 */
export function useOpenDocument(caseId: string) {
  const [error, setError] = useState<string | null>(null)
  const open = async (documentId: string, view: boolean) => {
    setError(null)
    const tab = window.open('', '_blank')
    if (tab) tab.opener = null
    try {
      const url = await fetchDocumentUrl(caseId, documentId, false, view)
      if (tab) tab.location.href = url
      // A blocked popup is not a failure of the fetch, so it is reported as itself.
      else setError('Allow pop-ups for this site to open documents.')
    } catch {
      tab?.close()
      setError('That document could not be opened. The store may be unavailable.')
    }
  }
  return { open, error }
}
