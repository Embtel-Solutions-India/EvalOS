/** Wire types, mirroring `com.ie.evalos.chat.ChatViews` (Unit 57 §4). */

export type ParticipantKind = 'STAFF' | 'CLIENT' | 'EXPERT'
export type ConversationType = 'CLIENT' | 'INTERNAL' | 'EXPERT'
export type Reaction = 'THUMBS_UP' | 'HEART' | 'LAUGH' | 'CELEBRATE' | 'SURPRISED' | 'THANKS'

/** The six, in display order. */
export const REACTIONS: Record<Reaction, string> = {
  THUMBS_UP: '👍',
  HEART: '❤️',
  LAUGH: '😂',
  CELEBRATE: '🎉',
  SURPRISED: '😮',
  THANKS: '🙏',
}

/** `MessageService.MAX_BODY`. */
export const MAX_BODY = 4000

export type Me = { kind: ParticipantKind; id: string }
export type Reactor = { kind: ParticipantKind; id: string; name: string }
export type Participant = { kind: ParticipantKind; id: string; role: string; name: string }

export type Message = {
  id: string
  conversationId: string
  authorKind: ParticipantKind
  authorId: string
  authorName: string | null
  /** 'GM' for a General Manager's message, else null (members are labelled by the roster). */
  authorRole: string | null
  body: string
  parentId: string | null
  replyCount: number
  createdAt: string
  editedAt: string | null
  deleted: boolean
  reactions: Partial<Record<Reaction, Reactor[]>>
  /** Trusted only on a REST reply; a live event carries its author's value. See `reducer.own`. */
  mine: boolean
}

export type Conversation = {
  id: string
  caseId: string
  caseCode: string
  /** The applicant, the conversation's title; null when the case holds no name yet. */
  clientName: string | null
  serviceType: string | null
  stage: string
  type: ConversationType
  status: 'ACTIVE' | 'READ_ONLY'
  access: 'MEMBER' | 'VIEWER' | 'PARTICIPANT'
  unread: number
  lastMessage: Message | null
  participants: Participant[]
  lastMessageAt: string | null
}

export type Page<T> = { items: T[]; nextCursor: string | null }

/** `ChatViews.ReaderMark`: how far one participant has read. Also the `read.moved` event's data. */
export type ReaderMark = { kind: ParticipantKind; id: string; name: string | null; lastReadMessageId: string }

/** `GET conversations/{id}/presence`: `"KIND:uuid"` → online, for the current participants only. */
export type Presence = Record<string, boolean>

/** `"KIND:uuid"`, the key presence and typing use. */
export function keyOf(who: { kind: ParticipantKind; id: string }): string {
  return `${who.kind}:${who.id}`
}

/** The Ably message data (Unit 57 §5). */
export type Envelope = { type: string; conversationId: string; data: unknown }

/** An Ably TokenRequest, as `GET realtime/token` answers (`AblyToken`). */
export type TokenRequest = {
  keyName: string
  clientId: string
  capability: string
  ttl: number
  timestamp: number
  nonce: string
  mac: string
}
