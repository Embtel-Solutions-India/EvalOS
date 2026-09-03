import { ArrowRight } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'
import { ServiceCategorySection } from '@/components/intake/ServiceCategorySection'
import { SERVICE_CATEGORIES, SERVICES, getService, getServicesByCategory } from '@/constants/serviceCatalog'
import { useAuth } from '@/hooks/useAuth'
import { getDraft, seedAboutYouFromAccount, setDraftPurpose, setDraftService } from '@/services/intakeService'

export default function ChooseService() {
  const navigate = useNavigate()
  const { isAuthenticated, user } = useAuth()
  const [selectedServiceId, setSelectedServiceId] = useState<string | undefined>(() => getDraft().serviceId)

  function handleContinue() {
    if (!selectedServiceId) return
    setDraftService(selectedServiceId)
    const service = getService(selectedServiceId)

    // Anyone already logged in (via /register or a prior request's About
    // You step) already has an account — skip straight past About You,
    // which is only for first-time account creation.
    if (isAuthenticated && user) {
      seedAboutYouFromAccount(user)
      // First-time visitors get their purpose set by AboutYou.tsx on submit;
      // returning clients skip that page entirely, so it has to happen here.
      if (service?.impliedPurpose) setDraftPurpose(service.impliedPurpose)
      navigate(service?.impliedPurpose ? '/start/questions' : '/start/purpose')
      return
    }

    navigate(service?.impliedPurpose ? '/start/about-you' : '/start/purpose')
  }

  return (
    <div>
      <h1 className="text-xl font-semibold tracking-tight text-foreground sm:text-2xl">What can we help you with?</h1>
      <p className="mt-1 text-sm text-muted-foreground">
        Choose the service that matches what you need — we'll only ask what's relevant from here.
      </p>

      <div className="mt-8 space-y-10">
        {SERVICE_CATEGORIES.map((category) => (
          <ServiceCategorySection
            key={category.id}
            category={category}
            services={getServicesByCategory(category.id)}
            selectedServiceId={selectedServiceId}
            onSelect={setSelectedServiceId}
          />
        ))}
      </div>

      <div className="sticky bottom-0 z-10 -mx-4 mt-8 flex justify-end border-t bg-background/95 px-4 py-4 backdrop-blur supports-[backdrop-filter]:bg-background/80 sm:static sm:mx-0 sm:border-t-0 sm:bg-transparent sm:px-0 sm:pt-6 sm:backdrop-blur-none">
        <Button size="lg" disabled={!selectedServiceId} onClick={handleContinue} className="w-full sm:w-auto sm:min-w-48">
          Continue
          <ArrowRight className="h-4 w-4" />
        </Button>
      </div>

      <p className="mt-4 text-center text-xs text-muted-foreground sm:text-left">
        {SERVICES.length} services across {SERVICE_CATEGORIES.length} categories. Not sure which one you need?{' '}
        Choose the closest match — our team will confirm the right service once we review your request.
      </p>
    </div>
  )
}
