package com.ie.evalos.chat;

/** A write to a conversation of a closed case. Answered 409 CONVERSATION_READ_ONLY. */
public class ConversationReadOnlyException extends RuntimeException {

	public ConversationReadOnlyException() {
		super("This case is closed, so its conversation is read-only.");
	}
}
