import { AnimatePresence, motion } from 'framer-motion'

// Two: choose a service, then review and send — the documents attach on review. About You went
// to sign-up, and the questionnaire step went with Unit 55. See NewRequest.
const STAGES = ['Service', 'Review'] as const

interface IntakeProgressProps {
  currentIndex: number
}

export function IntakeProgress({ currentIndex }: IntakeProgressProps) {
  return (
    <div>
      <p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
        Step {currentIndex + 1} of {STAGES.length}
      </p>
      <div className="flex items-center gap-2">
        {STAGES.map((stage, index) => (
          <span key={stage} className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
            <motion.span
              className="block h-full rounded-full bg-primary"
              initial={false}
              animate={{ width: index <= currentIndex ? '100%' : '0%' }}
              transition={{ duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
            />
          </span>
        ))}
      </div>
      <div className="mt-2 h-5 overflow-hidden">
        <AnimatePresence mode="wait">
          <motion.p
            key={STAGES[currentIndex]}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
            transition={{ duration: 0.2 }}
            className="text-sm font-medium text-foreground"
          >
            {STAGES[currentIndex]}
          </motion.p>
        </AnimatePresence>
      </div>
    </div>
  )
}
