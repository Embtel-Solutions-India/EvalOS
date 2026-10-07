import { keyOf, type Conversation, type Message, type Participant } from './types'

/** The badge beside an author's name. Members are labelled by the roster; only a GM is labelled here (D75). */
export function authorBadge(message: Message): string | null {
  return message.authorRole === 'GM' ? 'General Manager' : null
}

/** The line above a conversation that tells the reader what they may do in it, or null when nothing needs saying. */
export function accessBanner(conversation: Conversation): string | null {
  if (conversation.access === 'VIEWER') return 'Oversight — read only'
  if (conversation.status === 'READ_ONLY') return 'This case is closed. The conversation is kept as its history.'
  if (conversation.access === 'PARTICIPANT') {
    return 'You are taking part as General Manager. You are not listed as a member of this conversation.'
  }
  return null
}

/**
 * Who is typing, by name. A member is on the roster; a GM taking part is not, so their name comes from a
 * message they sent here. "Someone" only when neither knows them.
 */
export function typingNames(keys: string[], participants: Participant[] | undefined, messages: Message[]): string[] {
  return keys.map(
    (key) =>
      participants?.find((p) => keyOf(p) === key)?.name ??
      messages.find((m) => keyOf({ kind: m.authorKind, id: m.authorId }) === key && m.authorName)?.authorName ??
      'Someone',
  )
}
