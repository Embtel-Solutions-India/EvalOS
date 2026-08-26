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
 * provider can be added without re-keying the table.
 *
 * <p><strong>No cleanup migration is needed for rows this enum no longer names, and here is why —
 * it is not an oversight.</strong> {@code webhook_event.source} is unconstrained {@code text} with
 * no {@code CHECK}, so a dev database that recorded a {@code DROPBOX_SIGN} row before this cut
 * still holds it. Nothing ever reads it back: the only query on this column is
 * {@code findBySourceAndBrandIdAndExternalId}, which is always called with {@link #GHL}, so such a
 * row is excluded by the {@code WHERE} clause rather than loaded and failed on. Those rows are
 * inert history, and {@code webhook_event} is an archive — deleting from it to tidy a name would
 * be the more invasive choice. Add a {@code CHECK} only alongside a migration that reconciles
 * whatever is already there.
 */
public enum WebhookSource {

	GHL
}
