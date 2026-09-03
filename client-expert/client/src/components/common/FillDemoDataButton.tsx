import { Wand2 } from 'lucide-react'
import { Button } from '@shared/components/ui/button'

interface FillDemoDataButtonProps {
  onClick: () => void
}

// Dev/demo convenience only: pre-fills the current step's form with
// realistic sample values so the wizard can be clicked through quickly.
// It never submits or skips a step on its own — the user still reviews
// and clicks "Save & Continue" themselves.
export function FillDemoDataButton({ onClick }: FillDemoDataButtonProps) {
  return (
    <Button type="button" variant="outline" size="sm" onClick={onClick}>
      <Wand2 className="h-3.5 w-3.5" />
      Fill Demo Data
    </Button>
  )
}
