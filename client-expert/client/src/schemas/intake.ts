import { z } from 'zod'

/**
 * The same rule as {@link passwordRules}, in words, for showing BEFORE somebody submits.
 *
 * **Here rather than in the screen, because it is the rule restated and drift would lie.** The
 * five `.regex` calls below are the truth; a sentence living in a component would be a second
 * copy nobody updates, and a password hint that disagrees with the validator is worse than none
 * — it tells the client their correct password is wrong.
 *
 * Revealing all five up front is the point. `SetPassword` surfaced them one failed submit at a
 * time, so choosing a password could take five rejections to learn what was wanted.
 */
export const PASSWORD_REQUIREMENTS =
  'At least 8 characters, with an uppercase letter, a lowercase letter, a number and a special character.'

export const passwordRules = z
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
