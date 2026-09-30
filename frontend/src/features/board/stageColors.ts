/**
 * One colour per pipeline stage, by position, shared by the production board and the opportunity
 * board so a stage's pill reads the same on both.
 *
 * Every entry keeps white pill text at 4.5:1 or better (WCAG AA for the 14px label), which is why
 * these are the deeper shades rather than the brightest ones. **No red, amber, orange or green**:
 * RAG is status-only on this board (`ui-context.md`), so no stage may look like an SLA; green and
 * rose are kept for the Won and Lost outcomes (`BY_NAME`).
 */
export const STAGE_PALETTE = [
  '#4f46e5', // indigo
  '#0369a1', // sky
  '#be185d', // pink
  '#7c3aed', // violet
  '#0f766e', // teal
  '#a21caf', // fuchsia
  '#1d4ed8', // blue
  '#0e7490', // cyan
  '#9333ea', // purple
  '#57534e', // stone
] as const

/** Exception lanes are held work, not a step, so they share one neutral colour. */
export const LANE_COLOR = '#475569'

/**
 * Outcome stages keep one meaning on every pipeline, whatever their position: Won is green, a
 * lost deal rose, a refund neutral.
 */
const BY_NAME: Record<string, string> = {
  won: '#15803d',
  lost: '#be123c',
  disqualified: '#be123c',
  dropped: '#be123c',
  refunded: LANE_COLOR,
  refund: LANE_COLOR,
}

/** The stage's colour: its outcome colour if it has one, else by position (the palette repeats). */
export function stageColor(index: number, name?: string): string {
  const named = name ? BY_NAME[name.trim().toLowerCase()] : undefined
  return named ?? STAGE_PALETTE[((index % STAGE_PALETTE.length) + STAGE_PALETTE.length) % STAGE_PALETTE.length]
}
