import { mockDelay } from '@shared/mock/mockDelay'

/**
 * A fake chunked upload, so the intake wizard's progress bar has something to animate.
 *
 * **It lives in `mock/` because that is what it is.** It used to sit in `documentService`, which
 * is now the real portal API client (Unit 34c) — a mock beside real calls in one module is how
 * somebody ships the mock. The intake flow it serves is itself proposed for removal (Unit 34 D2:
 * intake creates a case outside Handoff A, which is front-of-house work GHL owns), so this goes
 * with it.
 */
export async function simulateUpload(file: File, onProgress: (percent: number) => void): Promise<void> {
  const steps = 10
  for (let step = 1; step <= steps; step += 1) {
    await mockDelay(120)
    onProgress(Math.round((step / steps) * 100))
  }
  void file
}
