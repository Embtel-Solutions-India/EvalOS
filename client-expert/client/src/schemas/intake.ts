import { z } from 'zod'

const passwordRules = z
  .string()
  .min(8, 'Password must be at least 8 characters.')
  .regex(/[A-Z]/, 'Password must include an uppercase letter.')
  .regex(/[a-z]/, 'Password must include a lowercase letter.')
  .regex(/[0-9]/, 'Password must include a number.')
  .regex(/[^A-Za-z0-9]/, 'Password must include a special character.')

export const aboutYouSchema = z
  .object({
    fullName: z.string().min(1, 'Full name is required.'),
    email: z.string().min(1, 'Email is required.').email('Please enter a valid email address.'),
    phone: z.string().min(1, 'Phone number is required.'),
    countryOfResidence: z.string().min(1, 'Country of residence is required.'),
    currentLocation: z.string().optional(),
    preferredContactMethod: z.enum(['email', 'phone', 'whatsapp']),
    clientType: z.enum(['individual', 'employer', 'attorney', 'organization', 'other']),
    password: passwordRules,
    confirmPassword: z.string().min(1, 'Please confirm your password.'),
    acceptTerms: z.literal(true, {
      message: 'You must agree to the Terms & Conditions and Privacy Policy.',
    }),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Passwords do not match.',
    path: ['confirmPassword'],
  })

export type AboutYouFormValues = z.infer<typeof aboutYouSchema>
