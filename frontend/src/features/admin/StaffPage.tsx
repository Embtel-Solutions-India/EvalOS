import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { SheetContent, SheetRoot } from "../../components/ui/dialog";
import { useMe } from "../../lib/authContext";
import type { Role } from "../../lib/session";
import {
  ROLE_LABEL,
  SEGMENTS,
  createStaff,
  fetchBrands,
  fetchGhlUsers,
  fetchGrants,
  fetchPipelines,
  fetchStaff,
  grantPipeline,
  holdsPipelines,
  isDesk,
  revokePipeline,
  setStaffActive,
  setStaffPassword,
  updateStaff,
  type StaffForm,
  type StaffMember,
} from "./adminApi";

const INPUT = "mt-1 w-full rounded-md border px-2.5 py-1.5 text-sm";
const INPUT_STYLE = {
  borderColor: "var(--border-default)",
  background: "var(--bg-surface)",
};

/**
 * The staff directory (Unit 68): everyone who signs in to the staff app, and the GM's one place to
 * add, change, deactivate or re-password them. No staff mail exists (spec 68 §1.3): the GM sets a
 * password and hands it over. A deactivated member is refused on their next request.
 */
export default function StaffPage() {
  const staff = useQuery({
    queryKey: ["admin", "staff"],
    queryFn: ({ signal }) => fetchStaff(signal),
  });
  const brands = useQuery({
    queryKey: ["admin", "brands"],
    queryFn: ({ signal }) => fetchBrands(signal),
  });
  const [open, setOpen] = useState<StaffMember | "new" | null>(null);
  const brandName = (id: string | null) =>
    id ? (brands.data?.find((b) => b.id === id)?.name ?? "—") : "All brands";

  const rows = [...(staff.data ?? [])].sort(
    (a, b) =>
      Number(b.active) - Number(a.active) ||
      a.displayName.localeCompare(b.displayName),
  );

  return (
    <section className="space-y-4">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Staff</h1>
          <p className="text-sm" style={{ color: "var(--text-muted)" }}>
            Everyone who signs in to EvalOS. Deactivating someone signs them out
            at once.
          </p>
        </div>
        <button
          type="button"
          className="btn chip-accent"
          onClick={() => setOpen("new")}
        >
          Add staff
        </button>
      </header>

      {staff.isError && !staff.data && (
        <p className="text-sm" style={{ color: "var(--status-red)" }}>
          {staff.error.message}
        </p>
      )}
      {!staff.data && !staff.isError && (
        <p className="text-sm" style={{ color: "var(--text-muted)" }}>
          Loading…
        </p>
      )}

      {staff.data && (
        <div
          className="overflow-x-auto rounded-lg border"
          style={{
            borderColor: "var(--border-default)",
            background: "var(--bg-raised)",
          }}
        >
          <table className="w-full text-sm">
            <thead>
              <tr
                className="text-left text-xs"
                style={{ color: "var(--text-muted)" }}
              >
                <th className="px-4 py-2 font-medium">Name</th>
                <th className="px-4 py-2 font-medium">Role</th>
                <th className="px-4 py-2 font-medium">Brand</th>
                <th className="px-4 py-2 font-medium">GHL user</th>
                <th className="px-4 py-2 font-medium">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((m) => (
                <tr
                  key={m.id}
                  className="cursor-pointer border-t hover:bg-[var(--bg-surface)]"
                  style={{
                    borderColor: "var(--border-default)",
                    opacity: m.active ? 1 : 0.6,
                  }}
                  onClick={() => setOpen(m)}
                >
                  <td className="px-4 py-2">
                    <button
                      type="button"
                      className="text-left font-medium"
                      onClick={() => setOpen(m)}
                    >
                      {m.displayName}
                    </button>
                    <span
                      className="block text-xs"
                      style={{ color: "var(--text-muted)" }}
                    >
                      {m.email}
                    </span>
                  </td>
                  <td className="px-4 py-2">{ROLE_LABEL[m.role]}</td>
                  <td className="px-4 py-2">{brandName(m.brandId)}</td>
                  <td className="px-4 py-2 text-xs">
                    {m.ghlUserId ? "Linked" : "—"}
                  </td>
                  <td
                    className="px-4 py-2 text-xs"
                    style={{
                      color: m.active
                        ? "var(--status-green)"
                        : "var(--text-muted)",
                    }}
                  >
                    {m.active ? "Active" : "Deactivated"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {open && (
        <StaffSheet
          member={open === "new" ? null : open}
          brands={brands.data ?? []}
          onDone={() => {
            void staff.refetch();
            setOpen(null);
          }}
          onClose={() => setOpen(null)}
        />
      )}
    </section>
  );
}

function StaffSheet({
  member,
  brands,
  onDone,
  onClose,
}: {
  member: StaffMember | null;
  brands: { id: string; name: string }[];
  onDone: () => void;
  onClose: () => void;
}) {
  const me = useMe();
  const users = useQuery({
    queryKey: ["admin", "ghl-users"],
    queryFn: ({ signal }) => fetchGhlUsers(signal),
  });
  // Copied once when the sheet opens, so a background re-read never overwrites what is being typed.
  const [form, setForm] = useState<StaffForm>(() => ({
    displayName: member?.displayName ?? "",
    email: member?.email ?? "",
    role: member?.role ?? "CASE_MANAGER",
    brandId: member?.brandId ?? brands[0]?.id ?? null,
    segment: member?.segment ?? null,
    ghlUserId: member?.ghlUserId ?? null,
  }));
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const self = member?.id === me.id;

  const attempt = async (work: () => Promise<unknown>, done?: string) => {
    setBusy(true);
    setFailure(null);
    setFlash(null);
    try {
      await work();
      if (done) setFlash(done);
      else onDone();
    } catch (error: unknown) {
      setFailure(error instanceof Error ? error.message : "That did not work");
    } finally {
      setBusy(false);
    }
  };

  const save = () =>
    attempt(() =>
      member ? updateStaff(member.id, form) : createStaff(form, password),
    );

  const set = <K extends keyof StaffForm>(key: K, value: StaffForm[K]) =>
    setForm((f) => ({ ...f, [key]: value }));

  return (
    <SheetRoot open onOpenChange={(next) => !next && onClose()}>
      <SheetContent
        title={member ? member.displayName : "Add staff"}
        description={
          member
            ? `${ROLE_LABEL[member.role]} · ${member.active ? "active" : "deactivated"}`
            : "They sign in with this email and the password you set."
        }
        footer={
          <button
            type="button"
            className="btn chip-accent"
            disabled={busy}
            onClick={() => void save()}
          >
            {busy ? "Saving…" : member ? "Save changes" : "Add"}
          </button>
        }
      >
        <div className="flex flex-col gap-3">
          {failure && (
            <p
              className="text-sm"
              style={{ color: "var(--status-red)" }}
              role="alert"
            >
              {failure}
            </p>
          )}
          {flash && (
            <p className="text-sm" style={{ color: "var(--status-green)" }}>
              {flash}
            </p>
          )}

          <label className="text-sm">
            Name
            <input
              className={INPUT}
              style={INPUT_STYLE}
              value={form.displayName}
              onChange={(e) => set("displayName", e.target.value)}
            />
          </label>
          <label className="text-sm">
            Email
            <input
              type="email"
              className={INPUT}
              style={INPUT_STYLE}
              value={form.email}
              onChange={(e) => set("email", e.target.value)}
            />
          </label>
          <label className="text-sm">
            Role
            <select
              className={INPUT}
              style={INPUT_STYLE}
              value={form.role}
              disabled={self}
              onChange={(e) => set("role", e.target.value as Role)}
            >
              {(Object.keys(ROLE_LABEL) as Role[]).map((role) => (
                <option key={role} value={role}>
                  {ROLE_LABEL[role]}
                </option>
              ))}
            </select>
          </label>
          {form.role !== "GM" && form.role !== "ADMIN" && (
            <label className="text-sm">
              Brand
              <select
                className={INPUT}
                style={INPUT_STYLE}
                value={form.brandId ?? ""}
                onChange={(e) => set("brandId", e.target.value || null)}
              >
                <option value="">Choose…</option>
                {brands.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </select>
            </label>
          )}
          {isDesk(form.role) && (
            <label className="text-sm">
              Segment
              <select
                className={INPUT}
                style={INPUT_STYLE}
                value={form.segment ?? ""}
                onChange={(e) =>
                  set(
                    "segment",
                    (e.target.value || null) as StaffForm["segment"],
                  )
                }
              >
                <option value="">Choose…</option>
                {SEGMENTS.map((s) => (
                  <option key={s} value={s}>
                    {s.replace("_", " ").toLowerCase()}
                  </option>
                ))}
              </select>
            </label>
          )}
          <label className="text-sm">
            GHL user{" "}
            <span style={{ color: "var(--text-muted)" }}>
              (for calendars and deal owners)
            </span>
            <select
              className={INPUT}
              style={INPUT_STYLE}
              value={form.ghlUserId ?? ""}
              onChange={(e) => set("ghlUserId", e.target.value || null)}
            >
              <option value="">None</option>
              {(users.data ?? []).map((u) => (
                <option key={u.id} value={u.id}>
                  {u.name ?? u.email ?? u.id}
                </option>
              ))}
            </select>
          </label>
          {!member && (
            <label className="text-sm">
              Password{" "}
              <span style={{ color: "var(--text-muted)" }}>
                (at least 12 characters; tell them yourself)
              </span>
              <input
                type="password"
                autoComplete="new-password"
                className={INPUT}
                style={INPUT_STYLE}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </label>
          )}

          {member && (
            <>
              <hr style={{ borderColor: "var(--border-default)" }} />
              <PasswordReset
                busy={busy}
                onSet={(pw) =>
                  void attempt(
                    () => setStaffPassword(member.id, pw),
                    "Password set. Tell them the new one yourself.",
                  )
                }
              />
              {!self && (
                <button
                  type="button"
                  className="btn w-fit"
                  disabled={busy}
                  onClick={() =>
                    void attempt(() =>
                      setStaffActive(member.id, !member.active),
                    )
                  }
                >
                  {member.active ? "Deactivate" : "Reactivate"}
                </button>
              )}
              {holdsPipelines(member.role) && (
                <Pipelines memberId={member.id} role={member.role} />
              )}
            </>
          )}
        </div>
      </SheetContent>
    </SheetRoot>
  );
}

function PasswordReset({
  busy,
  onSet,
}: {
  busy: boolean;
  onSet: (password: string) => void;
}) {
  const [value, setValue] = useState("");
  return (
    <div className="text-sm">
      Set a new password
      <div className="mt-1 flex gap-2">
        <input
          type="password"
          autoComplete="new-password"
          className="w-full rounded-md border px-2.5 py-1.5 text-sm"
          style={INPUT_STYLE}
          value={value}
          onChange={(e) => setValue(e.target.value)}
        />
        <button
          type="button"
          className="btn"
          disabled={busy || value.length < 12}
          onClick={() => {
            onSet(value);
            setValue("");
          }}
        >
          Set
        </button>
      </div>
    </div>
  );
}

/**
 * A person's pipelines (Unit 44b), over the existing grant routes. The server refuses another brand's.
 * An expert network manager is offered hiring pipelines only — the server refuses any other — and holds exactly the
 * ones ticked here, so one manager can own one hiring pipeline.
 */
function Pipelines({
  memberId,
  role,
}: {
  memberId: string;
  role: StaffMember["role"];
}) {
  const grants = useQuery({
    queryKey: ["admin", "grants", memberId],
    queryFn: ({ signal }) => fetchGrants(memberId, signal),
  });
  const pipelines = useQuery({
    queryKey: ["admin", "pipelines"],
    queryFn: ({ signal }) => fetchPipelines(signal),
  });
  const [failure, setFailure] = useState<string | null>(null);
  const held = new Set(grants.data?.ghlPipelineIds ?? []);
  const hiring = role === "EXPERT_NETWORK_MANAGER";
  const live = (pipelines.data ?? []).filter(
    (p) => !p.missingSince && (!hiring || p.purpose === "EXPERT_HIRING"),
  );

  const change = async (work: () => Promise<unknown>) => {
    setFailure(null);
    try {
      await work();
      void grants.refetch();
    } catch (error: unknown) {
      setFailure(error instanceof Error ? error.message : "That did not work");
    }
  };

  return (
    <div className="text-sm">
      <p className="font-medium">{hiring ? "Hiring pipelines" : "Pipelines"}</p>
      {failure && (
        <p className="text-xs" style={{ color: "var(--status-red)" }}>
          {failure}
        </p>
      )}
      {hiring && live.length === 0 && (
        <p className="text-xs" style={{ color: "var(--text-muted)" }}>
          No pipeline is tagged Expert hiring yet. Tag one on the Pipelines
          screen first.
        </p>
      )}
      <ul className="mt-1 flex flex-col gap-1">
        {live.map((p) => (
          <li key={p.mirrorId}>
            <label className="inline-flex items-center gap-2">
              <input
                type="checkbox"
                checked={held.has(p.id)}
                onChange={(e) =>
                  void change(() =>
                    e.target.checked
                      ? grantPipeline(memberId, p.mirrorId)
                      : revokePipeline(memberId, p.mirrorId),
                  )
                }
              />
              {p.name}
            </label>
          </li>
        ))}
      </ul>
      <p className="mt-1 text-xs" style={{ color: "var(--text-muted)" }}>
        Takes effect when they next sign in.
      </p>
    </div>
  );
}
