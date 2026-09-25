package com.ie.evalos.chat;

import java.util.List;

/** Who joined and who left in one sync — the fan-out tells each of them. */
public record MembersChanged(List<ExpectedMember> added, List<ExpectedMember> removed) {
}
