import { useState, type ReactElement } from 'react'
import { DialogContent, DialogRoot, DialogTrigger } from '../../components/ui/dialog'
import { SERVICE_FILTER_OPTIONS, type ServiceType } from '../board/boardRules'
import { changeServiceType } from './caseApi'

/**
 * The PM corrects what the case was sold as (2026-10-30). The server keeps every document already sent,
 * uploaded or reviewed, drops untouched ones the new type does not need, and adds its missing ones.
 */
export default function ServiceTypeDialog({
  caseId,
  serviceType,
  trigger,
  onSaved,
}: {
  caseId: string
  serviceType: ServiceType
  trigger: ReactElement
  onSaved: () => void
}) {
  const [open, setOpen] = useState(false)
  const [value, setValue] = useState<ServiceType>(serviceType)
  const [error, setError] = useState<string | null>(null)

  async function save(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      await changeServiceType(caseId, value)
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : 'The service type was not changed')
      return
    }
    setOpen(false)
    onSaved()
  }

  return (
    <DialogRoot
      open={open}
      onOpenChange={(next) => {
        setOpen(next)
        if (next) {
          setValue(serviceType)
          setError(null)
        }
      }}
    >
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent
        title="Change service type"
        description="Documents already sent, uploaded or reviewed stay. The new type's missing documents are added."
      >
        <form onSubmit={save} className="flex flex-col gap-3 text-sm">
          <select
            aria-label="Service type"
            value={value}
            onChange={(e) => setValue(e.target.value as ServiceType)}
            className="rounded-md border p-2"
            style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}
          >
            {SERVICE_FILTER_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          {error && (
            <p role="alert" style={{ color: 'var(--status-red)' }}>
              {error}
            </p>
          )}
          <div className="flex justify-end">
            <button
              type="submit"
              disabled={value === serviceType}
              className="rounded-md px-3 py-1.5 font-medium disabled:opacity-50"
              style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}
            >
              Save
            </button>
          </div>
        </form>
      </DialogContent>
    </DialogRoot>
  )
}
