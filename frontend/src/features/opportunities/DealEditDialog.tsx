import { useState } from 'react'

import { DialogContent, DialogRoot } from '../../components/ui/dialog'
import { updateDeal } from './opportunityApi'

/**
 * Rename a deal, or change what it is worth.
 *
 * <p><strong>Two fields, because the server takes two.</strong> {@code SalesDeskService.update}
 * accepts a name and a monetary value and nothing else — the stage, the status and the follow-up
 * are their own routes with their own rules, and they are already on the Actions panel. A dialog
 * offering more than the endpoint accepts would be a form with dead controls.
 *
 * <p><strong>Neither field is required, but one of them has to change.</strong> That is the
 * server's rule ("Nothing to change: send a name, a value, or both") and it is mirrored here so
 * the refusal costs no round trip — the button stays disabled until something is actually
 * different from what came in.
 *
 * <p><strong>The write goes to the outbox, not to GHL.</strong> Unit 46: a desk edit is
 * {@code editLocally} plus a queued {@code UPSERT}, so this returns as soon as EvalOS has the
 * change and GHL catches up on the next drain (≤2m). The reload afterwards therefore shows the
 * local row, which is the one the board draws from.
 */
export default function DealEditDialog({
  opportunityId,
  name,
  amount,
  onClose,
  onSaved,
}: {
  opportunityId: string
  name: string | null
  amount: number | null
  onClose: () => void
  onSaved: () => void
}) {
  const [draftName, setDraftName] = useState(name ?? '')
  const [draftAmount, setDraftAmount] = useState(amount === null ? '' : String(amount))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const nameChanged = draftName.trim() !== (name ?? '').trim()
  // Compared as numbers, not as strings: "150" and "150.00" are the same value, and offering to
  // save the difference between them would be a write that changes nothing in GHL.
  const parsedAmount = draftAmount.trim() === '' ? null : Number(draftAmount)
  const amountValid = parsedAmount === null || (Number.isFinite(parsedAmount) && parsedAmount >= 0)
  const amountChanged = amountValid && parsedAmount !== null && parsedAmount !== amount

  const canSave = !busy && amountValid && (nameChanged || amountChanged)

  async function save() {
    if (!canSave) return
    setBusy(true)
    setError(null)
    try {
      await updateDeal(opportunityId, {
        ...(nameChanged ? { name: draftName.trim() } : {}),
        ...(amountChanged && parsedAmount !== null ? { monetaryValue: parsedAmount } : {}),
      })
      onSaved()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not save the change')
      setBusy(false)
    }
  }

  return (
    <DialogRoot open onOpenChange={(next) => !next && onClose()}>
      <DialogContent
        title="Edit deal"
        description="The name and value GoHighLevel holds for this deal."
        footer={
          <>
            <button type="button" className="btn" onClick={onClose} disabled={busy}>
              Cancel
            </button>
            <button type="button" className="btn btn-primary" onClick={() => void save()} disabled={!canSave}>
              {busy ? 'Saving…' : 'Save'}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <label className="block text-xs" style={{ color: 'var(--text-muted)' }}>
            Name
            <input
              value={draftName}
              onChange={(event) => setDraftName(event.target.value)}
              className="field mt-1 w-full"
              placeholder="Deal name"
            />
          </label>

          <label className="block text-xs" style={{ color: 'var(--text-muted)' }}>
            Value (USD)
            {/*
              `inputMode="decimal"` rather than `type="number"`: a number input silently discards
              what it cannot parse, so a mistyped value becomes an empty field the reader did not
              ask for. This keeps the text and refuses it below.
            */}
            <input
              value={draftAmount}
              inputMode="decimal"
              onChange={(event) => setDraftAmount(event.target.value)}
              className="field mt-1 w-full font-num"
              placeholder="0"
            />
          </label>

          {!amountValid && (
            <p className="text-xs" style={{ color: 'var(--status-red)' }}>
              Value has to be a number, and not a negative one.
            </p>
          )}
          {error && (
            <p className="text-xs" style={{ color: 'var(--status-red)' }}>
              {error}
            </p>
          )}

          <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
            Saved here first; GoHighLevel catches up on the next sync.
          </p>
        </div>
      </DialogContent>
    </DialogRoot>
  )
}
