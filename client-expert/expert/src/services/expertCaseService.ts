import { STORAGE_KEYS } from '@shared/constants/storage'
import { INITIAL_EXPERT_CASES } from '@/mock/expertMockData'
import { mockDelay } from '@shared/mock/mockDelay'
import type { ExpertCase } from '@/types/expert'
import { readStorage, writeStorage } from '@shared/utils/storage'

function readCases(): ExpertCase[] {
  return readStorage<ExpertCase[]>(STORAGE_KEYS.expertCases, INITIAL_EXPERT_CASES)
}

function writeCases(cases: ExpertCase[]): void {
  writeStorage(STORAGE_KEYS.expertCases, cases)
}

export function isOverdue(expertCase: ExpertCase): boolean {
  return expertCase.signingStatus !== 'signed' && new Date(expertCase.dueDate).getTime() < Date.now()
}

export async function listCases(): Promise<ExpertCase[]> {
  await mockDelay()
  return readCases()
}

export async function getCase(id: string): Promise<ExpertCase | null> {
  await mockDelay()
  return readCases().find((expertCase) => expertCase.id === id) ?? null
}

// Opening a case for the first time moves it from "pending" to "viewed" —
// this is what lets the dashboard distinguish cases the expert hasn't
// looked at yet from ones they're actively working on.
export async function markViewed(id: string): Promise<ExpertCase | null> {
  const cases = readCases()
  const index = cases.findIndex((expertCase) => expertCase.id === id)
  if (index === -1) return null
  if (cases[index].signingStatus !== 'pending') return cases[index]
  const updated = { ...cases[index], signingStatus: 'viewed' as const }
  cases[index] = updated
  writeCases(cases)
  return updated
}

export async function uploadSignedDocument(id: string, file: File): Promise<ExpertCase | null> {
  await mockDelay(900)
  const cases = readCases()
  const index = cases.findIndex((expertCase) => expertCase.id === id)
  if (index === -1) return null
  const updated: ExpertCase = {
    ...cases[index],
    signingStatus: 'signed',
    signedDocument: { fileName: file.name, fileSizeBytes: file.size, uploadedAt: new Date().toISOString() },
  }
  cases[index] = updated
  writeCases(cases)
  return updated
}
