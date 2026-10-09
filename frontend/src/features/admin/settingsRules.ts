import type { SettingChanges, SettingSource, SettingView } from "./settingsApi";

/**
 * What the Settings screen sends, worked out apart from the drawing so it can be tested.
 *
 * A draft entry is `undefined` (untouched), `null` (reset to the environment) or a string (the new value).
 */
export type Draft = Record<string, string | null | undefined>;

/** Only what actually changes: an untouched field, or one typed back to its saved value, sends nothing. */
export function buildChanges(views: SettingView[], draft: Draft): SettingChanges {
  const changes: SettingChanges = {};
  for (const view of views) {
    const next = draft[view.key];
    if (next === undefined) continue;
    if (next === null) {
      // Resetting a value the environment already owns is not a change.
      if (view.source === "APP") changes[view.key] = null;
      continue;
    }
    const value = view.kind === "BOOL" ? next : next.trim();
    // An empty secret box means "keep the one you have", never "save an empty password".
    if (view.secret && value === "") continue;
    if (!view.secret && view.source === "APP" && value === view.value) continue;
    if (!view.secret && value === "") continue;
    changes[view.key] = value;
  }
  return changes;
}

/** What the screen says about where a value comes from. */
export function sourceLabel(source: SettingSource): string {
  return source === "APP" ? "Set in app" : source === "ENVIRONMENT" ? "From environment" : "Not set";
}

/** The input's shown value: the draft while editing, else the app's own value (an env value is the placeholder). */
export function shownValue(view: SettingView, draft: Draft): string {
  const next = draft[view.key];
  if (typeof next === "string") return next;
  if (next === null || view.secret) return "";
  return view.source === "APP" ? (view.value ?? "") : "";
}

/** A switch is on unless the Administrator turned it off. */
export function switchOn(view: SettingView, draft: Draft): boolean {
  const next = draft[view.key];
  if (next === null) return true;
  return (typeof next === "string" ? next : (view.value ?? "true")) === "true";
}

/** `MAIL_PORT` → `Mail port`, for the few places a key is named. */
export function settingLabel(key: string): string {
  return LABELS[key] ?? key.charAt(0) + key.slice(1).toLowerCase().replaceAll("_", " ");
}

const LABELS: Record<string, string> = {
  MAIL_HOST: "SMTP host",
  MAIL_PORT: "Port",
  MAIL_USERNAME: "Username",
  MAIL_PASSWORD: "Password",
  MAIL_FROM: "From address",
  MAIL_ENABLED: "Outbound email",
  GHL_TOKEN: "Private Integration Token",
  GHL_LOCATION_ID: "Location ID",
  GHL_CORRELATION_FIELD: "Opportunity correlation field ID",
  GHL_WRITES_ENABLED: "Writes to GHL",
  SALES_BRAND: "Selling brand",
  CASES_PER_CM: "Cases per Case Manager",
  ONBOARDING_TARGET: "Experts onboarded per month (target)",
  WON_LOOKBACK_DAYS: "Won-deal lookback (days)",
};
