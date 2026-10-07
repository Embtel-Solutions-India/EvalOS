import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ChatScreen } from '@evalos/chat'

/**
 * Every case conversation with the case team (Unit 57), on one screen. Same shape as the
 * client's. The height leaves room for the layout's padding (and its phone header below lg).
 */
export default function Messages() {
  const [open, setOpen] = useState<string | null>(null)
  const navigate = useNavigate()

  return (
    <div className="mx-auto h-[calc(100dvh-11rem)] max-w-7xl lg:h-[calc(100dvh-4rem)]">
      <ChatScreen
        title="Messages"
        description="Coordinate with your team on every case."
        selectedId={open ?? undefined}
        onOpen={setOpen}
        onBack={() => setOpen(null)}
        onUploadDocument={(c) => navigate(`/case?caseId=${c.caseId}`)}
      />
    </div>
  )
}
