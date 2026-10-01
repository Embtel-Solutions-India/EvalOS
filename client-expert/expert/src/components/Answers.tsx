import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@shared/components/ui/card'
import { Textarea } from '@shared/components/ui/textarea'
import { statusOf } from '@shared/services/apiClient'
import { acceptFailureMessage, expertFailureMessage, feeLine } from '@/lib/expertCase'
import { accept, decline, requestEvidence } from '@/services/expertPortalService'

/**
 * Accept · Ask for more evidence · Decline. Each one says plainly what it does to the case.
 *
 * The fee comes first (Unit 65): accepting is agreeing to it, so it is shown above the buttons and
 * Accept is disabled until one is set. The PM's note (D69) sits with it: it is part of the offer.
 */
export function Answers({ caseId, fee, currency, note, onChanged }: {
  caseId: string
  fee: number | null
  currency: string | null
  note: string | null
  onChanged: () => void
}) {
  const [missing, setMissing] = useState('')
  const [reason, setReason] = useState('')

  const act = (call: () => Promise<unknown>, done: string, failure = expertFailureMessage) => async () => {
    try {
      await call()
      toast.success(done)
      onChanged()
    }
    catch (actionError: unknown) {
      const status = statusOf(actionError)
      toast.error(failure(status))
      // A refused accept is most often a changed fee: reload so the new amount is on screen.
      if (status === 409 && failure === acceptFailureMessage) onChanged()
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm">Your answer</CardTitle>
        <p className="text-base font-semibold text-foreground tabular-nums">{feeLine(fee, currency)}</p>
        {note && (
          <div className="mt-2 rounded-md border bg-muted/40 p-3">
            <p className="text-xs font-medium text-muted-foreground">Note from the team</p>
            <p className="mt-1 whitespace-pre-wrap text-sm text-foreground">{note}</p>
          </div>
        )}
      </CardHeader>
      <CardContent className="grid gap-6 lg:grid-cols-3">
        <div className="flex flex-col">
          <Button
            className="bg-success text-success-foreground hover:bg-success/90"
            disabled={fee === null}
            title={fee === null ? 'The fee for this case is not set yet' : undefined}
            onClick={() => void act(() => accept(caseId, fee), 'Thank you — the case manager has been told.', acceptFailureMessage)()}
          >
            I will sign this
          </Button>
          <p className="mt-2 text-xs text-muted-foreground">
            {fee === null
              ? 'You can accept once the team has set the fee for this case.'
              : 'Accepts the case at this fee and tells the case manager. You can still upload the signed letter later.'}
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
