import logoImage from '@shared/assets/logo.png'
import { cn } from '@shared/utils/cn'

interface LogoProps {
  className?: string
  variant?: 'light' | 'dark'
  showTagline?: boolean
  size?: 'md' | 'lg'
}

const IMAGE_SIZE: Record<NonNullable<LogoProps['size']>, string> = {
  md: 'h-6 w-auto sm:h-7',
  lg: 'h-9 w-auto sm:h-11',
}

// The logo artwork is full-color (navy + red) and reads fine directly on a
// light background. On a dark background (the sidebar, the auth panel) its
// navy details would blend in, so `variant="light"` wraps it in a small
// white backing chip instead of trying to recolor the raster image.
export function Logo({ className, variant = 'dark', showTagline = false, size = 'md' }: LogoProps) {
  const isLight = variant === 'light'
  return (
    <div className={cn('inline-flex flex-col items-center gap-1', className)}>
      <span className={cn('inline-flex items-center rounded-md', isLight && 'bg-white px-2 py-1.5 shadow-sm')}>
        <img src={logoImage} alt="International Evaluations" className={IMAGE_SIZE[size]} />
      </span>
      {showTagline && (
        <span className={cn('text-[11px] font-medium uppercase tracking-wide', isLight ? 'text-white/60' : 'text-muted-foreground')}>
          Client Portal
        </span>
      )}
    </div>
  )
}
