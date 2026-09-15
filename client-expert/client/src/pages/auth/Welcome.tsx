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
 * **The two doors are sign up and sign in**, which is what the client flow asks for. The first
 * pointed at `/start` — a placeholder saying we could not take a new client — until 2026-09-15,
 * because nothing created a `client_account` at runtime. It creates one now, along with the GHL
 * contact. **Requesting an evaluation is a separate step** reached from the dashboard, and is
 * Unit 43; this card must not promise it.
 */
export default function Welcome() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-8 px-4 py-12">
      <Logo size="lg" showTagline />

      <div className="w-full max-w-md space-y-4">
        <Link to="/signup" className="block">
          <Card className="p-5 transition-colors hover:bg-accent">
            <div className="flex items-center gap-4">
              <UserPlus className="h-6 w-6 shrink-0 text-primary" aria-hidden="true" />
              <div>
                <p className="text-sm font-semibold text-foreground">Create an account</p>
                <p className="text-sm text-muted-foreground">
                  New here? Set up an account and request your evaluation from inside.
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
