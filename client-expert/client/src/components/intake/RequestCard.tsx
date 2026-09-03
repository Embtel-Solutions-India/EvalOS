import { ArrowRight, TriangleAlert } from 'lucide-react'
import { motion } from 'framer-motion'
import { Link } from 'react-router-dom'
import { Card, CardContent } from '@shared/components/ui/card'
import { RequestStatusBadge } from '@/components/intake/RequestStatusBadge'
import { getService } from '@/constants/serviceCatalog'
import type { ClientRequest } from '@/types/intake'
import { formatDateShort } from '@shared/utils/formatters'

export function RequestCard({ request }: { request: ClientRequest }) {
  const service = getService(request.serviceId)
  const Icon = service?.icon

  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      whileHover={{ y: -4 }}
      whileTap={{ scale: 0.98 }}
      transition={{ type: 'spring', stiffness: 400, damping: 28 }}
    >
      <Link to={`/requests/${request.id}`} className="block">
        <Card className="transition-colors duration-200 hover:border-primary/40 hover:shadow-md">
          <CardContent className="flex items-center gap-4 pt-6">
            {Icon && (
              <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary">
                <Icon className="h-5 w-5" />
              </span>
            )}
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold text-foreground">{service?.name ?? 'Request'}</p>
              <p className="text-xs text-muted-foreground">
                {request.referenceNumber} &middot; Submitted {formatDateShort(request.createdAt)}
              </p>
              {request.actionRequired && (
                <p className="mt-1 flex items-center gap-1 text-xs font-medium text-warning">
                  <TriangleAlert className="h-3 w-3" />
                  Action required
                </p>
              )}
            </div>
            <div className="flex shrink-0 items-center gap-3">
              <RequestStatusBadge status={request.status} />
              <ArrowRight className="h-4 w-4 text-muted-foreground" />
            </div>
          </CardContent>
        </Card>
      </Link>
    </motion.div>
  )
}
