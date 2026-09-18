import { Mail } from 'lucide-react'
import { Card } from '@shared/components/ui/card'
import { Logo } from '@shared/components/common/Logo'

/**
 * The expert portal's front door — <strong>a holding page, and deliberately only that</strong>
 * (2026-09-18).
 *
 * <p><strong>Why it exists at all.</strong> This app had no route at `/`. An expert who opened the
 * bare origin — or whose mail client dropped the URL fragment the case link carries — got the
 * 404 page, which reads as a broken portal rather than as a link problem. One honest screen is
 * worth more than a correct 404 here.
 *
 * <p><strong>Why it offers nothing.</strong> Staff-minted expert links are being retired: the
 * decision is that an expert signs in the same way a client does, and that process has not been
 * designed yet. So this page must not offer a sign-in that does not exist, and must not offer
 * "open the link we sent you" either, because that is the mechanism going away. It says where the
 * expert stands and stops. The two doors arrive when the process does.
 *
 * <p><strong>Do not grow this into an account shell.</strong> The one deleted on 2026-09-10 was
 * refused rather than parked (D1) — a password store needs a reset flow, and the mail channel that
 * makes one possible only arrived at Unit 52. Whatever replaces it is specced first; see
 * `open-decisions.md`.
 */
export default function Welcome() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-8 px-4 py-12">
      <div className="flex flex-col items-center gap-1">
        <Logo size="lg" />
        <span className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
          Expert Portal
        </span>
      </div>

      <div className="w-full max-w-md">
        <Card className="space-y-3 p-6 text-center">
          <h1 className="text-base font-semibold text-foreground">Welcome</h1>
          <p className="text-sm text-muted-foreground">
            This is where you&rsquo;ll review and sign the evaluation letters assigned to you, and
            track what you&rsquo;re owed.
          </p>
          <div className="flex items-start gap-3 rounded-md border border-border p-4 text-left">
            <Mail className="mt-0.5 h-5 w-5 shrink-0 text-primary" aria-hidden="true" />
            <p className="text-sm text-muted-foreground">
              {/*
                Named as a person rather than a process, because "sign-in is coming" invites an
                expert to come back and try a door that still is not there. Someone contacting
                them is a thing that actually happens next.
              */}
              Sign-in for experts isn&rsquo;t open yet. Your case manager will be in touch with
              whatever you need for the case you&rsquo;re working on.
            </p>
          </div>
        </Card>
      </div>
    </div>
  )
}
