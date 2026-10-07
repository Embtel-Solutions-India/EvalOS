/** Progress against a target as a whole percent; null when there is no target to measure against (not set, or 0). */
export function progressPct(progress: number, target: number | null): number | null {
  if (target === null || target <= 0) return null
  return Math.round((progress * 100) / target)
}
