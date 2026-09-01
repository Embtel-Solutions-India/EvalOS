import { useEffect, useRef, useState } from 'react'
import ShortlistPanel from '../experts/ShortlistPanel'
import { fetchAssignable, fetchAvailableExperts } from './boardApi'
import type { ActionField, PickerOption, QuickAction } from './boardRules'

/**
 * The one dialog every input-taking action uses, driven by the action's own field list.
 *
 * One generic form rather than ten hand-written ones: the actions differ only in which
 * fields they collect, and a dialog per transition would be ten copies of the same submit
 * handler.
 *
 * Opened with `showModal()` rather than the `open` attribute, because only the modal path
 * gives the platform behaviour this leans on: Escape (which fires `cancel`), a real
 * `::backdrop`, and focus trapped inside the form. With `open` alone the dialog renders but
 * Escape does nothing, the backdrop class is inert, and tab focus walks out into the board
 * behind it.
 *
 * Assignment fields are `<select>`s loaded from the scoped roster and expert endpoints. An
 * id is not something a user knows or should be asked to type — and a *generated* id would
 * be worse than useless, since the transition looks the row up and refuses anything that is
 * not an existing member of the right role in the case's brand.
 *
 * `assign-cm` additionally gets the Unit 12 shortlist above its fields. It sits *above* the
 * expert dropdown and fills it in, rather than replacing it: the ranking is assistance, and the
 * full picker has to stay one click away or the engine has quietly become a precondition.
 */
export default function QuickActionDialog({
  action,
  caseId,
  caseCode,
  onCancel,
  onConfirm,
}: {
  action: QuickAction
  caseId: string
  caseCode: string
  onCancel: () => void
  onConfirm: (values: Record<string, string>) => void
}) {
  const [values, setValues] = useState<Record<string, string>>({})
  const fields = action.fields ?? []
  const dialog = useRef<HTMLDialogElement>(null)
  const setValue = (name: string, value: string) =>
    setValues((previous) => ({ ...previous, [name]: value }))

  useEffect(() => {
    // The component only renders while an action is pending, so this opens once on mount.
    dialog.current?.showModal()
  }, [])

  return (
    <dialog
      ref={dialog}
      // max-h + scroll because the shortlist makes this dialog tall enough to run off a laptop
      // screen, and a modal whose Assign button is below the fold cannot be completed.
      className="scroll-slim fixed inset-0 z-20 m-auto max-h-[85vh] overflow-y-auto rounded-xl p-0 backdrop:bg-black/30"
      style={{ background: 'var(--bg-surface)', boxShadow: 'var(--shadow-pop)' }}
      // Escape reaches this now that the dialog is modal. `preventDefault` first, because
      // the parent unmounts us and letting the platform also close a removed node is how a
      // stale dialog ends up stuck open on the next render.
      onCancel={(event) => {
        event.preventDefault()
        onCancel()
      }}
    >
      <form
        // Wider for the shortlist, whose cards carry four labelled bars each; the other dialogs
        // collect at most two fields and a wide box for one input reads as an empty room.
        className={`${action.path === 'assign-cm' ? 'w-112' : 'w-88'} p-7`}
        onSubmit={(event) => {
          event.preventDefault()
          onConfirm(values)
        }}
      >
        <h2 className="text-xl font-semibold tracking-tight">{action.label}</h2>
        <p className="font-mono mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
          {caseCode}
        </p>

        <div className="mt-4 space-y-3">
          {action.path === 'assign-cm' && (
            <ShortlistPanel
              caseId={caseId}
              selectedExpertId={values.expertId ?? ''}
              // Picking a card only sets the field the dropdown below reads, so the two are one
              // choice with two ways in — and the shortlisted expert is in that list either way,
              // since both endpoints filter to AVAILABLE.
              onPick={(expertId) => setValue('expertId', expertId)}
            />
          )}
          {fields.map((field) => (
            <Field
              key={field.name}
              field={field}
              caseId={caseId}
              value={values[field.name] ?? ''}
              onChange={(value) => setValue(field.name, value)}
            />
          ))}
          {fields.length === 0 && (
            <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
              This records the transition on {caseCode}. It cannot be undone from here.
            </p>
          )}
        </div>

        <div className="mt-7 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-md px-5 py-2.5 text-sm font-medium"
            style={{ background: 'var(--bg-raised)' }}
          >
            Cancel
          </button>
          <button
            type="submit"
            className="rounded-md px-5 py-2.5 text-sm font-medium text-white"
            style={{ background: 'var(--accent-primary)' }}
          >
            {action.label}
          </button>
        </div>
      </form>
    </dialog>
  )
}

const LABEL_CLASS = 'block text-xs font-medium tracking-[0.04em] uppercase'
const INPUT_CLASS = 'mt-1.5 w-full rounded-lg border px-4 py-2.5 text-sm'
const INPUT_STYLE = { background: 'var(--bg-base)', borderColor: 'var(--border-default)' }

function Field({
  field,
  caseId,
  value,
  onChange,
}: {
  field: ActionField
  /** Passed down for the expert picker, which the server narrows to what this case can take. */
  caseId: string
  value: string
  onChange: (value: string) => void
}) {
  const isPicker = field.kind === 'member' || field.kind === 'expert'
  // Required unless the label says otherwise — the server validates too, this just avoids
  // a round trip for an empty box.
  const required = !field.label.includes('optional')

  return (
    <label className="block">
      <span className={LABEL_CLASS} style={{ color: 'var(--text-muted)' }}>
        {field.label}
      </span>
      {isPicker ? (
        <Picker field={field} caseId={caseId} value={value} onChange={onChange} required={required} />
      ) : (
        <input
          required={required}
          type={field.kind === 'amount' ? 'number' : 'text'}
          step={field.kind === 'amount' ? '0.01' : undefined}
          min={field.kind === 'amount' ? '0.01' : undefined}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          className={`${INPUT_CLASS} ${field.kind === 'amount' ? 'font-num tabular-nums' : ''}`}
          style={INPUT_STYLE}
        />
      )}
    </label>
  )
}

type Options = { status: 'loading' } | { status: 'ready'; items: PickerOption[] } | { status: 'failed' }

/**
 * A choice from the rows the caller may actually assign.
 *
 * An empty list is a real answer, not an error: a PM with no Coordinator on their team has
 * nobody to assign, and saying so beats an empty dropdown that looks broken.
 */
function Picker({
  field,
  caseId,
  value,
  onChange,
  required,
}: {
  field: ActionField
  caseId: string
  value: string
  onChange: (value: string) => void
  required: boolean
}) {
  const [options, setOptions] = useState<Options>({ status: 'loading' })
  const memberRole = field.memberRole

  useEffect(() => {
    const controller = new AbortController()
    const load = field.kind === 'expert' || !memberRole
      ? fetchAvailableExperts(caseId, controller.signal)
      : fetchAssignable(memberRole, controller.signal)

    load
      .then((items) => setOptions({ status: 'ready', items }))
      .catch(() => {
        if (!controller.signal.aborted) setOptions({ status: 'failed' })
      })
    return () => controller.abort()
  }, [field.kind, memberRole, caseId])

  if (options.status === 'loading') {
    return (
      <p className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>
        Loading…
      </p>
    )
  }

  if (options.status === 'failed') {
    return (
      <p className="mt-1 text-sm" style={{ color: 'var(--status-red)' }}>
        Could not load the list.
      </p>
    )
  }

  if (options.items.length === 0) {
    return (
      <p className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>
        {field.kind === 'expert'
          ? 'No available expert in this brand.'
          : 'Nobody in that role is in your scope.'}
      </p>
    )
  }

  return (
    <select
      required={required}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      className={INPUT_CLASS}
      style={INPUT_STYLE}
    >
      <option value="">Choose…</option>
      {options.items.map((option) => (
        <option key={option.id} value={option.id}>
          {option.label}
        </option>
      ))}
    </select>
  )
}
