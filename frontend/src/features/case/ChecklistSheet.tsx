import type { ReactElement } from 'react'
import { SheetContent, SheetRoot, SheetTrigger } from '../../components/ui/dialog'
import CaseChecklist from '../checklist/CaseChecklist'
import type { ChecklistView } from '../checklist/checklistRules'

/** The Doc checklists screen's own component, opened from the case (Unit 66). Nothing re-implemented. */
export default function ChecklistSheet({
  caseId,
  trigger,
  onChecklistChanged,
  onCaseLeftTheStage,
}: {
  caseId: string
  trigger: ReactElement
  onChecklistChanged: (view: ChecklistView) => void
  onCaseLeftTheStage: () => void
}) {
  return (
    <SheetRoot>
      <SheetTrigger asChild>{trigger}</SheetTrigger>
      <SheetContent title="Checklist" description="Add items, set their status, send new items and chase the client.">
        <CaseChecklist caseId={caseId} onChecklistChanged={onChecklistChanged} onCaseLeftTheStage={onCaseLeftTheStage} />
      </SheetContent>
    </SheetRoot>
  )
}
