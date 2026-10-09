import { api, unwrap } from "../../lib/api";

/**
 * The Administrator's Settings (D83, spec 85). A secret's value is never sent to the browser: `value` is null
 * for one, and `set` says only whether it has a value.
 */
export type SettingGroup = "MAIL" | "GHL" | "BRAND" | "TARGETS";
export type SettingKind = "TEXT" | "HOST" | "PORT" | "EMAIL" | "BOOL" | "BRAND" | "COUNT";
export type SettingSource = "APP" | "ENVIRONMENT" | "UNSET";

export type SettingView = {
  key: string;
  group: SettingGroup;
  kind: SettingKind;
  secret: boolean;
  /** The value in force (the app's, else the environment's); null for a secret. */
  value: string | null;
  set: boolean;
  source: SettingSource;
  /** What the environment says, shown so a reset is not a guess; null for a secret. */
  environmentValue: string | null;
};

export type TestResult = { ok: boolean; message: string };

/** A value sets, `null` resets to the environment; a key left out is untouched. */
export type SettingChanges = Record<string, string | null>;

export const fetchSettings = (signal?: AbortSignal) => unwrap<SettingView[]>(api.get("/settings", { signal }));
export const saveSettings = (changes: SettingChanges) => unwrap<SettingView[]>(api.put("/settings", { changes }));
export const testMail = () => unwrap<TestResult>(api.post("/settings/mail/test"));
export const testGhl = () => unwrap<TestResult>(api.post("/settings/ghl/test"));
