import { LogIn, UserPlus } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Card } from '@shared/components/ui/card'
import { Logo } from '@shared/components/common/Logo'

/**
 * The client portal's front door (Unit 42), and since 2026-09-12 the only one.
 *
 * **This used to say "a door, not the only door".** While staff could mint a client link, a
 * welcome screen offering only a password would have told someone holding a working link that
 * their link was broken — so this file carried a note sending them back to their inbox. Nothing
 * mints a client link any more: the portal is hosted at one origin, the website links to it, and
 * sign-in is the route to everything. The note is gone. `resolve` still admits links already
 * issued, so anyone still holding one reaches their case by opening it, which this screen neither
 * helps nor hinders.
 *
 * **"Start a new evaluation" points at `/start`, which is a PLACEHOLDER.** That funnel is Unit 43.
 * Until it ships the route exists and explains itself — it was a 404 for a while, which this
 * screen and `SignIn`'s UNKNOWN branch were both walking clients into.
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
