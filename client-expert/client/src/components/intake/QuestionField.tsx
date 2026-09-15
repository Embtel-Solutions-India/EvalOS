import { DatePicker } from '@shared/components/ui/date-picker'
import { Input } from '@shared/components/ui/input'
import { RadioGroup, RadioGroupItem } from '@shared/components/ui/radio-group'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@shared/components/ui/select'
import { Textarea } from '@shared/components/ui/textarea'
import { FormField } from '@shared/components/common/FormField'
import type { QuestionDefinition } from '@/types/intake'
import { cn } from '@shared/utils/cn'

interface QuestionFieldProps {
  question: QuestionDefinition
  value: string
  onChange: (value: string) => void
  error?: string
}

export function QuestionField({ question, value, onChange, error }: QuestionFieldProps) {
  const fieldId = `question-${question.id}`

  if (question.type === 'radio' && question.options) {
    return (
      <FormField label={question.label} htmlFor={fieldId} required={question.required} error={error} hint={question.helpText}>
        <RadioGroup value={value} onValueChange={onChange} className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
          {question.options.map((option) => (
            <label
              key={option.value}
              htmlFor={`${fieldId}-${option.value}`}
              className={cn(
                'flex cursor-pointer items-center gap-2.5 rounded-lg border p-3 text-sm transition-colors',
                value === option.value ? 'border-primary bg-primary/5 font-medium text-foreground' : 'text-foreground hover:bg-muted/40',
              )}
            >
              <RadioGroupItem value={option.value} id={`${fieldId}-${option.value}`} />
              {option.label}
            </label>
          ))}
        </RadioGroup>
      </FormField>
    )
  }

  if (question.type === 'select' && question.options) {
    return (
      <FormField label={question.label} htmlFor={fieldId} required={question.required} error={error} hint={question.helpText}>
        <Select value={value} onValueChange={onChange}>
          <SelectTrigger id={fieldId} invalid={Boolean(error)}>
            <SelectValue placeholder={question.placeholder ?? 'Select an option'} />
          </SelectTrigger>
          <SelectContent>
            {question.options.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </FormField>
    )
  }

  if (question.type === 'date') {
    return (
      <FormField label={question.label} htmlFor={fieldId} required={question.required} error={error} hint={question.helpText}>
        <DatePicker id={fieldId} invalid={Boolean(error)} value={value} onChange={(event) => onChange(event.target.value)} />
      </FormField>
    )
  }

  if (question.type === 'textarea') {
    return (
      <FormField label={question.label} htmlFor={fieldId} required={question.required} error={error} hint={question.helpText}>
        <Textarea
          id={fieldId}
          rows={4}
          invalid={Boolean(error)}
          placeholder={question.placeholder}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      </FormField>
    )
  }

  return (
    <FormField label={question.label} htmlFor={fieldId} required={question.required} error={error} hint={question.helpText}>
      <Input
        id={fieldId}
        type={question.type === 'number' ? 'number' : 'text'}
        invalid={Boolean(error)}
        placeholder={question.placeholder}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </FormField>
  )
}
