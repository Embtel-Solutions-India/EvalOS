import { getQuestionGroups } from '@/constants/questionGroups'
import { getService } from '@/constants/serviceCatalog'
import type { QuestionDefinition, QuestionGroup, RequestAnswers } from '@/types/intake'

// The engine: Service + Answers-so-far → the groups and questions that
// should actually be shown. This is what makes the questionnaire
// conditional (attorney info only if working with an attorney, employer
// info only for employer-type clients, etc.) without any per-service
// custom component.

export function getVisibleGroups(serviceId: string | undefined, answers: RequestAnswers): QuestionGroup[] {
  const service = getService(serviceId)
  if (!service) return []
  return getQuestionGroups(service.questionGroupIds).filter((group) => !group.showIf || group.showIf(answers))
}

export function getVisibleQuestions(group: QuestionGroup, answers: RequestAnswers): QuestionDefinition[] {
  return group.questions.filter((question) => !question.showIf || question.showIf(answers))
}

export function isGroupComplete(group: QuestionGroup, answers: RequestAnswers): boolean {
  return getVisibleQuestions(group, answers).every((question) => !question.required || Boolean(answers[question.id]?.trim()))
}
