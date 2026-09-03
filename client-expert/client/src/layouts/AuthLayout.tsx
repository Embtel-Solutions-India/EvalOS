import { CheckCircle2 } from 'lucide-react'
import { motion } from 'framer-motion'
import { Link } from 'react-router-dom'
import heroImage from '@/assets/hero.png'
import { Logo } from '@shared/components/common/Logo'
import { LiquidBackground } from '@shared/components/common/LiquidBackground'
import { PageTransition } from '@shared/components/common/PageTransition'

const HIGHLIGHTS = [
  'Track every application from submission to final report',
  'Upload and manage your academic documents securely',
  'Message our evaluation team directly from your portal',
]

const panelContainer = {
  hidden: {},
  show: { transition: { staggerChildren: 0.12, delayChildren: 0.1 } },
}

const panelItem = {
  hidden: { opacity: 0, y: 16 },
  show: { opacity: 1, y: 0, transition: { duration: 0.5, ease: [0.16, 1, 0.3, 1] as const } },
}

export function AuthLayout() {
  return (
    <div className="grid min-h-dvh lg:grid-cols-2">
      <motion.div
        variants={panelContainer}
        initial="hidden"
        animate="show"
        className="relative hidden flex-col justify-between overflow-hidden bg-primary p-10 text-primary-foreground lg:flex"
      >
        <LiquidBackground variant="dark" />
        <motion.div variants={panelItem} className="relative">
          <Logo variant="light" showTagline />
        </motion.div>
        <motion.img
          variants={panelItem}
          src={heroImage}
          alt=""
          aria-hidden="true"
          className="relative mx-auto w-40 opacity-90 drop-shadow-2xl xl:w-48"
          animate={{ y: [0, -10, 0] }}
          transition={{ duration: 5, repeat: Infinity, ease: 'easeInOut' }}
        />
        <motion.div variants={panelItem} className="glass-panel-dark relative max-w-md rounded-2xl p-6 shadow-xl">
          <h2 className="font-serif text-2xl leading-snug text-primary-foreground">
            Manage your credential evaluation from one secure portal.
          </h2>
          <ul className="mt-6 space-y-4">
            {HIGHLIGHTS.map((highlight) => (
              <li key={highlight} className="flex items-start gap-3 text-sm text-primary-foreground/90">
                <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-primary-foreground" />
                {highlight}
              </li>
            ))}
          </ul>
        </motion.div>
        <motion.p variants={panelItem} className="relative text-xs text-primary-foreground/60">
          &copy; {new Date().getFullYear()} International Evaluations. All rights reserved.
        </motion.p>
      </motion.div>

      <div className="relative flex flex-col justify-center overflow-hidden px-6 py-10 sm:px-10 lg:px-16">
        <LiquidBackground className="opacity-40" />
        <motion.div
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4 }}
          className="relative mb-8 lg:hidden"
        >
          <Link to="/">
            <Logo />
          </Link>
        </motion.div>
        <div className="relative mx-auto w-full max-w-md">
          <PageTransition />
        </div>
      </div>
    </div>
  )
}
