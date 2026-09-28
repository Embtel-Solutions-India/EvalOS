import { useEffect, useState } from 'react'
import { keyOf, type Presence } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'

/** The label beside a name (`ChatRole`, Unit 57 §1). */
export const ROLE_LABEL: Record<string, string> = {
  CLIENT: 'Client',
  SALES: 'Sales',
  PM: 'Project manager',
  COORDINATOR: 'Coordinator',
  CASE_MANAGER: 'Case manager',
  ENM: 'Expert network',
  EXPERT: 'Expert',
}

/** How often presence is re-read while a conversation is open. Presence has no live event. */
const PRESENCE_EVERY_MS = 30_000

/**
 * Who is in the conversation, with role labels and an online dot. Presence is read per
 * conversation (`GET presence`), so it can only ever describe this conversation's members.
 */
export function Participants({ conversationId }: { conversationId: string }) {
  const client = useChatClient()
  const participants = useChat((s) => s.conversations[conversationId]?.participants)
  const [online, setOnline] = useState<Presence>({})

  useEffect(() => {
    let live = true
    const read = () =>
      client.api.presence(conversationId).then(
        (p) => live && setOnline(p),
        () => {}, // No presence (no Ably key): names still show, just without dots.
      )
    void read()
    const timer = setInterval(read, PRESENCE_EVERY_MS)
    return () => {
      live = false
      clearInterval(timer)
    }
  }, [client, conversationId])

  if (!participants?.length) return null
  return (
    <ul className="ec-participants" aria-label="Participants">
      {participants.map((p) => {
        const on = online[keyOf(p)] === true
        return (
          <li key={keyOf(p)} className="ec-participant">
            <span className={on ? 'ec-dot ec-dot--on' : 'ec-dot'} aria-label={on ? 'online' : 'offline'} />
            {p.name} <span className="ec-muted">· {ROLE_LABEL[p.role] ?? p.role}</span>
          </li>
        )
      })}
    </ul>
  )
}
