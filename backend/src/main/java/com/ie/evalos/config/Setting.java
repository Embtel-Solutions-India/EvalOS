package com.ie.evalos.config;

/**
 * Every setting the Administrator may change in the app (D83, spec 85) — a closed list, so a setting is added in
 * code review, never by a request. Each names the property it overrides; that property's own environment variable
 * still applies whenever nothing has been saved here.
 *
 * <p>What is deliberately <strong>not</strong> here, and why, is in spec 85: the signing and encryption keys and
 * the database credentials are needed before this table can be read, and the stub write mode is a bean chosen at
 * boot.
 */
public enum Setting {

	MAIL_HOST(Group.MAIL, Kind.HOST, "spring.mail.host", false),
	MAIL_PORT(Group.MAIL, Kind.PORT, "spring.mail.port", false),
	MAIL_USERNAME(Group.MAIL, Kind.TEXT, "spring.mail.username", false),
	MAIL_PASSWORD(Group.MAIL, Kind.TEXT, "spring.mail.password", true),
	MAIL_FROM(Group.MAIL, Kind.EMAIL, "evalos.mail.from", false),
	/** Off pauses every client and expert email without removing the credentials (the MAIL_UNAVAILABLE path). */
	MAIL_ENABLED(Group.MAIL, Kind.BOOL, null, false),

	GHL_TOKEN(Group.GHL, Kind.TEXT, "evalos.ghl.token", true),
	GHL_LOCATION_ID(Group.GHL, Kind.TEXT, "evalos.ghl.location-id", false),
	GHL_CORRELATION_FIELD(Group.GHL, Kind.TEXT, "evalos.ghl.opportunity-correlation-field", false),
	/** Off pauses every write to GHL; the outbox keeps its rows and drains when writes are back on. */
	GHL_WRITES_ENABLED(Group.GHL, Kind.BOOL, null, false),

	SALES_BRAND(Group.BRAND, Kind.BRAND, "evalos.ghl.sales-brand", false),

	CASES_PER_CM(Group.TARGETS, Kind.COUNT, "evalos.workload.cases-per-cm", false),
	ONBOARDING_TARGET(Group.TARGETS, Kind.COUNT, "evalos.roster.monthly-onboarding-target", false),
	WON_LOOKBACK_DAYS(Group.TARGETS, Kind.COUNT, "evalos.sales.won-lookback-days", false);

	public enum Group { MAIL, GHL, BRAND, TARGETS }

	/** What a value must look like; checked in {@code SettingsAdminService} before anything is saved. */
	public enum Kind { TEXT, HOST, PORT, EMAIL, BOOL, BRAND, COUNT }

	private final Group group;
	private final Kind kind;
	private final String property;
	private final boolean secret;

	Setting(Group group, Kind kind, String property, boolean secret) {
		this.group = group;
		this.kind = kind;
		this.property = property;
		this.secret = secret;
	}

	public Group group() {
		return group;
	}

	public Kind kind() {
		return kind;
	}

	/** The property whose environment variable applies when nothing is saved; null for a switch, which defaults on. */
	public String property() {
		return property;
	}

	/** Stored encrypted and never returned by any route, not even masked. */
	public boolean secret() {
		return secret;
	}
}
