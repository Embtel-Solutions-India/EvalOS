import { MOCK_INVOICES, MOCK_PAYMENTS } from '@/mock/mockData'
import { mockDelay } from '@shared/mock/mockDelay'
import type { Invoice, Payment } from '@/types'

export async function listPayments(): Promise<Payment[]> {
  await mockDelay()
  return MOCK_PAYMENTS
}

export async function listInvoices(): Promise<Invoice[]> {
  await mockDelay()
  return MOCK_INVOICES
}

export async function getInvoice(id: string): Promise<Invoice | null> {
  await mockDelay()
  return MOCK_INVOICES.find((invoice) => invoice.id === id) ?? null
}
