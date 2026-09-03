import type { EvaluationPurpose, EvaluationType } from '@/types'

// Still used by the legacy Reports / Report Detail pages (kept as
// secondary portal pages, not part of the new guided intake flow) and by
// the new intake's Purpose step, which reuses the same purpose vocabulary.

export const EVALUATION_TYPE_OPTIONS: { value: EvaluationType; label: string; description: string }[] = [
  {
    value: 'general',
    label: 'General Evaluation',
    description: 'A summary of your credential and its U.S. equivalency.',
  },
  {
    value: 'course_by_course',
    label: 'Course-by-Course Evaluation',
    description: 'A detailed breakdown of individual courses, credits and grades.',
  },
  {
    value: 'credential',
    label: 'Credential Evaluation',
    description: 'Verification and equivalency of a single credential or degree.',
  },
  {
    value: 'document',
    label: 'Document Evaluation',
    description: 'Review of academic or professional documents for authenticity.',
  },
  {
    value: 'professional',
    label: 'Professional Evaluation',
    description: 'Evaluation of professional experience and licensure credentials.',
  },
  {
    value: 'custom',
    label: 'Custom Evaluation',
    description: 'A tailored evaluation for a specific requirement.',
  },
]

export const EVALUATION_PURPOSE_OPTIONS: { value: EvaluationPurpose; label: string }[] = [
  { value: 'immigration', label: 'Immigration' },
  { value: 'employment', label: 'Employment' },
  { value: 'education', label: 'Education' },
  { value: 'university_admission', label: 'University Admission' },
  { value: 'professional_licensing', label: 'Professional Licensing' },
  { value: 'government', label: 'Government' },
  { value: 'other', label: 'Other' },
]
