import { useId, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient, unwrap } from '@shared/services/apiClient'
import { Logo } from '@shared/components/common/Logo'
import { AppLoadingScreen } from '@shared/components/common/AppLoadingScreen'
import { ErrorState } from '@shared/components/common/ErrorState'
import { Button } from '@shared/components/ui/button'
import { Checkbox } from '@shared/components/ui/checkbox'
import signInArt from '@shared/assets/portal-signin.jpg'
import { LEGAL } from './legal'
import { PolicySummary } from './PolicySummary'

type TermsStatus = { required: boolean; version: string }

/**
 * The first-sign-in policy acceptance (Unit 72, D71), in front of the signed-in portal.
 *
 * Asked **once per account and policy version**: the server keeps the version each account
 * accepted, so a person who has accepted never sees this again until the policies change, on any
 * device. **Fails closed**: if the check cannot be made the portal is not shown, only a retry — the
 * acceptance is the one screen a person may not skip.
 *
 * @param audience whose routes to call: `/api/portal/client/terms` or `/api/portal/expert/terms`
 * @param onSignOut how this portal signs out (both call `signOut`, which revokes the token, Unit 75)
 */
export function TermsGate({
  audience,
  onSignOut,
  children,
}: {
  audience: 'client' | 'expert'
  onSignOut: () => void
  children: ReactNode
}) {
  const queryClient = useQueryClient()
  const key = ['portal-terms', audience]
  const status = useQuery<TermsStatus>({
    queryKey: key,
    queryFn: ({ signal }) => unwrap<TermsStatus>(apiClient.get(`/${audience}/terms`, { signal })),
    staleTime: Infinity,
  })
  const accept = useMutation({
    mutationFn: () => unwrap<TermsStatus>(apiClient.post(`/${audience}/terms`)),
    onSuccess: (fresh) => queryClient.setQueryData(key, fresh),
  })

  if (status.isPending) return <AppLoadingScreen />
  if (status.isError) {
    return (
      <div className="flex min-h-dvh items-center justify-center p-6">
        <ErrorState
          title="We could not check your account."
          description="Please try again in a moment."
          onRetry={() => void status.refetch()}
        />
      </div>
    )
  }
  if (!status.data.required) return <>{children}</>

  return (
    <AcceptTerms
      busy={accept.isPending}
      failed={accept.isError}
      onAccept={() => accept.mutate()}
      onSignOut={onSignOut}
    />
  )
}

function AcceptTerms({
  busy,
  failed,
  onAccept,
  onSignOut,
}: {
  busy: boolean
  failed: boolean
  onAccept: () => void
  onSignOut: () => void
}) {
  const [agreed, setAgreed] = useState(false)
  const checkboxId = useId()

  return (
    <div className="flex min-h-dvh lg:grid lg:grid-cols-2">
      <main className="flex flex-1 flex-col justify-center gap-6 px-6 py-10 sm:px-12 lg:px-16">
        <Logo className="items-start" />
        <div className="max-w-xl space-y-2">
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">Before you continue</h1>
          <p className="text-sm text-muted-foreground">
            Please read how we work with you and your information.
          </p>
        </div>

        <PolicySummary className="max-w-xl space-y-3 rounded-lg border border-border bg-muted/40 p-4 text-sm text-muted-foreground" newTab />

        <form
          className="max-w-xl space-y-4"
          onSubmit={(event) => {
            event.preventDefault()
            if (agreed) onAccept()
          }}
        >
          <div className="flex items-start gap-3">
            <Checkbox id={checkboxId} checked={agreed} onCheckedChange={(value) => setAgreed(value === true)} className="mt-0.5" />
            <label htmlFor={checkboxId} className="text-sm leading-relaxed text-foreground">
              I acknowledge the AI assistance described above and accept the{' '}
              <Link to={LEGAL.privacy.to} target="_blank" rel="noopener noreferrer" className="font-medium underline underline-offset-4">
                {LEGAL.privacy.label}
              </Link>
              ,{' '}
              <Link to={LEGAL.disclaimer.to} target="_blank" rel="noopener noreferrer" className="font-medium underline underline-offset-4">
                {LEGAL.disclaimer.label}
              </Link>{' '}
              and{' '}
              <Link to={LEGAL.retention.to} target="_blank" rel="noopener noreferrer" className="font-medium underline underline-offset-4">
                {LEGAL.retention.label}
              </Link>
              .
            </label>
          </div>
          {failed && (
            <p className="text-sm text-destructive" role="alert">
              That did not save. Please try again.
            </p>
          )}
          <div className="flex flex-wrap items-center gap-3">
            <Button type="submit" disabled={!agreed} loading={busy}>
              Accept and continue
            </Button>
            <Button type="button" variant="ghost" onClick={onSignOut}>
              Sign out
            </Button>
          </div>
        </form>
      </main>
      <div className="relative hidden bg-[#0b1f4d] lg:block">
        <img src={signInArt} alt="" className="absolute inset-0 h-full w-full object-cover object-right" />
      </div>
    </div>
  )
}
