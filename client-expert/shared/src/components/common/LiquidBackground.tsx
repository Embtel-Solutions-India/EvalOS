import { cn } from '@shared/utils/cn'

interface LiquidBackgroundProps {
  className?: string
  variant?: 'light' | 'dark'
}

// Soft, slow-drifting gradient blobs in the brand's navy/crimson palette —
// the same glow technique International Evaluations' own site uses behind
// its hero content, recomposed here as a reusable decorative layer for the
// portal's marketing and auth surfaces.
export function LiquidBackground({ className, variant = 'light' }: LiquidBackgroundProps) {
  return (
    <div className={cn('pointer-events-none absolute inset-0 overflow-hidden', className)} aria-hidden="true">
      <div
        className="animate-liquid-a absolute -left-[10%] -top-[15%] h-[55%] w-[65%] rounded-full opacity-70 blur-3xl"
        style={{
          background:
            variant === 'light'
              ? 'radial-gradient(circle, color-mix(in oklab, var(--brand-navy) 22%, transparent) 0%, transparent 70%)'
              : 'radial-gradient(circle, color-mix(in oklab, var(--brand-navy) 55%, transparent) 0%, transparent 70%)',
        }}
      />
      <div
        className="animate-liquid-b absolute -right-[15%] top-[5%] h-[50%] w-[55%] rounded-full opacity-60 blur-3xl"
        style={{
          background: 'radial-gradient(circle, color-mix(in oklab, var(--brand-crimson) 20%, transparent) 0%, transparent 70%)',
        }}
      />
      <div
        className="animate-liquid-a absolute bottom-[-20%] left-[15%] h-[50%] w-[50%] rounded-full opacity-50 blur-3xl"
        style={{
          background:
            variant === 'light'
              ? 'radial-gradient(circle, color-mix(in oklab, var(--brand-navy) 14%, transparent) 0%, transparent 70%)'
              : 'radial-gradient(circle, color-mix(in oklab, white 12%, transparent) 0%, transparent 70%)',
        }}
      />
    </div>
  )
}
