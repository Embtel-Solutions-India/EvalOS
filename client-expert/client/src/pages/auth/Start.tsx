import { ArrowLeft } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Card } from '@shared/components/ui/card'
import { Logo } from '@shared/components/common/Logo'

/**
 * A placeholder, and deliberately a thin one — **Unit 43 replaces this whole file.**
 *
 * **It exists because two shipped screens already point here and neither could be left pointing
 * at a 404.** `/welcome` offers "Start a new evaluation", and `SignIn`'s `UNKNOWN` branch offers
 * the same button to somebody who has just been told their email is not in our system — which is
 * exactly the person least able to absorb a "page not found". The funnel behind it is Unit 43,
 * specced and not built; the gap between the screens and the funnel is what this covers.
 *
 * **It names no phone number, address or mailbox.** Inventing one would be worse than saying
 * less: a wrong contact detail on the page a new client lands on is a lost client. The wording
 * matches `MAIL_UNAVAILABLE` on the sign-in screen, which already says "contact us" without
 * naming a channel, so the two screens do not contradict each other about how to reach us.
 *
 * **When Unit 43 ships, delete this file** — do not grow a funnel inside it. It has no form, no
 * state and no service call on purpose, so that replacing it is a deletion rather than a merge.
 */
export default function Start() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-8 px-4 py-12">
      <Logo size="lg" showTagline />

      <Card className="w-full max-w-md space-y-4 p-6">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-foreground">
            Starting a new evaluation
          </h1>
          <p className="mt-2 text-sm text-muted-foreground">
            We can't take new evaluations through the portal just yet. Please contact us and we'll
            get yours started.
          </p>
          <p className="mt-3 text-sm text-muted-foreground">
            We'll set up your account at the same time, so you can sign in here to follow your case,
            send us documents and see your invoices.
          </p>
        </div>

        <Link
          to="/signin"
          className="inline-flex items-center gap-2 text-sm font-medium text-primary underline-offset-4 hover:underline"
        >
          <ArrowLeft className="h-4 w-4" aria-hidden="true" />
          Back to sign in
        </Link>
      </Card>
    </div>
  )
}
