import { MailCheck } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { useAuth } from '@/hooks/useAuth'
import { resendVerificationEmail } from '@/services/authService'

const RESEND_COOLDOWN_SECONDS = 45

export default function VerifyEmail() {
  const { user, isAuthenticated, verifyEmail } = useAuth()
  const navigate = useNavigate()
  const [cooldown, setCooldown] = useState(0)
  const [isResending, setIsResending] = useState(false)
  const [isVerifying, setIsVerifying] = useState(false)

  useEffect(() => {
    if (cooldown <= 0) return
    const timer = window.setInterval(() => setCooldown((value) => value - 1), 1000)
    return () => window.clearInterval(timer)
  }, [cooldown])

  if (!isAuthenticated || !user) {
    return (
      <div className="text-center">
        <h1 className="text-xl font-semibold tracking-tight text-foreground sm:text-2xl">Verify Your Email</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Please create an account or log in to verify your email address.
        </p>
        <Button asChild className="mt-6">
          <Link to="/login">Log In</Link>
        </Button>
      </div>
    )
  }

  async function handleResend() {
    setIsResending(true)
    try {
      await resendVerificationEmail()
      toast.success('Verification email sent.')
      setCooldown(RESEND_COOLDOWN_SECONDS)
    } finally {
      setIsResending(false)
    }
  }

  async function handleContinue() {
    setIsVerifying(true)
    try {
      await verifyEmail()
      // Was: profileCompleted ? '/dashboard' : '/start/questions'. The intake funnel is parked
      // (D2, 2026-09-10), so there is nowhere to send an incomplete profile but the dashboard.
      navigate('/dashboard', { replace: true })
    } finally {
      setIsVerifying(false)
    }
  }

  return (
    <div className="text-center">
      <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-primary/10 text-primary">
        <MailCheck className="h-6 w-6" />
      </div>
      <h1 className="mt-4 text-xl font-semibold tracking-tight text-foreground sm:text-2xl">Verify Your Email</h1>
      <p className="mt-2 text-sm text-muted-foreground">We've sent a verification link to:</p>
      <p className="mt-1 text-sm font-semibold text-foreground">{user.email}</p>
      <p className="mt-4 text-sm text-muted-foreground">Please check your inbox and click the verification link.</p>

      <div className="mt-8 flex flex-col gap-3">
        <Button onClick={() => void handleContinue()} loading={isVerifying} size="lg">
          Continue
        </Button>
        <Button
          type="button"
          variant="outline"
          onClick={() => void handleResend()}
          disabled={cooldown > 0}
          loading={isResending}
        >
          {cooldown > 0 ? `Resend available in ${cooldown} seconds` : 'Resend Email'}
        </Button>
      </div>
    </div>
  )
}
