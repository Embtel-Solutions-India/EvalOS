import { useQuery } from '@tanstack/react-query'
import { CheckCircle2, Plus, TriangleAlert } from 'lucide-react'
import { motion } from 'framer-motion'
import { Link } from 'react-router-dom'
import heroImage from '@/assets/hero.png'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent } from '@shared/components/ui/card'
import { EmptyState } from '@shared/components/common/EmptyState'
import { ListSkeleton } from '@shared/components/common/LoadingState'
import { RequestCard } from '@/components/intake/RequestCard'
import { RequestStatusBadge } from '@/components/intake/RequestStatusBadge'
import { getService } from '@/constants/serviceCatalog'
import { useAuth } from '@/hooks/useAuth'
import { REQUEST_STATUS_DESCRIPTIONS, listRequests } from '@/services/intakeService'

function greeting(): string {
  const hour = new Date().getHours()
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}

export default function Dashboard() {
  const { user } = useAuth()
  const { data, isLoading } = useQuery({ queryKey: ['requests'], queryFn: listRequests })

  const requests = data ?? []
  const primaryRequest = requests[0]
  const primaryService = primaryRequest ? getService(primaryRequest.serviceId) : undefined
  const otherRequests = requests.slice(1)

  return (
    <div>
      <motion.h1
        initial={{ opacity: 0, y: -8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35 }}
        className="text-xl font-semibold tracking-tight text-foreground sm:text-2xl"
      >
        {greeting()}, {user?.firstName}
      </motion.h1>

      {isLoading && (
        <div className="mt-6">
          <ListSkeleton rows={1} />
        </div>
      )}

      {!isLoading && !primaryRequest && (
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4, delay: 0.1 }}
          className="mt-6 flex flex-col items-center rounded-xl border border-dashed bg-muted/30 px-6 py-10 text-center"
        >
          <motion.img
            src={heroImage}
            alt=""
            aria-hidden="true"
            className="w-28 drop-shadow-lg"
            animate={{ y: [0, -8, 0] }}
            transition={{ duration: 4, repeat: Infinity, ease: 'easeInOut' }}
          />
          <EmptyState
            className="mt-2 border-0 bg-transparent p-0"
            icon={Plus}
            title="No requests yet"
            description="Start a request and we'll guide you through exactly what's needed."
            action={
              <Button asChild>
                <Link to="/start/service">Start a New Request</Link>
              </Button>
            }
          />
        </motion.div>
      )}

      {!isLoading && primaryRequest && (
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4 }}
          className="mt-6"
        >
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Your Request</p>
            <div className="mt-1.5 flex flex-wrap items-center justify-between gap-3">
              <h2 className="text-lg font-semibold text-foreground">{primaryService?.name ?? 'Request'}</h2>
              <RequestStatusBadge status={primaryRequest.status} />
            </div>

            <p className="mt-3 text-sm text-muted-foreground">{REQUEST_STATUS_DESCRIPTIONS[primaryRequest.status]}</p>

            <div className="mt-5 rounded-xl border p-4">
              {primaryRequest.actionRequired ? (
                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                  <div className="flex items-start gap-2.5">
                    <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-warning" />
                    <div>
                      <p className="text-sm font-semibold text-foreground">Action required</p>
                      <p className="text-sm text-muted-foreground">{primaryRequest.actionRequired}</p>
                    </div>
                  </div>
                  <Button asChild size="sm" className="shrink-0">
                    <Link to="/documents">Upload Document</Link>
                  </Button>
                </div>
              ) : (
                <div className="flex items-center gap-2.5">
                  <CheckCircle2 className="h-4 w-4 shrink-0 text-success" />
                  <div>
                    <p className="text-sm font-semibold text-foreground">Next Step</p>
                    <p className="text-sm text-muted-foreground">Nothing needed from you right now.</p>
                  </div>
                </div>
              )}
            </div>

            <Button asChild variant="link" className="mt-2 px-0">
              <Link to={`/requests/${primaryRequest.id}`}>View full status</Link>
            </Button>
          </CardContent>
        </Card>
        </motion.div>
      )}

      {otherRequests.length > 0 && (
        <div className="mt-8">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold text-foreground">Other Requests</h2>
            <Button asChild variant="link" size="sm" className="px-0">
              <Link to="/requests">View all</Link>
            </Button>
          </div>
          <div className="space-y-3">
            {otherRequests.map((request) => (
              <RequestCard key={request.id} request={request} />
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
