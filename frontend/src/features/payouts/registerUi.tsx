import { REGISTER_STATUS_LABEL, STATUS_TONE, type RegisterStatus } from './registerRules'

/** A status as a coloured dot and its business word (Unit 65) — the same on every payouts screen. */
export function StatusChip({ status }: { status: RegisterStatus }) {
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-xs font-medium">
      <span aria-hidden className="h-2 w-2 rounded-full" style={{ background: STATUS_TONE[status] }} />
      {REGISTER_STATUS_LABEL[status]}
    </span>
  )
}
