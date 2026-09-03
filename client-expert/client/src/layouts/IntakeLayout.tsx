import { Link } from 'react-router-dom'
import { Logo } from '@shared/components/common/Logo'
import { PageTransition } from '@shared/components/common/PageTransition'

// A deliberately quiet shell for the guided intake flow — just the logo,
// no sidebar, no dense navigation. The client's attention stays on
// completing their request, not on exploring the portal.
export function IntakeLayout() {
  return (
    <div className="min-h-dvh bg-background">
      <header className="border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/80">
        <div className="mx-auto flex h-20 max-w-3xl items-center justify-center px-4 sm:px-6">
          <Link to="/">
            <Logo size="lg" />
          </Link>
        </div>
      </header>
      <main className="mx-auto max-w-3xl px-4 py-8 sm:px-6 sm:py-10">
        <PageTransition />
      </main>
    </div>
  )
}
