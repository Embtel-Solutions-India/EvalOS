import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { LifeBuoy, Plus } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { Card } from '@shared/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@shared/components/ui/dialog'
import { Input } from '@shared/components/ui/input'
import { Pagination } from '@shared/components/ui/pagination'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@shared/components/ui/table'
import { Textarea } from '@shared/components/ui/textarea'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ErrorState } from '@shared/components/common/ErrorState'
import { FormField } from '@shared/components/common/FormField'
import { TableSkeleton } from '@shared/components/common/LoadingState'
import { PageHeader } from '@shared/components/common/PageHeader'
import { TicketStatusBadge } from '@/components/common/StatusBadge'
import { usePagination } from '@/hooks/usePagination'
import { createTicket, listTickets } from '@/services/ticketService'
import { createTicketSchema, type CreateTicketFormValues } from '@/schemas/ticket'
import { formatDateShort } from '@shared/utils/formatters'

export default function Tickets() {
  const queryClient = useQueryClient()
  const [dialogOpen, setDialogOpen] = useState(false)
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['tickets'], queryFn: listTickets })
  const { page, pageCount, pageItems, setPage } = usePagination(data ?? [])

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<CreateTicketFormValues>({
    resolver: zodResolver(createTicketSchema),
    defaultValues: { subject: '', applicationReference: '', description: '' },
  })

  async function onSubmit(values: CreateTicketFormValues) {
    await createTicket(values)
    await queryClient.invalidateQueries({ queryKey: ['tickets'] })
    toast.success('Support ticket created successfully.')
    reset()
    setDialogOpen(false)
  }

  return (
    <div>
      <PageHeader
        title="My Support Tickets"
        description="Track requests you've submitted to our support team."
        actions={
          <Button onClick={() => setDialogOpen(true)}>
            <Plus className="h-4 w-4" />
            Create Support Ticket
          </Button>
        }
      />

      {isLoading && <TableSkeleton />}
      {isError && <ErrorState description="We couldn't load your support tickets." onRetry={() => void refetch()} />}

      {!isLoading && !isError && data && data.length === 0 && (
        <EmptyState
          icon={LifeBuoy}
          title="No Support Tickets"
          description="Tickets you create will appear here."
          action={<Button onClick={() => setDialogOpen(true)}>Create Support Ticket</Button>}
        />
      )}

      {!isLoading && !isError && data && data.length > 0 && (
        <Card className="overflow-hidden p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Ticket ID</TableHead>
                <TableHead>Subject</TableHead>
                <TableHead>Application</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Created</TableHead>
                <TableHead>Updated</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {pageItems.map((ticket) => (
                <TableRow key={ticket.id}>
                  <TableCell className="font-medium text-foreground">{ticket.ticketNumber}</TableCell>
                  <TableCell>{ticket.subject}</TableCell>
                  <TableCell>{ticket.applicationReference ?? '—'}</TableCell>
                  <TableCell>
                    <TicketStatusBadge status={ticket.status} />
                  </TableCell>
                  <TableCell>{formatDateShort(ticket.createdAt)}</TableCell>
                  <TableCell>{formatDateShort(ticket.updatedAt)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <div className="border-t p-4">
            <Pagination page={page} pageCount={pageCount} onPageChange={setPage} />
          </div>
        </Card>
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create Support Ticket</DialogTitle>
            <DialogDescription className="sr-only">
              Submit a new support request to the International Evaluations team.
            </DialogDescription>
          </DialogHeader>
          <form className="space-y-5" onSubmit={handleSubmit(onSubmit)} noValidate>
            <FormField label="Subject" htmlFor="subject" required error={errors.subject?.message}>
              <Input id="subject" invalid={Boolean(errors.subject)} {...register('subject')} />
            </FormField>
            <FormField label="Application Reference" htmlFor="applicationReference" error={errors.applicationReference?.message}>
              <Input id="applicationReference" placeholder="e.g. IE-2026-000001" {...register('applicationReference')} />
            </FormField>
            <FormField label="Description" htmlFor="description" required error={errors.description?.message}>
              <Textarea id="description" rows={4} invalid={Boolean(errors.description)} {...register('description')} />
            </FormField>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setDialogOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" loading={isSubmitting}>
                Submit Ticket
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  )
}
