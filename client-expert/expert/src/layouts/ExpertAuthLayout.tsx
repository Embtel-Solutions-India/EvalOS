import { LiquidBackground } from '@shared/components/common/LiquidBackground'
import { Logo } from '@shared/components/common/Logo'
import { PageTransition } from '@shared/components/common/PageTransition'

// Deliberately a single centered column, distinct from the client-facing
// AuthLayout's two-column marketing treatment — the Expert Portal is a
// separate, more utilitarian tool, not a client acquisition surface.
export function ExpertAuthLayout() {
  return (
    <div className="relative flex min-h-dvh flex-col items-center justify-center overflow-hidden bg-primary px-6 py-10">
      <LiquidBackground variant="dark" />
      <div className="relative mb-8">
        <Logo variant="light" />
      </div>
      <div className="glass-panel-dark relative w-full max-w-sm rounded-2xl p-6 shadow-xl sm:p-8">
        <PageTransition />
      </div>
      <p className="relative mt-6 text-xs text-primary-foreground/60">Expert Portal — International Evaluations</p>
    </div>
  )
}
