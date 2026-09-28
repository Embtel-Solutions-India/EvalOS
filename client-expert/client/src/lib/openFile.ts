import { toast } from 'sonner'
import { failureMessage } from '@shared/lib/portal'
import { statusOf } from '@shared/services/apiClient'

/**
 * Opens a file behind a five-minute link, fetched on the click and never held.
 *
 * **The tab is opened synchronously and WITHOUT `noopener`, and both halves matter.** A popup
 * blocker rejects a window opened from an async continuation, so the click has to open it; and
 * `window.open` returns null whenever `noopener` is in the features string, so the handle needed
 * to navigate it afterwards would never exist. The opener is severed on the next line instead.
 */
export async function openFile(mint: () => Promise<string>): Promise<void> {
  const tab = window.open('', '_blank')
  if (tab) tab.opener = null
  try {
    const url = await mint()
    if (tab) tab.location.href = url
    else toast.error('Allow pop-ups for this site to open documents.')
  } catch (error: unknown) {
    tab?.close()
    toast.error(failureMessage(statusOf(error)))
  }
}
