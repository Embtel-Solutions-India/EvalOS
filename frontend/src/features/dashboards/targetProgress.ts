/** Progress against a target as a whole percent; null when there is no target to measure against (not set, or 0). */
export function progressPct(progress: number, target: number | null): number | null {
  if (target === null || target <= 0) return null
  return Math.round((progress * 100) / target)
}

/**
 * What a desk's target cell may say. Not set and not known are different: while the targets are loading,
 * or if they failed to load, saying "Not set" invites the GM to type over a target that exists.
 */
export function targetState(target: number | null, known: boolean): 'unknown' | 'unset' | 'set' {
  if (!known) return 'unknown'
  return target === null ? 'unset' : 'set'
}

/** The inline editor's field: a Sales target is money, so cents are real; a Marketing target counts leads. */
export function targetInput(role: 'SALES' | 'MARKETING', target: number | null): { step: string; initial: string } {
  return { step: role === 'SALES' ? '0.01' : '1', initial: target === null ? '' : String(target) }
}
