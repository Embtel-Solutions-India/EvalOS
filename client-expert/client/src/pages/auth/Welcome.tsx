import { LogIn, UserPlus } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Card } from '@shared/components/ui/card'
import { Logo } from '@shared/components/common/Logo'

/**
 * The client portal's front door (Unit 42).
 *
 * **A door, not the only door.** Every `portal_access` link EvalOS has already sent — one naming
 * a case, one naming a client — still works exactly as it did before this screen existed; the
 * server decides that, this screen only avoids implying otherwise. The line below is why: a
 * welcome screen offering just a password would tell someone holding a working link that their
 * link is broken, which is a worse bug than never having built the screen.
 *
 * **"Start a new evaluation" links to `/start`, which does not exist yet.** That funnel is
 * Unit 43. The link is real anyway — this screen is what a case gets born from later, and there
 * is nowhere else for it to point.
 */
export default function Welcome() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-8 px-4 py-12">
      <Logo size="lg" showTagline />

      <div className="w-full max-w-md space-y-4">
        <Link to="/start" className="block">
          <Card className="p-5 transition-colors hover:bg-accent">
            <div className="flex items-center gap-4">
              <UserPlus className="h-6 w-6 shrink-0 text-primary" aria-hidden="true" />
              <div>
                <p className="text-sm font-semibold text-foreground">Start a new evaluation</p>
                <p className="text-sm text-muted-foreground">
                  Tell us about yourself and what you need evaluated.
                </p>
              </div>
            </div>
          </Card>
        </Link>

        <Link to="/signin" className="block">
          <Card className="p-5 transition-colors hover:bg-accent">
            <div className="flex items-center gap-4">
              <LogIn className="h-6 w-6 shrink-0 text-primary" aria-hidden="true" />
              <div>
                <p className="text-sm font-semibold text-foreground">Sign in</p>
                <p className="text-sm text-muted-foreground">Already set a password? Sign in here.</p>
              </div>
            </div>
          </Card>
        </Link>

        {/*
          **The "opened a link we sent you?" note is deleted, reversing a rule this file used to
          state.** It was right while a mailed link was how a client reached anything: signing in
          instead of opening it would have lost them their only credential. Nothing mints a client
          link any more — clients arrive here from a button on the website and sign in for
          everything — so the advice now points away from the front door. Links already in inboxes
          are unaffected: `resolve` still admits them and they work by being opened, which no copy
          on this page changes.
        */}
      </div>
    </div>
  )
}
