import { ArrowLeft, ArrowRight } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'
import { RadioGroup, RadioGroupItem } from '@shared/components/ui/radio-group'
import { EVALUATION_PURPOSE_OPTIONS } from '@/constants/evaluation'
import { getService } from '@/constants/serviceCatalog'
import { useAuth } from '@/hooks/useAuth'
import { getDraft, setDraftPurpose } from '@/services/intakeService'
import type { RequestPurpose } from '@/types/intake'
import { cn } from '@shared/utils/cn'

export default function ChoosePurpose() {
  const navigate = useNavigate()
  const { isAuthenticated } = useAuth()
  const draft = getDraft()
  const service = getService(draft.serviceId)
  const [purpose, setPurpose] = useState<RequestPurpose | undefined>(draft.purpose)
  // Anyone already logged in skips About You entirely — it's only for
  // first-time account creation, and ChooseService already seeded the
  // draft's aboutYou from their account.
  const nextStepAfterPurpose = isAuthenticated ? '/start/questions' : '/start/about-you'

  useEffect(() => {
    if (!draft.serviceId) {
      navigate('/start/service', { replace: true })
      return
    }
    if (service?.impliedPurpose) {
      navigate(nextStepAfterPurpose, { replace: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function handleContinue() {
    if (!purpose) return
    setDraftPurpose(purpose)
    navigate(nextStepAfterPurpose)
  }

  if (!service || service.impliedPurpose) return null

  return (
    <div>
      <h1 className="text-xl font-semibold tracking-tight text-foreground sm:text-2xl">What are you using this for?</h1>
      <p className="mt-1 text-sm text-muted-foreground">
        This helps us tailor your {service.name.toLowerCase()} to the right audience.
      </p>

      <RadioGroup value={purpose} onValueChange={(value) => setPurpose(value as RequestPurpose)} className="mt-8 grid grid-cols-1 gap-3 sm:grid-cols-2">
        {EVALUATION_PURPOSE_OPTIONS.map((option) => (
          <label
            key={option.value}
            htmlFor={`purpose-${option.value}`}
            className={cn(
              'flex cursor-pointer items-center gap-3 rounded-lg border p-4 text-sm font-medium transition-colors',
              purpose === option.value ? 'border-primary bg-primary/5 text-foreground' : 'text-foreground hover:bg-muted/40',
            )}
          >
            <RadioGroupItem value={option.value} id={`purpose-${option.value}`} />
            {option.label}
          </label>
        ))}
      </RadioGroup>

      <div className="mt-8 flex flex-col-reverse gap-3 border-t pt-6 sm:flex-row sm:items-center sm:justify-between">
        <Button type="button" variant="outline" onClick={() => navigate('/start/service')}>
          <ArrowLeft className="h-4 w-4" />
          Back
        </Button>
        <Button size="lg" disabled={!purpose} onClick={handleContinue} className="sm:min-w-48">
          Continue
          <ArrowRight className="h-4 w-4" />
        </Button>
      </div>
    </div>
  )
}
