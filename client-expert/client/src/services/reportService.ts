import { MOCK_REPORTS } from '@/mock/mockData'
import { mockDelay } from '@shared/mock/mockDelay'
import type { Report } from '@/types'

export async function listReports(): Promise<Report[]> {
  await mockDelay()
  return MOCK_REPORTS
}

export async function getReport(id: string): Promise<Report | null> {
  await mockDelay()
  return MOCK_REPORTS.find((report) => report.id === id) ?? null
}
