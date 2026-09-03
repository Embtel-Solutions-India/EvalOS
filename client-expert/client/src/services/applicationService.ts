import { MOCK_APPLICATIONS } from '@/mock/mockData'
import { mockDelay } from '@shared/mock/mockDelay'
import type { Application } from '@/types'

export async function listApplications(): Promise<Application[]> {
  await mockDelay()
  return MOCK_APPLICATIONS
}

export async function getApplication(id: string): Promise<Application | null> {
  await mockDelay()
  return MOCK_APPLICATIONS.find((application) => application.id === id) ?? null
}
