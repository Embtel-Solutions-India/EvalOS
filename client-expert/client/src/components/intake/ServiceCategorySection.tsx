import { motion } from 'framer-motion'
import type { ServiceCategory, ServiceDefinition } from '@/types/intake'
import { ServiceCard } from '@/components/intake/ServiceCard'

interface ServiceCategorySectionProps {
  category: ServiceCategory
  services: ServiceDefinition[]
  selectedServiceId?: string
  onSelect: (serviceId: string) => void
}

const container = {
  hidden: {},
  show: { transition: { staggerChildren: 0.06 } },
}

const item = {
  hidden: { opacity: 0, y: 14 },
  show: { opacity: 1, y: 0, transition: { duration: 0.35, ease: [0.16, 1, 0.3, 1] as const } },
}

export function ServiceCategorySection({ category, services, selectedServiceId, onSelect }: ServiceCategorySectionProps) {
  return (
    <section>
      <h2 className="text-base font-semibold text-foreground">{category.name}</h2>
      <p className="mt-1 text-sm text-muted-foreground">{category.description}</p>
      <motion.div
        variants={container}
        initial="hidden"
        animate="show"
        className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
      >
        {services.map((service) => (
          <motion.div key={service.id} variants={item}>
            <ServiceCard service={service} selected={selectedServiceId === service.id} onSelect={onSelect} />
          </motion.div>
        ))}
      </motion.div>
    </section>
  )
}
