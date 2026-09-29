export function formatDate(
  value: string | Date | undefined | null,
  month: 'long' | 'short' = 'long',
): string {
  if (!value) return '—'
  const date = typeof value === 'string' ? new Date(value) : value
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleDateString('en-US', { year: 'numeric', month, day: 'numeric' })
}
