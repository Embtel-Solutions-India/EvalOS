import { ArrowLeft, ArrowRight } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@shared/components/ui/card'
import { IntakeDocumentCard } from '@/components/intake/IntakeDocumentCard'
import { IntakeProgress } from '@/components/intake/IntakeProgress'
import { getDraft, saveDocuments, buildInitialDocuments } from '@/services/intakeService'
import type { IntakeDocument } from '@/types/intake'

export default function IntakeDocuments() {
  const navigate = useNavigate()
  const [documents, setDocuments] = useState<IntakeDocument[]>(buildInitialDocuments)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!getDraft().serviceId) navigate('/start/service', { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function updateDocument(updated: IntakeDocument) {
    setDocuments((prev) => prev.map((document) => (document.id === updated.id ? updated : document)))
    setError(null)
  }

  async function handleContinue() {
    const missingRequired = documents.some((document) => document.required && document.status === 'required')
    if (missingRequired) {
      setError('Please upload all required documents before continuing.')
      return
    }
    setIsSubmitting(true)
    try {
      await saveDocuments(documents)
      toast.success('Your progress has been saved.')
      navigate('/start/review')
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div>
      <IntakeProgress currentIndex={2} />

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>Let's collect your documents.</CardTitle>
          <p className="text-sm text-muted-foreground">
            Upload the documents relevant to your request. We'll let you know if anything else is needed.
          </p>
        </CardHeader>
        <CardContent className="space-y-4">
          {documents.map((document) => (
            <IntakeDocumentCard key={document.id} document={document} onChange={updateDocument} />
          ))}
          {error && (
            <p className="text-xs font-medium text-destructive" role="alert">
              {error}
            </p>
          )}
        </CardContent>
      </Card>

      <div className="mt-8 flex flex-col-reverse gap-3 border-t pt-6 sm:flex-row sm:items-center sm:justify-between">
        <Button type="button" variant="outline" onClick={() => navigate('/start/questions')} disabled={isSubmitting}>
          <ArrowLeft className="h-4 w-4" />
          Back
        </Button>
        <Button size="lg" onClick={() => void handleContinue()} loading={isSubmitting} className="sm:min-w-48">
          Continue
          <ArrowRight className="h-4 w-4" />
        </Button>
      </div>
    </div>
  )
}
