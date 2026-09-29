import { reactedByMe } from '../core/reducer'
import { REACTIONS, type Message, type Reaction } from '../core/types'
import { useChat, useChatClient } from './ChatProvider'

/**
 * The six reactions: a chip per reaction given (click to toggle yours), and the picker while
 * `picking` — opened from the message's "⋮".
 */
export function Reactions({ message, disabled, picking = false, onPicked }: { message: Message; disabled: boolean; picking?: boolean; onPicked?: () => void }) {
  const client = useChatClient()
  const me = useChat((s) => s.me)
  const given = (Object.keys(REACTIONS) as Reaction[]).filter((r) => (message.reactions[r]?.length ?? 0) > 0)
  if (given.length === 0 && !(picking && !disabled)) return null

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
      {picking && !disabled && (
        <span className="ec-picker" role="group" aria-label="Pick a reaction">
          {(Object.keys(REACTIONS) as Reaction[]).map((r) => (
            <button
              key={`pick-${r}`}
              type="button"
              className="ec-reaction"
              onClick={() => {
                onPicked?.()
                void client.toggleReaction(message, r)
              }}
            >
              {REACTIONS[r]}
            </button>
          ))}
          <button type="button" className="ec-link" onClick={onPicked}>
            Close
          </button>
        </span>
      )}
    </div>
  )
}
