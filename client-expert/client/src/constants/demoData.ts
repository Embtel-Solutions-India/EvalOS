import type { AboutYouFormValues } from '@/schemas/intake'
import type { RequestAnswers } from '@/types/intake'

// Fictional sample values only, used by the "Fill Demo Data" convenience
// button so the guided flow can be clicked through quickly during a demo —
// the user still submits every step themselves, nothing here bypasses
// account creation or the questionnaire.

export const DEMO_ABOUT_YOU: AboutYouFormValues = {
  fullName: 'Amara Okafor',
  email: 'amara.okafor@example.com',
  phone: '+1 (555) 244-7810',
  countryOfResidence: 'Nigeria',
  currentLocation: 'Lagos',
  preferredContactMethod: 'email',
  clientType: 'individual',
  password: 'DemoPass1!',
  confirmPassword: 'DemoPass1!',
  acceptTerms: true,
}

// A flat pool covering every question id across every question group — the
// Questionnaire page just pulls whichever of these are visible on the
// current screen, however the groups happen to be composed for a service.
export const DEMO_ANSWERS: RequestAnswers = {
  highestDegree: 'Bachelor of Science',
  institution: 'University of Lagos',
  institutionCountry: 'Nigeria',
  graduationYear: '2018',
  fieldOfStudy: 'Computer Science',

  jobTitle: 'Software Engineer',
  employerName: 'Acme Technologies Inc.',
  yearsOfExperience: '6',
  experienceSummary: 'Led development of enterprise web applications and mentored junior engineers.',

  sourceLanguage: 'Spanish',
  targetLanguage: 'English',
  documentType: 'Birth certificate',
  pageCount: '2',

  petitionType: 'H-1B',
  receiptNumber: 'EAC1234567890',
  rfeDeadline: '2026-10-15',
  rfeSummary: 'USCIS is requesting additional evidence of specialty occupation.',

  businessName: 'Nova Ventures LLC',
  industry: 'Technology',
  businessStage: 'new',
  visaContext: 'E-2 visa',

  intendedUse: 'Submitting with my petition.',
  deadline: '2026-11-01',
  workingWithAttorney: 'yes',
  additionalContext: '',

  attorneyName: 'Smith & Associates',
  attorneyEmail: 'attorney@example.com',
  caseCommunicationPreference: 'both',

  employerLegalName: 'Acme Technologies Inc.',
  employerContactName: 'Jane Reyes',
  employerContactEmail: 'hr@acmetech.example.com',
}
