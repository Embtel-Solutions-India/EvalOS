import { Check, X } from 'lucide-react'
import { cn } from '@shared/utils/cn'

interface Rule {
  label: string
  test: (value: string) => boolean
}

const RULES: Rule[] = [
  { label: 'At least 8 characters', test: (v) => v.length >= 8 },
  { label: 'One uppercase letter', test: (v) => /[A-Z]/.test(v) },
  { label: 'One lowercase letter', test: (v) => /[a-z]/.test(v) },
  { label: 'One number', test: (v) => /[0-9]/.test(v) },
  { label: 'One special character', test: (v) => /[^A-Za-z0-9]/.test(v) },
]

function getScore(value: string): number {
  return RULES.filter((rule) => rule.test(value)).length
}

export function getPasswordStrengthLabel(value: string): 'Weak' | 'Medium' | 'Strong' {
  const score = getScore(value)
  if (score <= 2) return 'Weak'
  if (score <= 4) return 'Medium'
  return 'Strong'
}

interface PasswordStrengthProps {
  value: string
  className?: string
}

export function PasswordStrength({ value, className }: PasswordStrengthProps) {
  const score = getScore(value)
  const strength = getPasswordStrengthLabel(value)
  const barColor =
    strength === 'Weak' ? 'bg-destructive' : strength === 'Medium' ? 'bg-warning' : 'bg-success'

  return (
    <div className={cn('space-y-3', className)}>
      <div>
        <div className="mb-1.5 flex items-center justify-between text-xs">
          <span className="font-medium text-muted-foreground">Password strength</span>
          {value.length > 0 && (
            <span
              className={cn(
                'font-semibold',
                strength === 'Weak' && 'text-destructive',
                strength === 'Medium' && 'text-warning',
                strength === 'Strong' && 'text-success',
              )}
            >
              {strength}
            </span>
          )}
        </div>
        <div className="flex gap-1">
          {RULES.map((_, index) => (
            <span
              key={index}
              className={cn('h-1.5 flex-1 rounded-full bg-muted', index < score && barColor)}
            />
          ))}
        </div>
      </div>
      <ul className="grid grid-cols-1 gap-1 sm:grid-cols-2">
        {RULES.map((rule) => {
          const passed = rule.test(value)
          return (
            <li
              key={rule.label}
              className={cn('flex items-center gap-1.5 text-xs', passed ? 'text-success' : 'text-muted-foreground')}
            >
              {passed ? <Check className="h-3.5 w-3.5" /> : <X className="h-3.5 w-3.5 opacity-50" />}
              {rule.label}
            </li>
          )
        })}
      </ul>
    </div>
  )
}
