/** Whole days since, which is the unit a chase is planned in. */
export function daysSince(iso: string, now = Date.now()): number {
  return Math.floor((now - new Date(iso).getTime()) / 86_400_000)
}
