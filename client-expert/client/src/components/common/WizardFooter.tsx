import { ArrowLeft, ArrowRight } from 'lucide-react'
import { Button } from '@shared/components/ui/button'

interface WizardFooterProps {
  onBack?: () => void
  backLabel?: string
  nextLabel?: string
  loading?: boolean
  hideBack?: boolean
}

export function WizardFooter({ onBack, backLabel = 'Back', nextLabel = 'Save & Continue', loading, hideBack }: WizardFooterProps) {
  return (
    <div className="sticky bottom-0 z-10 -mx-4 mt-8 flex flex-col-reverse gap-3 border-t bg-background/95 px-4 py-4 backdrop-blur supports-[backdrop-filter]:bg-background/80 sm:static sm:mx-0 sm:flex-row sm:items-center sm:justify-between sm:bg-transparent sm:px-0 sm:pt-6 sm:backdrop-blur-none">
      {!hideBack && onBack ? (
        <Button type="button" variant="outline" onClick={onBack} disabled={loading}>
          <ArrowLeft className="h-4 w-4" />
          {backLabel}
        </Button>
      ) : (
        <span />
      )}
      <Button type="submit" loading={loading} className="sm:min-w-48">
        {nextLabel}
        <ArrowRight className="h-4 w-4" />
      </Button>
    </div>
  )
}
