import { useState } from 'react'

import { DialogContent, DialogRoot } from '../../components/ui/dialog'
import { useMetrics } from '../dashboards/useMetrics'
import { CustomFieldInput, INTAKE_FIELD_KEYS } from './NewDealForm'
import {
  deleteDeal,
  fetchGhlUsers,
  fetchOpportunityFields,
  updateDeal,
  type GhlUser,
  type OpportunityField,
} from './opportunityApi'

/**
 * Edit every field of a deal, or delete it (Unit 69, spec `69-deal-edit-delete.md`).
 *
 * <p><strong>The create form's fields, minus the contact's.</strong> Name, value, stage, expected
 * close, owner and the intake custom fields. Status stays on the Actions panel (Won is Handoff A);
 * the pipeline is GHL's routing; email and phone belong to the contact, not the deal.
 *
 * <p><strong>Only what changed is sent</strong>, so an untouched field is never re-sent over a
 * value GHL's automation set meanwhile. The close date is not mirrored, so it starts blank and blank
 * means "keep". A custom field cannot be cleared here — GHL is sent non-blank values only.
 *
 * <p><strong>Delete confirms inline</strong>, not with `window.confirm` (a native dialog blocks the
 * page). The server refuses a won deal and one whose last edit is still queued, and says why.
 */
export default function DealEditDialog({
  opportunityId,
  name,
  amount,
  stageId,
  stages,
  assignedToId,
  fieldValues,
  onClose,
  onSaved,
  onDeleted,
}: {
  opportunityId: string
  name: string | null
  amount: number | null
  stageId: string | null
  stages: readonly { stageId: string; stageName: string }[]
  assignedToId: string | null
  fieldValues: Record<string, string>
  onClose: () => void
  onSaved: () => void
  onDeleted: () => void
}) {
  const [draftName, setDraftName] = useState(name ?? '')
  const [draftAmount, setDraftAmount] = useState(amount === null ? '' : String(amount))
  const [draftStage, setDraftStage] = useState(stageId ?? '')
  const [closeDate, setCloseDate] = useState('')
  const [owner, setOwner] = useState(assignedToId ?? '')
  const [custom, setCustom] = useState<Record<string, string>>(fieldValues)
  const [busy, setBusy] = useState(false)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const { data: users } = useMetrics<readonly GhlUser[]>((signal) => fetchGhlUsers(signal), [])
  const { data: fields } = useMetrics<readonly OpportunityField[]>(
    (signal) => fetchOpportunityFields(signal),
    [],
  )
  const intake = (fields ?? [])
    .filter((field) => INTAKE_FIELD_KEYS.includes(field.fieldKey))
    .sort((a, b) => INTAKE_FIELD_KEYS.indexOf(a.fieldKey) - INTAKE_FIELD_KEYS.indexOf(b.fieldKey))

  const nameChanged = draftName.trim() !== '' && draftName.trim() !== (name ?? '').trim()
  // Compared as numbers, not as strings: "150" and "150.00" are the same value.
  const parsedAmount = draftAmount.trim() === '' ? null : Number(draftAmount)
  const amountValid = parsedAmount === null || (Number.isFinite(parsedAmount) && parsedAmount >= 0)
  const amountChanged = amountValid && parsedAmount !== null && parsedAmount !== amount
  const stageChanged = draftStage !== '' && draftStage !== (stageId ?? '')
  const ownerChanged = owner !== '' && owner !== (assignedToId ?? '')
  const changedFields = Object.fromEntries(
    Object.entries(custom).filter(
      ([id, value]) => value.trim() !== '' && value !== (fieldValues[id] ?? ''),
    ),
  )
  const fieldsChanged = Object.keys(changedFields).length > 0

  const canSave =
    !busy &&
    amountValid &&
    (nameChanged || amountChanged || stageChanged || closeDate !== '' || ownerChanged || fieldsChanged)

  async function save() {
    if (!canSave) return
    setBusy(true)
    setError(null)
    try {
      await updateDeal(opportunityId, {
        ...(nameChanged ? { name: draftName.trim() } : {}),
        ...(amountChanged && parsedAmount !== null ? { monetaryValue: parsedAmount } : {}),
        ...(stageChanged ? { stageId: draftStage } : {}),
        ...(closeDate ? { expectedCloseDate: closeDate } : {}),
        ...(ownerChanged ? { assignedTo: owner } : {}),
        ...(fieldsChanged ? { customFields: changedFields } : {}),
      })
      onSaved()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not save the change')
      setBusy(false)
    }
  }

  async function remove() {
    setBusy(true)
    setError(null)
    try {
      await deleteDeal(opportunityId)
      onDeleted()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not delete the deal')
      setBusy(false)
      setConfirmingDelete(false)
    }
  }

  const label = 'block text-xs'
  const muted = { color: 'var(--text-muted)' }

  return (
    <DialogRoot open onOpenChange={(next) => !next && onClose()}>
      <DialogContent
        title="Edit deal"
        description="Every field GoHighLevel holds for this deal."
        footer={
          confirmingDelete ? (
            <>
              <span className="mr-auto text-sm" style={{ color: 'var(--status-red)' }}>
                Delete this deal in GoHighLevel for good?
              </span>
              <button type="button" className="btn" onClick={() => setConfirmingDelete(false)} disabled={busy}>
                Keep
              </button>
              <button
                type="button"
                className="btn"
                style={{ color: 'var(--status-red)', borderColor: 'var(--status-red)' }}
                onClick={() => void remove()}
                disabled={busy}
              >
                {busy ? 'Deleting…' : 'Delete'}
              </button>
            </>
          ) : (
            <>
              <button
                type="button"
                className="btn mr-auto"
                style={{ color: 'var(--status-red)' }}
                onClick={() => setConfirmingDelete(true)}
                disabled={busy}
              >
                Delete deal
              </button>
              <button type="button" className="btn" onClick={onClose} disabled={busy}>
                Cancel
              </button>
              <button type="button" className="btn btn-primary" onClick={() => void save()} disabled={!canSave}>
                {busy ? 'Saving…' : 'Save'}
              </button>
            </>
          )
        }
      >
        <div className="space-y-4">
          <label className={label} style={muted}>
            Name
            <input
              value={draftName}
              onChange={(event) => setDraftName(event.target.value)}
              className="field mt-1 w-full"
              placeholder="Deal name"
            />
          </label>

          <div className="grid grid-cols-2 gap-3">
            <label className={label} style={muted}>
              Value (USD)
              {/* `inputMode="decimal"` rather than `type="number"`: a number input silently
                  discards what it cannot parse. This keeps the text and refuses it below. */}
              <input
                value={draftAmount}
                inputMode="decimal"
                onChange={(event) => setDraftAmount(event.target.value)}
                className="field mt-1 w-full font-num"
                placeholder="0"
              />
            </label>
            <label className={label} style={muted}>
              Expected close
              <input
                type="date"
                value={closeDate}
                onChange={(event) => setCloseDate(event.target.value)}
                className="field mt-1 w-full"
              />
            </label>
          </div>

          <label className={label} style={muted}>
            Stage
            <select
              value={draftStage}
              onChange={(event) => setDraftStage(event.target.value)}
              className="field mt-1 w-full"
            >
              {stages.map((stage) => (
                <option key={stage.stageId} value={stage.stageId}>
                  {stage.stageName}
                </option>
              ))}
            </select>
          </label>

          <label className={label} style={muted}>
            Owner
            <select value={owner} onChange={(event) => setOwner(event.target.value)} className="field mt-1 w-full">
              {/* Blank is shown only while nobody owns it: GHL is sent an owner, never "none". */}
              {assignedToId === null && <option value="">Unassigned</option>}
              {(users ?? []).map((user) => (
                <option key={user.id} value={user.id}>
                  {user.name}
                </option>
              ))}
            </select>
          </label>

          {intake.map((field) => (
            <CustomFieldInput
              key={field.id}
              field={field}
              value={custom[field.id] ?? ''}
              onChange={(next) => setCustom((prev) => ({ ...prev, [field.id]: next }))}
            />
          ))}

          {!amountValid && (
            <p className="text-xs" style={{ color: 'var(--status-red)' }}>
              Value has to be a number, and not a negative one.
            </p>
          )}
          {error && (
            <p className="text-xs" style={{ color: 'var(--status-red)' }} role="alert">
              {error}
            </p>
          )}

          <p className="text-xs" style={muted}>
            Name, value and stage are saved here first and reach GoHighLevel on the next sync; the
            rest is written to GoHighLevel straight away.
          </p>
        </div>
      </DialogContent>
    </DialogRoot>
  )
}
