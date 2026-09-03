import { z } from 'zod'

export const createTicketSchema = z.object({
  subject: z.string().min(1, 'Subject is required.'),
  applicationReference: z.string().optional(),
  description: z.string().min(10, 'Please provide at least 10 characters describing your issue.'),
})

export type CreateTicketFormValues = z.infer<typeof createTicketSchema>
