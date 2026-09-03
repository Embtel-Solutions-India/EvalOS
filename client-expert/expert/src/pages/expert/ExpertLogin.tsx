import { zodResolver } from '@hookform/resolvers/zod'
import { Eye, EyeOff } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { Input } from '@shared/components/ui/input'
import { FormField } from '@shared/components/common/FormField'
import { DEMO_EXPERT } from '@/mock/expertMockData'
import { ExpertAuthError } from '@/services/expertAuthService'
import { useExpertAuth } from '@/hooks/useExpertAuth'
import { loginSchema, type LoginFormValues } from '@shared/schemas/auth'

export default function ExpertLogin() {
  const { login } = useExpertAuth()
  const navigate = useNavigate()
  const [showPassword, setShowPassword] = useState(false)

  const {
    register,
    handleSubmit,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: '', password: '', rememberMe: false },
  })

  async function onSubmit(values: LoginFormValues) {
    try {
      const expert = await login({ email: values.email, password: values.password })
      toast.success(`Welcome back, ${expert.firstName}.`)
      navigate('/expert', { replace: true })
    } catch (error) {
      toast.error(
        error instanceof ExpertAuthError ? error.message : 'Unable to log in. Please check your credentials and try again.',
      )
    }
  }

  function fillDemoCredentials() {
    setValue('email', DEMO_EXPERT.user.email)
    setValue('password', DEMO_EXPERT.password)
  }

  return (
    <div>
      <h1 className="text-xl font-semibold tracking-tight text-primary-foreground">Expert Sign In</h1>
      <p className="mt-1 text-sm text-primary-foreground/70">Review and sign your assigned cases.</p>

      <form className="mt-6 space-y-4" onSubmit={handleSubmit(onSubmit)} noValidate>
        <FormField label="Email" htmlFor="email" required error={errors.email?.message} labelClassName="text-primary-foreground">
          <Input id="email" type="email" autoComplete="email" invalid={Boolean(errors.email)} placeholder="you@example.com" {...register('email')} />
        </FormField>

        <FormField label="Password" htmlFor="password" required error={errors.password?.message} labelClassName="text-primary-foreground">
          <div className="relative">
            <Input
              id="password"
              type={showPassword ? 'text' : 'password'}
              autoComplete="current-password"
              invalid={Boolean(errors.password)}
              className="pr-10"
              {...register('password')}
            />
            <button
              type="button"
              onClick={() => setShowPassword((prev) => !prev)}
              className="absolute inset-y-0 right-0 flex w-10 items-center justify-center text-muted-foreground hover:text-foreground"
              aria-label={showPassword ? 'Hide password' : 'Show password'}
            >
              {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
        </FormField>

        <Button type="submit" size="lg" className="w-full" loading={isSubmitting}>
          {isSubmitting ? 'Signing in...' : 'Sign In'}
        </Button>
      </form>

      <button
        type="button"
        onClick={fillDemoCredentials}
        className="mt-4 w-full text-center text-xs font-medium text-primary-foreground/60 hover:text-primary-foreground hover:underline"
      >
        Use demo expert credentials
      </button>
    </div>
  )
}
