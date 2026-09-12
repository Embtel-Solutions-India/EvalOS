import { Logo } from '@shared/components/common/Logo'

export function AppLoadingScreen() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-4 bg-background">
      <Logo />
      <div className="h-1 w-40 overflow-hidden rounded-full bg-muted">
        <div className="h-full w-1/3 animate-pulse rounded-full bg-primary" />
      </div>
    </div>
  )
}
