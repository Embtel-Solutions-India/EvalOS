import { useNavigate, useSearchParams } from 'react-router-dom'
import { ChatScreen } from '@evalos/chat'

/**
 * The client's case conversations, one row per case (Unit 58 §4, Unit 57), on one screen. Two
 * panes on desktop; on a phone, the inbox or one conversation with Back. The paperclip opens the
 * case, where documents are uploaded.
 */
export default function Conversations() {
  // The open conversation is `?c=`, so a push notification or the message toast can land on one.
  const [params, setParams] = useSearchParams()
  const open = params.get('c')
  const setOpen = (id: string | null) => setParams(id ? { c: id } : {})
  const navigate = useNavigate()

  return (
    <div className="mx-auto h-[calc(100dvh-8rem)] max-w-7xl lg:h-[calc(100dvh-7rem)]">
      <ChatScreen
        title="Conversations"
        description="Talk to the team working on each of your cases."
        selectedId={open ?? undefined}
        onOpen={setOpen}
        onBack={() => setOpen(null)}
        onUploadDocument={(c) => navigate(`/cases/${c.caseId}`)}
      />
    </div>
  )
}
