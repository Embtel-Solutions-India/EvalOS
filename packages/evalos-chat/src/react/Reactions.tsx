import { useState } from 'react'
import { reactedByMe } from '../core/reducer'
import { REACTIONS, type Message, type Reaction } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'

/** The six reactions: counts for any given, a picker when the caller may react. */
export function Reactions({ message, disabled }: { message: Message; disabled: boolean }) {
  const client = useChatClient()
  const me = useChat((s) => s.me)
  const [picking, setPicking] = useState(false)
  const given = (Object.keys(REACTIONS) as Reaction[]).filter((r) => (message.reactions[r]?.length ?? 0) > 0)

  return (
    <div className="ec-reactions">
      {given.map((r) => (
        <button
          key={r}
          type="button"
          className={reactedByMe(message, r, me) ? 'ec-reaction ec-reaction--mine' : 'ec-reaction'}
          disabled={disabled}
          title={message.reactions[r]!.map((p) => p.name).join(', ')}
          onClick={() => void client.toggleReaction(message, r)}
        >
          {REACTIONS[r]} {message.reactions[r]!.length}
        </button>
      ))}
      {!disabled && (
        <button type="button" className="ec-reaction ec-reaction--add" aria-label="Add a reaction" onClick={() => setPicking(!picking)}>
          +
        </button>
      )}
      {picking &&
        (Object.keys(REACTIONS) as Reaction[]).map((r) => (
          <button
            key={`pick-${r}`}
            type="button"
            className="ec-reaction"
            onClick={() => {
              setPicking(false)
              void client.toggleReaction(message, r)
            }}
          >
            {REACTIONS[r]}
          </button>
        ))}
    </div>
  )
}
