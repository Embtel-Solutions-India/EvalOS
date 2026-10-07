import type { Conversation, Message } from './types'

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
