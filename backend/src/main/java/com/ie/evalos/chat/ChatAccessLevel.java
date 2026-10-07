package com.ie.evalos.chat;

/** What a caller may do in a conversation (Unit 57 §3). */
public enum ChatAccessLevel {
	MEMBER, VIEWER, NONE,
	/** A GM who is not a member: may write, but holds no read position and no unread (D75). */
	PARTICIPANT
}
