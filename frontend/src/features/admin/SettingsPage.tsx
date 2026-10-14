import { useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Card } from "../../components/ui/card";
import { stateOf } from "../dashboards/useMetrics";
import { fetchBrands } from "./adminApi";
import {
  fetchSettings,
  saveSettings,
  testGhl,
  testMail,
  type SettingGroup,
  type SettingView,
  type TestResult,
} from "./settingsApi";
import { buildChanges, settingLabel, shownValue, sourceLabel, switchOn, type Draft } from "./settingsRules";

/**
 * The Administrator's Settings (D83, spec 85): the relay, the GHL credential, the selling brand and three
 * targets, changed here with no restart. A value saved here wins over the deployment's environment variable;
 * "Reset" hands it back. Secrets are write-only — the server never sends one back, so the box is always empty.
 */
export default function SettingsPage() {
  const settings = useQuery({ queryKey: ["admin", "settings"], queryFn: ({ signal }) => fetchSettings(signal) });
  const brands = useQuery({ queryKey: ["admin", "brands"], queryFn: ({ signal }) => fetchBrands(signal) });
  const views = settings.data ?? [];
  const state = stateOf(settings);
  const inGroup = (group: SettingGroup) => views.filter((v) => v.group === group);

  return (
    <section className="space-y-5">
      <header>
        <h1 className="text-xl font-semibold tracking-tight">Settings</h1>
        <p className="text-sm" style={MUTED}>
          Changes apply on the next email or GHL call — no restart. A value set here overrides the deployment&rsquo;s
          environment; reset it to go back. The signing and encryption keys, the database and storage are not
          changed here.
        </p>
      </header>

      <Section
        title="Email (SMTP)"
        description="The relay that sends client and expert email. The username is often not your account email — see your provider's SMTP page."
        views={inGroup("MAIL")}
        state={state}
        onSaved={() => void settings.refetch()}
        test={{ label: "Send test email", run: testMail, hint: "Sends one email to your own address." }}
        renderField={(view, draft, set) => <Field view={view} draft={draft} set={set} />}
      />
      <Section
        title="GoHighLevel"
        description="The location EvalOS reads and writes. The token is a Private Integration Token for that location, with the scopes EvalOS uses."
        views={inGroup("GHL")}
        state={state}
        onSaved={() => void settings.refetch()}
        test={{ label: "Test connection", run: testGhl, hint: "Reads the location's pipelines with the saved token." }}
        renderField={(view, draft, set) => <Field view={view} draft={draft} set={set} />}
      />
      <Section
        title="Selling brand"
        description="The brand that owns the GHL location: its sales desks, mirrors and outbox. Changing it attributes new mirror rows to the new brand; existing rows keep theirs."
        views={inGroup("BRAND")}
        state={state}
        onSaved={() => void settings.refetch()}
        renderField={(view, draft, set) => (
          <Field
            view={view}
            draft={draft}
            set={set}
            options={(brands.data ?? []).map((b) => ({ value: b.slug, label: `${b.name} (${b.slug})` }))}
          />
        )}
      />
      <Section
        title="Business targets"
        description="The denominators behind the dashboards: Case Manager capacity bands, the onboarding target, and how far back a won deal is looked for."
        views={inGroup("TARGETS")}
        state={state}
        onSaved={() => void settings.refetch()}
        renderField={(view, draft, set) => <Field view={view} draft={draft} set={set} />}
      />
    </section>
  );
}

const MUTED = { color: "var(--text-muted)" };
const INPUT = "mt-1 w-full rounded-md border px-2.5 py-1.5 text-sm";
const INPUT_STYLE = { borderColor: "var(--border-default)", background: "var(--bg-surface)" };

type SetDraft = (key: string, value: string | null | undefined) => void;

function Section({
  title,
  description,
  views,
  state,
  onSaved,
  test,
  renderField,
}: {
  title: string;
  description: string;
  views: SettingView[];
  state: ReturnType<typeof stateOf>;
  onSaved: () => void;
  test?: { label: string; run: () => Promise<TestResult>; hint: string };
  renderField: (view: SettingView, draft: Draft, set: SetDraft) => ReactNode;
}) {
  const [draft, setDraftState] = useState<Draft>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const changes = buildChanges(views, draft);
  const dirty = Object.keys(changes).length > 0;
  const set: SetDraft = (key, value) => setDraftState((d) => ({ ...d, [key]: value }));

  const attempt = async (work: () => Promise<{ ok: boolean; text: string }>) => {
    setBusy(true);
    setMessage(null);
    try {
      setMessage(await work());
    } catch (error: unknown) {
      setMessage({ ok: false, text: error instanceof Error ? error.message : "That did not work" });
    } finally {
      setBusy(false);
    }
  };

  const save = () =>
    attempt(async () => {
      await saveSettings(changes);
      setDraftState({});
      onSaved();
      return { ok: true, text: "Saved. It applies from the next email or GHL call." };
    });

  return (
    <Card title={title} state={state}>
      <p className="-mt-1 mb-4 text-sm" style={MUTED}>
        {description}
      </p>
      <div className="grid gap-x-6 gap-y-4 md:grid-cols-2">
        {views.map((view) => (
          <div key={view.key} className={view.kind === "BOOL" ? "md:col-span-2" : undefined}>
            {renderField(view, draft, set)}
          </div>
        ))}
      </div>
      {message && (
        <p
          className="mt-4 text-sm"
          role={message.ok ? "status" : "alert"}
          style={{ color: message.ok ? "var(--status-green)" : "var(--status-red)" }}
        >
          {message.text}
        </p>
      )}
      <div className="mt-5 flex flex-wrap items-center gap-2">
        <button type="button" className="btn chip-accent" disabled={busy || !dirty} onClick={() => void save()}>
          {busy ? "Working…" : "Save"}
        </button>
        {dirty && (
          <button type="button" className="btn" disabled={busy} onClick={() => setDraftState({})}>
            Discard
          </button>
        )}
        {test && (
          <button
            type="button"
            className="btn"
            disabled={busy || dirty}
            title={dirty ? "Save first: the test uses the saved settings." : test.hint}
            onClick={() => void attempt(async () => {
              const result = await test.run();
              return { ok: result.ok, text: result.message };
            })}
          >
            {test.label}
          </button>
        )}
        {test && dirty && <span className="text-xs" style={MUTED}>Save first — the test uses the saved settings.</span>}
      </div>
    </Card>
  );
}

function Field({
  view,
  draft,
  set,
  options,
}: {
  view: SettingView;
  draft: Draft;
  set: SetDraft;
  options?: { value: string; label: string }[];
}) {
  const label = settingLabel(view.key);
  const resettable = view.source === "APP" && draft[view.key] !== null;
  const header = (
    <span className="flex flex-wrap items-center justify-between gap-2">
      <span className="text-sm font-medium">{label}</span>
      <span className="flex items-center gap-2">
        <SourceChip view={view} draft={draft} />
        {resettable && (
          <button
            type="button"
            className="text-xs font-medium"
            style={{ color: "var(--accent-primary)" }}
            onClick={() => set(view.key, null)}
            title={view.secret ? "Remove the saved value" : "Use the environment's value again"}
          >
            {view.secret ? "Clear" : "Reset"}
          </button>
        )}
        {draft[view.key] !== undefined && (
          <button type="button" className="text-xs" style={MUTED} onClick={() => set(view.key, undefined)}>
            Undo
          </button>
        )}
      </span>
    </span>
  );

  if (view.kind === "BOOL") {
    const on = switchOn(view, draft);
    return (
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl px-4 py-3" style={{ background: "var(--bg-raised)" }}>
        <div className="min-w-0">
          <p className="text-sm font-medium">{label}</p>
          <p className="text-xs" style={MUTED}>
            {SWITCH_HELP[view.key]}
          </p>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={on}
          aria-label={label}
          onClick={() => set(view.key, on ? "false" : "true")}
          className="relative h-6 w-11 shrink-0 rounded-full transition-colors"
          style={{ background: on ? "var(--status-green)" : "var(--status-red)" }}
        >
          <span
            className="absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-[left]"
            style={{ left: on ? "1.375rem" : "0.125rem" }}
          />
          <span className="sr-only">{on ? "On" : "Off"}</span>
        </button>
        <span className="w-full text-xs font-medium sm:w-auto" style={{ color: on ? "var(--status-green)" : "var(--status-red)" }}>
          {on ? "On" : "Off — paused"}
        </span>
      </div>
    );
  }

  const placeholder = view.secret
    ? draft[view.key] === null || !view.set
      ? "Not set"
      : view.source === "APP"
        ? "•••••• saved here — type to replace"
        : "•••••• set by the environment — type to override"
    : view.environmentValue
      ? `Environment: ${view.environmentValue}`
      : "Not set";

  if (options) {
    const current = shownValue(view, draft);
    return (
      <label className="block">
        {header}
        <select className={INPUT} style={INPUT_STYLE} value={current} onChange={(e) => set(view.key, e.target.value || null)}>
          <option value="">{view.environmentValue ? `Environment's choice (${view.environmentValue})` : "None — sync off"}</option>
          {options.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
          {current && !options.some((o) => o.value === current) && <option value={current}>{current}</option>}
        </select>
      </label>
    );
  }

  return (
    <label className="block">
      {header}
      <input
        className={INPUT}
        style={INPUT_STYLE}
        type={view.secret ? "password" : view.kind === "PORT" || view.kind === "COUNT" ? "number" : view.kind === "EMAIL" ? "email" : "text"}
        inputMode={view.kind === "PORT" || view.kind === "COUNT" ? "numeric" : undefined}
        autoComplete={view.secret ? "new-password" : "off"}
        spellCheck={false}
        placeholder={placeholder}
        value={shownValue(view, draft)}
        onChange={(e) => set(view.key, e.target.value)}
      />
    </label>
  );
}

const SWITCH_HELP: Record<string, string> = {
  MAIL_ENABLED: "Off pauses every client and expert email; sign-in screens say email is unavailable.",
  GHL_WRITES_ENABLED: "Off pauses every write to GHL. Edits wait in the outbox and go out when switched back on; reads continue.",
};

function SourceChip({ view, draft }: { view: SettingView; draft: Draft }) {
  if (draft[view.key] === null) {
    return <Chip tone="var(--status-amber)">Resets on save</Chip>;
  }
  if (typeof draft[view.key] === "string") {
    return <Chip tone="var(--accent-primary)">Unsaved</Chip>;
  }
  const tone = view.source === "APP" ? "var(--accent-primary)" : view.source === "ENVIRONMENT" ? "var(--text-muted)" : "var(--status-amber)";
  return <Chip tone={tone}>{view.kind === "BOOL" && view.source === "UNSET" ? "Default" : sourceLabel(view.source)}</Chip>;
}

function Chip({ tone, children }: { tone: string; children: ReactNode }) {
  return (
    <span
      className="rounded-full px-2 py-0.5 text-[0.6875rem] font-medium"
      style={{ color: tone, background: `color-mix(in srgb, ${tone} 12%, transparent)` }}
    >
      {children}
    </span>
  );
}
