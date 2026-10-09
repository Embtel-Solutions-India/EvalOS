import { describe, expect, it } from "vitest";
import { buildChanges, shownValue, sourceLabel, switchOn } from "./settingsRules";
import type { SettingView } from "./settingsApi";

const view = (over: Partial<SettingView>): SettingView => ({
  key: "MAIL_HOST",
  group: "MAIL",
  kind: "HOST",
  secret: false,
  value: null,
  set: false,
  source: "UNSET",
  environmentValue: null,
  ...over,
});

describe("buildChanges", () => {
  it("sends nothing for untouched fields", () => {
    expect(buildChanges([view({})], {})).toEqual({});
  });

  it("sends a new value, trimmed", () => {
    expect(buildChanges([view({})], { MAIL_HOST: "  smtp.example.com " })).toEqual({ MAIL_HOST: "smtp.example.com" });
  });

  it("sends nothing when a value is typed back to what is already saved", () => {
    const saved = view({ source: "APP", value: "smtp.example.com", set: true });
    expect(buildChanges([saved], { MAIL_HOST: "smtp.example.com" })).toEqual({});
  });

  it("resets an app value to the environment with null, but not one the environment already owns", () => {
    expect(buildChanges([view({ source: "APP", value: "x", set: true })], { MAIL_HOST: null })).toEqual({ MAIL_HOST: null });
    expect(buildChanges([view({ source: "ENVIRONMENT", value: "x", set: true })], { MAIL_HOST: null })).toEqual({});
  });

  it("never sends an empty secret, so a blank box keeps the saved password", () => {
    const password = view({ key: "MAIL_PASSWORD", kind: "TEXT", secret: true, source: "APP", set: true });
    expect(buildChanges([password], { MAIL_PASSWORD: "" })).toEqual({});
    expect(buildChanges([password], { MAIL_PASSWORD: "n3w" })).toEqual({ MAIL_PASSWORD: "n3w" });
    expect(buildChanges([password], { MAIL_PASSWORD: null })).toEqual({ MAIL_PASSWORD: null });
  });

  it("sends a switch as true or false", () => {
    const toggle = view({ key: "MAIL_ENABLED", kind: "BOOL", value: "true", set: true });
    expect(buildChanges([toggle], { MAIL_ENABLED: "false" })).toEqual({ MAIL_ENABLED: "false" });
  });
});

describe("display", () => {
  it("names where a value comes from", () => {
    expect(sourceLabel("APP")).toBe("Set in app");
    expect(sourceLabel("ENVIRONMENT")).toBe("From environment");
    expect(sourceLabel("UNSET")).toBe("Not set");
  });

  it("shows the app's own value, leaves an environment value as the placeholder, and never shows a secret", () => {
    expect(shownValue(view({ source: "APP", value: "a" }), {})).toBe("a");
    expect(shownValue(view({ source: "ENVIRONMENT", value: "e" }), {})).toBe("");
    expect(shownValue(view({ secret: true, source: "APP", set: true }), {})).toBe("");
    expect(shownValue(view({ source: "APP", value: "a" }), { MAIL_HOST: "typed" })).toBe("typed");
  });

  it("reads a switch as on unless it was turned off", () => {
    expect(switchOn(view({ kind: "BOOL", value: "true" }), {})).toBe(true);
    expect(switchOn(view({ kind: "BOOL", value: "false" }), {})).toBe(false);
    expect(switchOn(view({ kind: "BOOL", value: "false" }), { MAIL_HOST: null })).toBe(true);
  });
});
