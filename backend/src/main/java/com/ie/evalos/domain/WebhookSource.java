package com.ie.evalos.domain;

/** Where an inbound webhook came from. One endpoint and one secret per source. */
public enum WebhookSource {

	GHL,
	DROPBOX_SIGN
}
