import { MOCK_TICKETS } from '@/mock/mockData'
import { mockDelay } from '@shared/mock/mockDelay'
import type { SupportTicket } from '@/types'

let ticketStore: SupportTicket[] = structuredClone(MOCK_TICKETS)

export async function listTickets(): Promise<SupportTicket[]> {
  await mockDelay()
  // A fresh array reference each call — TanStack Query (and React in
  // general) detects new data by reference, so mutating ticketStore in
  // place and returning it as-is would look identical to the previous
  // result and never trigger a re-render.
  return [...ticketStore]
}

export interface CreateTicketPayload {
  subject: string
  applicationReference?: string
  description: string
}

export async function createTicket(payload: CreateTicketPayload): Promise<SupportTicket> {
  await mockDelay(600)
  const ticket: SupportTicket = {
    id: `tix-${Date.now()}`,
    ticketNumber: `TCK-${Math.floor(10000 + Math.random() * 89999)}`,
    subject: payload.subject,
    applicationReference: payload.applicationReference,
    status: 'open',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    description: payload.description,
  }
  ticketStore = [ticket, ...ticketStore]
  return ticket
}
