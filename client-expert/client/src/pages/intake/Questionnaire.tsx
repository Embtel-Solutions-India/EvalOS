import { ArrowLeft, ArrowRight } from 'lucide-react'
import { AnimatePresence, motion } from 'framer-motion'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@shared/components/ui/card'
import { FillDemoDataButton } from '@/components/common/FillDemoDataButton'
import { IntakeProgress } from '@/components/intake/IntakeProgress'
import { QuestionField } from '@/components/intake/QuestionField'
import { DEMO_ANSWERS } from '@/constants/demoData'
import { getService } from '@/constants/serviceCatalog'
import { getVisibleGroups, getVisibleQuestions } from '@/lib/questionnaire'
import { getDraft, saveAnswers } from '@/services/intakeService'
import type { RequestAnswers } from '@/types/intake'

export default function Questionnaire() {
  const navigate = useNavigate()
  const draft = getDraft()
  const service = getService(draft.serviceId)
  // Seed `clientType` into the answers pool (not just the account) so
  // conditional groups like employerInfo — whose showIf checks
  // answers.clientType — can actually see it. draft.answers wins if a
  // question ever reuses this key directly.
  const [answers, setAnswers] = useState<RequestAnswers>(() => ({
    ...(draft.aboutYou?.clientType ? { clientType: draft.aboutYou.clientType } : {}),
    ...draft.answers,
  }))
  const [groupIndex, setGroupIndex] = useState(0)
  const [direction, setDirection] = useState(1)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [isSaving, setIsSaving] = useState(false)

  useEffect(() => {
    if (!draft.serviceId) navigate('/start/service', { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const groups = useMemo(() => getVisibleGroups(draft.serviceId, answers), [draft.serviceId, answers])
  const currentGroup = groups[Math.min(groupIndex, groups.length - 1)]
  const visibleQuestions = currentGroup ? getVisibleQuestions(currentGroup, answers) : []

  if (!service || !currentGroup) return null

  function updateAnswer(questionId: string, value: string) {
    setAnswers((prev) => ({ ...prev, [questionId]: value }))
    setErrors((prev) => {
      if (!prev[questionId]) return prev
      const next = { ...prev }
      delete next[questionId]
      return next
    })
  }

  function handleFillDemoData() {
    const demoValues = Object.fromEntries(
      visibleQuestions.map((question) => [question.id, DEMO_ANSWERS[question.id] ?? '']),
    )
    setAnswers((prev) => ({ ...prev, ...demoValues }))
    setErrors({})
  }

  async function handleContinue() {
    const nextErrors: Record<string, string> = {}
    for (const question of visibleQuestions) {
      if (question.required && !answers[question.id]?.trim()) {
        nextErrors[question.id] = 'This field is required.'
      }
    }
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors)
      return
    }

    setIsSaving(true)
    try {
      await saveAnswers(answers)
      if (groupIndex < groups.length - 1) {
        setDirection(1)
        setGroupIndex((index) => index + 1)
        window.scrollTo({ top: 0, behavior: 'smooth' })
      } else {
        toast.success('Your progress has been saved.')
        navigate('/start/documents')
      }
    } finally {
      setIsSaving(false)
    }
  }

  function handleBack() {
    if (groupIndex > 0) {
      setDirection(-1)
      setGroupIndex((index) => index - 1)
      window.scrollTo({ top: 0, behavior: 'smooth' })
    } else {
      navigate('/start/about-you')
    }
  }

  return (
    <div>
      <IntakeProgress currentIndex={1} />

      <div className="relative mt-6 overflow-hidden">
        <AnimatePresence mode="wait" custom={direction} initial={false}>
          <motion.div
            key={currentGroup.id}
            custom={direction}
            initial={{ opacity: 0, x: direction > 0 ? 24 : -24 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: direction > 0 ? -24 : 24 }}
            transition={{ duration: 0.25, ease: [0.16, 1, 0.3, 1] }}
          >
            <Card>
              <CardHeader className="flex flex-row items-center justify-between space-y-0">
                <div>
                  <CardTitle>{currentGroup.title}</CardTitle>
                  <p className="text-sm text-muted-foreground">{currentGroup.description}</p>
                </div>
                <FillDemoDataButton onClick={handleFillDemoData} />
              </CardHeader>
              <CardContent className="space-y-5">
                {visibleQuestions.map((question, index) => (
                  <motion.div
                    key={question.id}
                    initial={{ opacity: 0, y: 8 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ duration: 0.25, delay: index * 0.04 }}
                  >
                    <QuestionField
                      question={question}
                      value={answers[question.id] ?? ''}
                      onChange={(value) => updateAnswer(question.id, value)}
                      error={errors[question.id]}
                    />
                  </motion.div>
                ))}
              </CardContent>
            </Card>
          </motion.div>
        </AnimatePresence>
      </div>

      <div className="mt-8 flex flex-col-reverse gap-3 border-t pt-6 sm:flex-row sm:items-center sm:justify-between">
        <Button type="button" variant="outline" onClick={handleBack} disabled={isSaving}>
          <ArrowLeft className="h-4 w-4" />
          Back
        </Button>
        <Button size="lg" onClick={() => void handleContinue()} loading={isSaving} className="sm:min-w-48">
          Continue
          <ArrowRight className="h-4 w-4" />
        </Button>
      </div>
    </div>
  )
}
