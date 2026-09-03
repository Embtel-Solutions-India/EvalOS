import { ArrowRight, Check } from 'lucide-react'
import { AnimatePresence, motion } from 'framer-motion'
import type { ServiceDefinition } from '@/types/intake'
import { cn } from '@shared/utils/cn'

interface ServiceCardProps {
  service: ServiceDefinition
  selected: boolean
  onSelect: (serviceId: string) => void
}

export function ServiceCard({ service, selected, onSelect }: ServiceCardProps) {
  const Icon = service.icon
  return (
    <motion.button
      type="button"
      onClick={() => onSelect(service.id)}
      aria-pressed={selected}
      whileHover={{ y: -4 }}
      whileTap={{ scale: 0.98 }}
      transition={{ type: 'spring', stiffness: 400, damping: 25 }}
      className={cn(
        'group relative flex flex-col items-start gap-3 rounded-2xl border bg-card p-5 text-left shadow-sm transition-colors duration-200 hover:border-primary/40 hover:shadow-lg',
        selected ? 'border-primary bg-primary/5 shadow-lg' : 'border-border',
      )}
    >
      <AnimatePresence>
        {selected && (
          <motion.span
            initial={{ scale: 0, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            exit={{ scale: 0, opacity: 0 }}
            transition={{ type: 'spring', stiffness: 500, damping: 20 }}
            className="absolute right-4 top-4 flex h-6 w-6 items-center justify-center rounded-full bg-primary text-primary-foreground"
          >
            <Check className="h-3.5 w-3.5" />
          </motion.span>
        )}
      </AnimatePresence>
      <span
        className={cn(
          'flex h-11 w-11 items-center justify-center rounded-xl transition-colors duration-200',
          selected ? 'bg-primary text-primary-foreground' : 'bg-primary/10 text-primary group-hover:bg-primary/15',
        )}
      >
        <Icon className="h-5 w-5" />
      </span>
      <div className="pr-6">
        <h3 className="text-sm font-semibold text-foreground">{service.name}</h3>
        <p className="mt-1 text-sm text-muted-foreground">{service.shortDescription}</p>
        {service.bestFor && <p className="mt-2 text-xs font-medium text-primary">{service.bestFor}</p>}
      </div>
      <span className="mt-1 flex items-center gap-1 text-xs font-medium text-muted-foreground transition-all duration-200 group-hover:gap-1.5 group-hover:text-primary">
        Select
        <ArrowRight className="h-3.5 w-3.5" />
      </span>
    </motion.button>
  )
}
