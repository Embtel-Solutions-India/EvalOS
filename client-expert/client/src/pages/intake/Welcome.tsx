import { ArrowRight, FileCheck2, MessageCircleQuestion, ShieldCheck } from 'lucide-react'
import { useEffect } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'
import { LiquidBackground } from '@shared/components/common/LiquidBackground'

const REASSURANCES = [
  { icon: FileCheck2, text: 'Only the questions relevant to your request' },
  { icon: ShieldCheck, text: 'Your documents are handled securely' },
  { icon: MessageCircleQuestion, text: "We'll tell you clearly what happens next" },
]

export default function Welcome() {
  const navigate = useNavigate()

  useEffect(() => {
  }, [])

  return (
    <div className="relative -mx-4 -mt-8 overflow-hidden px-4 pb-4 pt-12 sm:-mx-6 sm:-mt-10 sm:px-6 sm:pt-20">
      <LiquidBackground className="opacity-70" />
      <div className="relative mx-auto max-w-xl text-center">
        <span className="glass-panel inline-flex items-center rounded-full px-3 py-1 text-xs font-medium text-muted-foreground">
          International Evaluations Client Portal
        </span>
        <h1 className="mt-5 font-serif text-4xl leading-tight tracking-tight text-foreground sm:text-5xl">
          Let's get your evaluation started.
        </h1>
        <p className="mt-4 text-base text-muted-foreground sm:text-lg">
          Tell us what you need. We'll guide you through the information and documents required for your
          request.
        </p>

        <div className="mt-8 flex flex-col items-center gap-3 sm:flex-row sm:justify-center">
          <Button size="lg" onClick={() => navigate('/start/service')} className="w-full sm:w-auto">
            Get Started
            <ArrowRight className="h-4 w-4" />
          </Button>
          <Button asChild size="lg" variant="outline" className="w-full sm:w-auto">
            <Link to="/login">I already have an account</Link>
          </Button>
        </div>

        <ul className="mt-10 grid grid-cols-1 gap-3 text-left sm:grid-cols-3">
          {REASSURANCES.map((item) => (
            <li key={item.text} className="glass-panel flex items-start gap-2.5 rounded-xl p-3.5 text-sm text-foreground">
              <item.icon className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
              {item.text}
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}
