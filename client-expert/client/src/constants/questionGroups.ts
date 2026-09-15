import type { QuestionGroup } from '@/types/intake'
import { COUNTRIES } from '@/constants/countries'

const countryOptions = COUNTRIES.map((country) => ({ value: country, label: country }))

// Reusable question groups, composed per service in serviceCatalog.ts. A
// new service is added by pointing at existing group ids (or adding a new
// group here) — no page or component needs to change.

export const QUESTION_GROUPS: Record<string, QuestionGroup> = {
  education: {
    id: 'education',
    title: 'Tell us about your education',
    description: 'The credential you want evaluated.',
    questions: [
      {
        id: 'highestDegree',
        label: 'Highest Degree / Credential',
        type: 'text',
        required: true,
        placeholder: 'e.g. Bachelor of Science',
      },
      { id: 'institution', label: 'Institution Name', type: 'text', required: true },
      { id: 'institutionCountry', label: 'Country', type: 'select', required: true, options: countryOptions },
      {
        id: 'graduationYear',
        label: 'Graduation Year',
        type: 'number',
        required: true,
        placeholder: 'e.g. 2019',
      },
      { id: 'fieldOfStudy', label: 'Field of Study', type: 'text', required: true },
    ],
  },

  professionalBackground: {
    id: 'professionalBackground',
    title: 'Tell us about your professional background',
    description: 'A quick summary of your current role and experience.',
    questions: [
      { id: 'jobTitle', label: 'Current Job Title', type: 'text', required: true },
      { id: 'employerName', label: 'Employer', type: 'text', required: true },
      {
        id: 'yearsOfExperience',
        label: 'Years of Relevant Experience',
        type: 'number',
        required: true,
        placeholder: 'e.g. 5',
      },
      {
        id: 'experienceSummary',
        label: 'Briefly describe your relevant professional experience',
        type: 'textarea',
        required: true,
        placeholder: 'What have you been doing, and why does it matter for this request?',
      },
    ],
  },

  translationDetails: {
    id: 'translationDetails',
    title: 'Tell us about your documents',
    description: 'What needs to be translated.',
    questions: [
      { id: 'sourceLanguage', label: 'Original Language', type: 'text', required: true, placeholder: 'e.g. Spanish' },
      { id: 'targetLanguage', label: 'Translate Into', type: 'text', required: true, placeholder: 'e.g. English' },
      {
        id: 'documentType',
        label: 'Type of Document',
        type: 'text',
        required: true,
        placeholder: 'e.g. Birth certificate, diploma, transcript',
      },
      {
        id: 'pageCount',
        label: 'Approximate Number of Pages',
        type: 'number',
        required: true,
        placeholder: 'e.g. 3',
      },
    ],
  },

  rfeDetails: {
    id: 'rfeDetails',
    title: 'Tell us about your RFE',
    description: 'Details from the Request for Evidence notice you received.',
    questions: [
      {
        id: 'petitionType',
        label: 'Petition Type',
        type: 'text',
        required: true,
        placeholder: 'e.g. H-1B, EB-2 NIW, O-1',
      },
      { id: 'receiptNumber', label: 'USCIS Receipt Number', type: 'text', required: false },
      { id: 'rfeDeadline', label: 'RFE Response Deadline', type: 'date', required: true },
      {
        id: 'rfeSummary',
        label: 'What is USCIS asking for?',
        type: 'textarea',
        required: true,
        placeholder: 'Summarize the evidence being requested.',
      },
    ],
  },

  businessPlanDetails: {
    id: 'businessPlanDetails',
    title: 'Tell us about your business',
    description: 'The business this plan will support.',
    questions: [
      { id: 'businessName', label: 'Business Name', type: 'text', required: true },
      { id: 'industry', label: 'Industry', type: 'text', required: true },
      {
        id: 'businessStage',
        label: 'Business Stage',
        type: 'radio',
        required: true,
        options: [
          { value: 'new', label: 'New / Not Yet Started' },
          { value: 'existing', label: 'Existing Business' },
        ],
      },
      {
        id: 'visaContext',
        label: 'What is this business plan for?',
        type: 'text',
        required: true,
        placeholder: 'e.g. E-2 visa, EB-5, L-1 new office',
      },
    ],
  },

  requestContext: {
    id: 'requestContext',
    title: 'Tell us about your request',
    description: 'A little more context helps our team get started.',
    questions: [
      {
        id: 'intendedUse',
        label: 'What will this be used for?',
        type: 'textarea',
        required: true,
        placeholder: 'e.g. Submitting with my H-1B petition, applying to graduate school...',
      },
      { id: 'deadline', label: 'Do you have a deadline?', type: 'date', required: false },
      {
        id: 'workingWithAttorney',
        label: 'Are you working with an attorney or law firm on this?',
        type: 'radio',
        required: true,
        options: [
          { value: 'yes', label: 'Yes' },
          { value: 'no', label: 'No' },
        ],
      },
      {
        id: 'additionalContext',
        label: 'Anything else we should know?',
        type: 'textarea',
        required: false,
        placeholder: 'Optional',
      },
    ],
  },

  attorneyInfo: {
    id: 'attorneyInfo',
    title: "Your attorney's information",
    description: "So we can coordinate with your legal team if needed.",
    showIf: (answers) => answers.workingWithAttorney === 'yes',
    questions: [
      { id: 'attorneyName', label: 'Attorney / Law Firm Name', type: 'text', required: true },
      { id: 'attorneyEmail', label: 'Attorney Email', type: 'text', required: true },
      {
        id: 'caseCommunicationPreference',
        label: 'How should we handle case-related communication?',
        type: 'radio',
        required: true,
        options: [
          { value: 'client_only', label: 'With me only' },
          { value: 'attorney_only', label: 'With my attorney only' },
          { value: 'both', label: 'With both of us' },
        ],
      },
    ],
  },

  employerInfo: {
    id: 'employerInfo',
    title: "Your employer's information",
    description: 'Since this request is being made through an employer.',
    showIf: (answers) => answers.clientType === 'employer',
    questions: [
      { id: 'employerLegalName', label: 'Employer Legal Name', type: 'text', required: true },
      { id: 'employerContactName', label: 'HR / Hiring Manager Contact Name', type: 'text', required: true },
      { id: 'employerContactEmail', label: 'Employer Contact Email', type: 'text', required: true },
    ],
  },
}

export function getQuestionGroups(ids: string[]): QuestionGroup[] {
  return ids.map((id) => QUESTION_GROUPS[id]).filter((group): group is QuestionGroup => Boolean(group))
}
