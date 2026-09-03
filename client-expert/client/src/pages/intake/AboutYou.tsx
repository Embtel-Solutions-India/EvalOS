import { zodResolver } from '@hookform/resolvers/zod'
import { ArrowLeft, ArrowRight, Eye, EyeOff } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@shared/components/ui/card'
import { Checkbox } from '@shared/components/ui/checkbox'
import { Input } from '@shared/components/ui/input'
import { RadioGroup, RadioGroupItem } from '@shared/components/ui/radio-group'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@shared/components/ui/select'
import { FillDemoDataButton } from '@/components/common/FillDemoDataButton'
import { FormField } from '@shared/components/common/FormField'
import { PasswordStrength } from '@/components/common/PasswordStrength'
import { COUNTRIES } from '@/constants/countries'
import { DEMO_ABOUT_YOU } from '@/constants/demoData'
import { getService } from '@/constants/serviceCatalog'
import { useAuth } from '@/hooks/useAuth'
import { getDraft, setDraftAboutYou, setDraftPurpose } from '@/services/intakeService'
import { AuthError } from '@/services/authService'
import { aboutYouSchema, type AboutYouFormValues } from '@/schemas/intake'
import { cn } from '@shared/utils/cn'

const CLIENT_TYPE_OPTIONS: { value: AboutYouFormValues['clientType']; label: string }[] = [
  { value: 'individual', label: 'Individual' },
  { value: 'employer', label: 'Employer' },
  { value: 'attorney', label: 'Attorney / Law Firm' },
  { value: 'organization', label: 'Organization' },
  { value: 'other', label: 'Other' },
]

const CONTACT_METHOD_OPTIONS: { value: AboutYouFormValues['preferredContactMethod']; label: string }[] = [
  { value: 'email', label: 'Email' },
  { value: 'phone', label: 'Phone' },
  { value: 'whatsapp', label: 'WhatsApp' },
]

export default function AboutYou() {
  const navigate = useNavigate()
  const { register: registerUser, updateUser, isAuthenticated, user } = useAuth()
  const [showPassword, setShowPassword] = useState(false)
  const draft = getDraft()
  const service = getService(draft.serviceId)

  useEffect(() => {
    if (!draft.serviceId) {
      navigate('/start/service', { replace: true })
    }
    // If already authenticated (e.g. resuming), skip straight ahead —
    // account creation is behind them.
    if (isAuthenticated && user) {
      navigate('/start/questions', { replace: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const {
    register,
    control,
    handleSubmit,
    watch,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<AboutYouFormValues>({
    resolver: zodResolver(aboutYouSchema),
    defaultValues: {
      fullName: draft.aboutYou?.fullName ?? '',
      email: draft.aboutYou?.email ?? '',
      phone: draft.aboutYou?.phone ?? '',
      countryOfResidence: draft.aboutYou?.countryOfResidence ?? '',
      currentLocation: draft.aboutYou?.currentLocation ?? '',
      preferredContactMethod: draft.aboutYou?.preferredContactMethod ?? 'email',
      clientType: draft.aboutYou?.clientType ?? 'individual',
      password: '',
      confirmPassword: '',
      acceptTerms: undefined,
    },
  })

  const passwordValue = watch('password') || ''

  async function onSubmit(values: AboutYouFormValues) {
    const [firstName, ...rest] = values.fullName.trim().split(/\s+/)
    const lastName = rest.join(' ') || firstName

    try {
      await registerUser({
        firstName,
        lastName,
        email: values.email,
        phone: values.phone,
        password: values.password,
      })
      updateUser({
        countryOfResidence: values.countryOfResidence,
        clientType: values.clientType,
        preferredContactMethod: values.preferredContactMethod,
      })

      setDraftAboutYou({
        fullName: values.fullName,
        email: values.email,
        phone: values.phone,
        countryOfResidence: values.countryOfResidence,
        currentLocation: values.currentLocation,
        preferredContactMethod: values.preferredContactMethod,
        clientType: values.clientType,
      })
      if (service?.impliedPurpose) {
        setDraftPurpose(service.impliedPurpose)
      }

      navigate('/verify-email')
    } catch (error) {
      toast.error(error instanceof AuthError ? error.message : 'We could not create your account. Please try again.')
    }
  }

  if (!draft.serviceId || (isAuthenticated && user)) return null

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate>
      <div className="flex items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight text-foreground sm:text-2xl">About You</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            A few details so we know who we're working with{service ? ` for your ${service.name.toLowerCase()}` : ''}.
          </p>
        </div>
        <FillDemoDataButton onClick={() => reset(DEMO_ABOUT_YOU)} />
      </div>

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>Your Information</CardTitle>
        </CardHeader>
        <CardContent className="space-y-5">
          <FormField label="Full Name" htmlFor="fullName" required error={errors.fullName?.message}>
            <Input id="fullName" invalid={Boolean(errors.fullName)} {...register('fullName')} />
          </FormField>
          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
            <FormField label="Email" htmlFor="email" required error={errors.email?.message}>
              <Input id="email" type="email" invalid={Boolean(errors.email)} {...register('email')} />
            </FormField>
            <FormField label="Phone Number" htmlFor="phone" required error={errors.phone?.message}>
              <Input id="phone" type="tel" invalid={Boolean(errors.phone)} {...register('phone')} />
            </FormField>
          </div>
          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
            <FormField label="Country of Residence" htmlFor="countryOfResidence" required error={errors.countryOfResidence?.message}>
              <Controller
                name="countryOfResidence"
                control={control}
                render={({ field }) => (
                  <Select value={field.value} onValueChange={field.onChange}>
                    <SelectTrigger id="countryOfResidence" invalid={Boolean(errors.countryOfResidence)}>
                      <SelectValue placeholder="Select country" />
                    </SelectTrigger>
                    <SelectContent>
                      {COUNTRIES.map((country) => (
                        <SelectItem key={country} value={country}>
                          {country}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
              />
            </FormField>
            <FormField label="Current Location" htmlFor="currentLocation" error={errors.currentLocation?.message}>
              <Input id="currentLocation" placeholder="City, if different" {...register('currentLocation')} />
            </FormField>
          </div>

          <FormField label="Preferred Contact Method" htmlFor="preferredContactMethod" required>
            <Controller
              name="preferredContactMethod"
              control={control}
              render={({ field }) => (
                <RadioGroup value={field.value} onValueChange={field.onChange} className="flex flex-wrap gap-4">
                  {CONTACT_METHOD_OPTIONS.map((option) => (
                    <label key={option.value} htmlFor={`contact-${option.value}`} className="flex items-center gap-2 text-sm font-medium text-foreground">
                      <RadioGroupItem value={option.value} id={`contact-${option.value}`} />
                      {option.label}
                    </label>
                  ))}
                </RadioGroup>
              )}
            />
          </FormField>
        </CardContent>
      </Card>

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>Who is this request for?</CardTitle>
        </CardHeader>
        <CardContent>
          <Controller
            name="clientType"
            control={control}
            render={({ field }) => (
              <RadioGroup value={field.value} onValueChange={field.onChange} className="grid grid-cols-1 gap-2.5 sm:grid-cols-3">
                {CLIENT_TYPE_OPTIONS.map((option) => (
                  <label
                    key={option.value}
                    htmlFor={`clientType-${option.value}`}
                    className={cn(
                      'flex cursor-pointer items-center gap-2.5 rounded-lg border p-3 text-sm font-medium transition-colors',
                      field.value === option.value ? 'border-primary bg-primary/5 text-foreground' : 'text-foreground hover:bg-muted/40',
                    )}
                  >
                    <RadioGroupItem value={option.value} id={`clientType-${option.value}`} />
                    {option.label}
                  </label>
                ))}
              </RadioGroup>
            )}
          />
        </CardContent>
      </Card>

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>Create Your Account</CardTitle>
        </CardHeader>
        <CardContent className="space-y-5">
          <FormField label="Password" htmlFor="password" required error={errors.password?.message}>
            <div className="relative">
              <Input
                id="password"
                type={showPassword ? 'text' : 'password'}
                autoComplete="new-password"
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
          {passwordValue.length > 0 && <PasswordStrength value={passwordValue} />}
          <FormField label="Confirm Password" htmlFor="confirmPassword" required error={errors.confirmPassword?.message}>
            <Input id="confirmPassword" type={showPassword ? 'text' : 'password'} invalid={Boolean(errors.confirmPassword)} {...register('confirmPassword')} />
          </FormField>

          <div>
            <label className="flex items-start gap-2.5 text-sm text-muted-foreground">
              <Controller
                name="acceptTerms"
                control={control}
                render={({ field }) => (
                  <Checkbox className="mt-0.5" checked={field.value === true} onCheckedChange={(checked) => field.onChange(checked === true)} />
                )}
              />
              <span>
                I agree to the{' '}
                <a href="https://internationalevaluations.com/terms" target="_blank" rel="noreferrer" className="font-medium text-primary hover:underline">
                  Terms &amp; Conditions
                </a>{' '}
                and{' '}
                <a href="https://internationalevaluations.com/privacy" target="_blank" rel="noreferrer" className="font-medium text-primary hover:underline">
                  Privacy Policy
                </a>
                .
              </span>
            </label>
            {errors.acceptTerms && (
              <p className="mt-1.5 text-xs font-medium text-destructive" role="alert">
                {errors.acceptTerms.message}
              </p>
            )}
          </div>
        </CardContent>
      </Card>

      <div className="mt-8 flex flex-col-reverse gap-3 border-t pt-6 sm:flex-row sm:items-center sm:justify-between">
        <Button type="button" variant="outline" onClick={() => navigate(service?.impliedPurpose ? '/start/service' : '/start/purpose')} disabled={isSubmitting}>
          <ArrowLeft className="h-4 w-4" />
          Back
        </Button>
        <Button type="submit" size="lg" loading={isSubmitting} className="sm:min-w-56">
          Create Account &amp; Continue
          <ArrowRight className="h-4 w-4" />
        </Button>
      </div>
    </form>
  )
}
