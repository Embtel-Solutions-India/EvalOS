import { ArrowRight, FileText } from 'lucide-react'
import { motion } from 'framer-motion'
import { Link } from 'react-router-dom'
import { Card, CardContent } from '@shared/components/ui/card'
import { SigningStatusBadge } from '@/components/expert/SigningStatusBadge'
import type { ExpertCase } from '@/types/expert'
import { formatDateShort } from '@shared/utils/formatters'

export function ExpertCaseCard({ expertCase }: { expertCase: ExpertCase }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      whileHover={{ y: -4 }}
      whileTap={{ scale: 0.98 }}
      transition={{ type: 'spring', stiffness: 400, damping: 28 }}
    >
      <Link to={`/expert/cases/${expertCase.id}`} className="block">
        <Card className="transition-colors duration-200 hover:border-primary/40 hover:shadow-md">
          <CardContent className="flex items-center gap-4 pt-6">
            <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary">
              <FileText className="h-5 w-5" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold text-foreground">{expertCase.serviceType}</p>
              <p className="text-xs text-muted-foreground">
                {expertCase.caseReference} &middot; Due {formatDateShort(expertCase.dueDate)}
              </p>
            </div>
            <div className="flex shrink-0 items-center gap-3">
              <SigningStatusBadge expertCase={expertCase} />
              <ArrowRight className="h-4 w-4 text-muted-foreground" />
            </div>
          </CardContent>
        </Card>
      </Link>
    </motion.div>
  )
}
