import { LogIn } from 'lucide-react'
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
 * **"Already set a password?" was a trap, fixed 2026-09-17.** It gated the sign-in door on
 * having a password — so a client who signed up but never opened their link (the single most
 * common state a new account is in) read "no" and took the other door, which answered "you
 * already have an account with us". Two cards, and the only honest route was behind the one whose
 * label said it was not for them. Sign-in is the door for everyone who exists: `identify` sorts
 * out which of them has a password and mails a link to whoever does not.
 *
 * **One door since Unit 64 (2026-09-29): sign in.** There is no sign-up and no request: a
 * client's account is opened when their case starts, and the set-password email is how they first
 * arrive. The note under the card says so, for the client who finds the portal before that email.
 */
export default function Welcome() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-8 px-4 py-12">
      <Logo size="lg" showTagline />

      <div className="w-full max-w-md space-y-4">
        <Link to="/signin" className="block">
          <Card className="p-5 transition-colors hover:bg-accent">
            <div className="flex items-center gap-4">
              <LogIn className="h-6 w-6 shrink-0 text-primary" aria-hidden="true" />
              <div>
                <p className="text-sm font-semibold text-foreground">Sign in</p>
                <p className="text-sm text-muted-foreground">
                  Sign in to follow your case — or we&rsquo;ll email you a link if you
                  haven&rsquo;t set a password yet.
                </p>
              </div>
            </div>
          </Card>
        </Link>

        <p className="text-center text-sm text-muted-foreground">
          New to International Evaluations? Your portal account opens when our team starts your
          case — we&rsquo;ll email you a link to set your password.
        </p>

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
