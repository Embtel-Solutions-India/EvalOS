import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@shared/components/ui/card'
import { Textarea } from '@shared/components/ui/textarea'
import { statusOf } from '@shared/services/apiClient'
import { expertFailureMessage } from '@/lib/expertCase'
import { accept, decline, requestEvidence } from '@/services/expertPortalService'

/** Accept · Ask for more evidence · Decline. Each one says plainly what it does to the case. */
export function Answers({ caseId, onChanged }: { caseId: string; onChanged: () => void }) {
  const [missing, setMissing] = useState('')
  const [reason, setReason] = useState('')

  const act = (call: () => Promise<unknown>, done: string) => async () => {
    try {
      await call()
      toast.success(done)
      onChanged()
    }
    catch (actionError: unknown) {
      toast.error(expertFailureMessage(statusOf(actionError)))
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm">Your answer</CardTitle>
      </CardHeader>
      <CardContent className="grid gap-6 lg:grid-cols-3">
        <div className="flex flex-col">
          <Button className="bg-success text-success-foreground hover:bg-success/90" onClick={() => void act(() => accept(caseId), 'Thank you — the case manager has been told.')()}>
            I will sign this
          </Button>
          <p className="mt-2 text-xs text-muted-foreground">
            Tells the case manager you have taken it. You can still upload the signed letter later.
          </p>
        </div>

        <div className="flex flex-col lg:border-l lg:pl-6">
          <p className="mb-2 text-sm font-medium text-foreground">Ask for more evidence</p>
          <Textarea
            value={missing}
            onChange={(event) => setMissing(event.target.value)}
            placeholder="What do you need before you can sign? Be specific — the client is asked for exactly this."
            rows={3}
          />
          <Button
            variant="outline"
            className="mt-2"
            disabled={missing.trim().length === 0}
            onClick={() => void act(() => requestEvidence(caseId, missing.trim()), 'We will ask the client for it.')()}
          >
            Ask for it
          </Button>
          <p className="mt-2 text-xs text-muted-foreground">
            Puts the case on hold with the client. You will not be able to sign until it comes back.
          </p>
        </div>

        <div className="flex flex-col lg:border-l lg:pl-6">
          <p className="mb-2 text-sm font-medium text-foreground">Decline this case</p>
          <Textarea
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder="Why can you not take it? This goes to the case manager, who will find another expert."
            rows={3}
          />
          <Button
            variant="outline"
            className="mt-2 border-destructive/40 text-destructive hover:bg-destructive/5"
            disabled={reason.trim().length === 0}
            onClick={() => void act(() => decline(caseId, reason.trim()), 'Understood — the case goes back for rematching.')()}
          >
            Decline
          </Button>
          <p className="mt-2 text-xs text-muted-foreground">
            This sends the case back to be matched with another expert. It cannot be undone from here.
          </p>
        </div>
      </CardContent>
    </Card>
  )
}
