import { Menu } from 'lucide-react'
import { Button } from '@shared/components/ui/button'

interface PortalHeaderProps {
  title: string
  onMenuClick: () => void
}

/**
 * The portal's top bar: a menu button and the page title.
 *
 * **Everything else went with the account shell (34d), and none of it is coming back.** The
 * avatar and initials, the name and email, the Profile and Settings links, Logout, and the
 * notification bell were all built on an email-and-password account — the model D1 refused
 * rather than deferred, because a password store needs the mail channel invariant 14 says
 * EvalOS does not have.
 *
 * **There is nothing to put in their place.** EvalOS deliberately does not hand the portal a
 * client's name for decoration; the credential is a scoped link, and the client already knows
 * who they are. A "signed in as…" line would be inventing an identity display out of a
 * credential that exists to avoid one.
 *
 * **No Logout, and no notification bell.** There is no session to end — closing the tab is the
 * whole of it — and no channel to notify through: the portal *is* the notification, which is
 * what D4 settled when it made `MISSING` and `INCORRECT` states a client can see rather than
 * messages EvalOS cannot send.
 */
export function PortalHeader({ title, onMenuClick }: PortalHeaderProps) {
  return (
    <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b-2 border-b-destructive bg-background/95 px-4 backdrop-blur supports-[backdrop-filter]:bg-background/80 sm:px-6">
      <Button variant="ghost" size="icon" className="lg:hidden" onClick={onMenuClick} aria-label="Open menu">
        <Menu className="h-5 w-5" />
      </Button>
      <h1 className="text-base font-semibold text-foreground sm:text-lg">{title}</h1>
    </header>
  )
}
