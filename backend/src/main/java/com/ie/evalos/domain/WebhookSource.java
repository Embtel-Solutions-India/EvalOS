package com.ie.evalos.domain;

/**
 * Where an inbound webhook came from. One endpoint and one secret per source.
 *
 * <p><strong>One value, and that is the current architecture rather than a stub.</strong>
 * {@code DROPBOX_SIGN} sat here until the e-signature provider was dropped — the expert now
 * signs in their own tool and uploads the PDF through their portal, so there is no second
 * inbound source and {@code architecture.md} states it as "one inbound source, GHL".
 *
 * <p>The enum stays (rather than the column becoming a constant) because {@code source} is part
 * of {@code webhook_event}'s dedup key: an event id is unique <em>per source</em>, so a second
 * provider can be added without re-keying the table. Note there is no DB {@code CHECK} on
 * {@code webhook_event.source} — a row written with a name this enum no longer has will fail on
 * read, which only matters for a dev database that recorded one before this cut.
 */
public enum WebhookSource {

	GHL
}
