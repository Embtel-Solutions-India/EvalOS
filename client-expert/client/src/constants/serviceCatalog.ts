import {
  Award,
  Briefcase,
  Building2,
  FileSignature,
  FileText,
  FileWarning,
  Globe2,
  GraduationCap,
  Languages,
  ListChecks,
  Megaphone,
  Plane,
  Scale,
  ShieldCheck,
  TrendingUp,
  Trophy,
} from 'lucide-react'
import type { ServiceCategory, ServiceDefinition } from '@/types/intake'

// The schema-driven catalog: adding a new service here is the only change
// needed to offer it in the portal. The request screen renders purely from
// this data. There is no questionnaire (Unit 55).

export const SERVICE_CATEGORIES: ServiceCategory[] = [
  {
    id: 'credential_evaluations',
    name: 'Credential Evaluations',
    description: 'Evaluate your education or professional experience for use in the U.S.',
  },
  {
    id: 'expert_opinion_letters',
    name: 'Expert Opinion Letters',
    description: 'Professional opinion letters supporting immigration and employment matters.',
  },
  {
    id: 'other_professional_services',
    name: 'Other Professional Services',
    description: 'Translations, RFE support, and additional documentation services.',
  },
]

const expertLetterDocs = [
  'passport',
  'resume',
  'degreeCertificate',
  'transcript',
  'employmentVerificationLetters',
  'supportingDocuments',
]

export const SERVICES: ServiceDefinition[] = [
  // ---- Credential Evaluations ------------------------------------------
  {
    id: 'academic_evaluation',
    categoryId: 'credential_evaluations',
    name: 'Academic Evaluation',
    shortDescription: 'A summary equivalency of your degree or diploma.',
    bestFor: 'Best for university admissions or general reference',
    icon: GraduationCap,
    documentTemplateIds: ['passport', 'degreeCertificate', 'transcript', 'resume', 'supportingDocuments'],
  },
  {
    id: 'course_by_course_evaluation',
    categoryId: 'credential_evaluations',
    name: 'Course-by-Course Evaluation',
    shortDescription: 'A detailed, course-level breakdown with U.S. credit equivalency.',
    bestFor: 'Best for graduate school or professional licensing',
    icon: ListChecks,
    documentTemplateIds: ['passport', 'degreeCertificate', 'transcript', 'supportingDocuments'],
  },
  {
    id: 'work_experience_evaluation',
    categoryId: 'credential_evaluations',
    name: 'Work Experience Evaluation',
    shortDescription: 'Convert professional experience into U.S. degree equivalency.',
    bestFor: 'Best for employment-based petitions',
    icon: Briefcase,
    documentTemplateIds: ['passport', 'resume', 'employmentVerificationLetters', 'recommendationLetters'],
  },

  // ---- Expert Opinion Letters -------------------------------------------
  {
    id: 'h1b_expert_opinion_letter',
    categoryId: 'expert_opinion_letters',
    name: 'H-1B Expert Opinion Letter',
    shortDescription: 'Supports specialty occupation requirements for an H-1B petition.',
    icon: FileSignature,
    impliedPurpose: 'immigration',
    documentTemplateIds: expertLetterDocs,
  },
  {
    id: 'o1_expert_opinion_letter',
    categoryId: 'expert_opinion_letters',
    name: 'O-1A / O-1B Expert Opinion Letter',
    shortDescription: 'Supports extraordinary ability or achievement for an O-1 petition.',
    icon: Award,
    impliedPurpose: 'immigration',
    documentTemplateIds: [...expertLetterDocs, 'recommendationLetters'],
  },
  {
    id: 'eb1a_expert_opinion_letter',
    categoryId: 'expert_opinion_letters',
    name: 'EB-1A Expert Opinion Letter',
    shortDescription: 'Supports extraordinary ability criteria for an EB-1A petition.',
    icon: Trophy,
    impliedPurpose: 'immigration',
    documentTemplateIds: [...expertLetterDocs, 'recommendationLetters'],
  },
  {
    id: 'eb1b_expert_opinion_letter',
    categoryId: 'expert_opinion_letters',
    name: 'EB-1B Expert Opinion Letter',
    shortDescription: 'Supports outstanding professor or researcher criteria.',
    icon: GraduationCap,
    impliedPurpose: 'immigration',
    documentTemplateIds: [...expertLetterDocs, 'recommendationLetters'],
  },
  {
    id: 'eb2_niw_expert_opinion_letter',
    categoryId: 'expert_opinion_letters',
    name: 'EB-2 NIW Expert Opinion Letter',
    shortDescription: 'Supports a National Interest Waiver petition.',
    icon: Globe2,
    impliedPurpose: 'immigration',
    documentTemplateIds: [...expertLetterDocs, 'recommendationLetters'],
  },
  {
    id: 'l1_expert_opinion_letter',
    categoryId: 'expert_opinion_letters',
    name: 'L-1A / L-1B Expert Opinion Letter',
    shortDescription: 'Supports an intracompany transferee petition.',
    icon: Building2,
    impliedPurpose: 'immigration',
    documentTemplateIds: expertLetterDocs,
  },
  {
    id: 'tn_visa_expert_opinion_letter',
    categoryId: 'expert_opinion_letters',
    name: 'TN Visa Expert Opinion Letter',
    shortDescription: 'Supports a TN professional classification.',
    icon: Plane,
    impliedPurpose: 'immigration',
    documentTemplateIds: expertLetterDocs,
  },
  {
    id: 'h1b_wage_level_letter',
    categoryId: 'expert_opinion_letters',
    name: 'H-1B Wage Level Letter',
    shortDescription: 'An opinion on the appropriate prevailing wage level for a role.',
    icon: Scale,
    impliedPurpose: 'employment',
    documentTemplateIds: ['resume', 'employmentVerificationLetters'],
  },
  {
    id: 'cover_support_letter',
    categoryId: 'expert_opinion_letters',
    name: 'Cover Letter & Support Letter',
    shortDescription: 'A supporting narrative letter for your petition or application.',
    icon: FileText,
    documentTemplateIds: ['resume', 'supportingDocuments'],
  },

  // ---- Other Professional Services --------------------------------------
  {
    id: 'certified_translation',
    categoryId: 'other_professional_services',
    name: 'Certified Translations',
    shortDescription: 'Certified translation of academic or personal documents.',
    icon: Languages,
    documentTemplateIds: ['sourceDocument'],
  },
  {
    id: 'rfe_response',
    categoryId: 'other_professional_services',
    name: 'RFE Response',
    shortDescription: 'Documentation support to help respond to a USCIS Request for Evidence.',
    icon: FileWarning,
    impliedPurpose: 'immigration',
    documentTemplateIds: ['rfeNotice', 'originalPetition', 'supportingDocuments'],
  },
  {
    id: 'perm_recruitment_advertising',
    categoryId: 'other_professional_services',
    name: 'PERM Recruitment Advertising',
    shortDescription: 'Recruitment advertising support for the PERM labor certification process.',
    icon: Megaphone,
    impliedPurpose: 'employment',
    documentTemplateIds: ['jobPostingDraft'],
  },
  {
    id: 'business_plans',
    categoryId: 'other_professional_services',
    name: 'Business Plans',
    shortDescription: 'A professional business plan for visa or investment purposes.',
    icon: TrendingUp,
    documentTemplateIds: ['businessFinancials', 'supportingDocuments'],
  },
  {
    id: 'pre_filing_documentation_review',
    categoryId: 'other_professional_services',
    name: 'Pre-Filing Documentation Review',
    shortDescription: 'A review of your documentation before you file.',
    icon: ShieldCheck,
    impliedPurpose: 'immigration',
    documentTemplateIds: ['priorFilings', 'supportingDocuments'],
  },
]

export function getService(serviceId: string | undefined): ServiceDefinition | undefined {
  return SERVICES.find((service) => service.id === serviceId)
}

export function getServicesByCategory(categoryId: string): ServiceDefinition[] {
  return SERVICES.filter((service) => service.categoryId === categoryId)
}
