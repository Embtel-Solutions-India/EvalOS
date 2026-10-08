import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { PURPOSES, fetchPipelines, setPurpose } from "./adminApi";

/**
 * D61's "pipelines screen" (Unit 68): GHL's pipelines as the mirror holds them, and the one column
 * EvalOS owns — purpose. Tagging `EXPERT_HIRING` makes a pipeline one an ENM can be given (per person, on the Staff
 * screen — it used to give it to every ENM of the brand at once); nothing
 * infers a purpose from a name (D11a), so an untagged pipeline stays `UNASSIGNED`.
 */
export default function PipelinesPage() {
  const query = useQuery({
    queryKey: ["admin", "pipelines"],
    queryFn: ({ signal }) => fetchPipelines(signal),
  });
  const [failure, setFailure] = useState<string | null>(null);

  const change = async (mirrorId: string, purpose: string) => {
    setFailure(null);
    try {
      await setPurpose(mirrorId, purpose);
      void query.refetch();
    } catch (error: unknown) {
      setFailure(
        error instanceof Error ? error.message : "The purpose was not saved",
      );
    }
  };

  return (
    <section className="space-y-4">
      <header>
        <h1 className="text-xl font-semibold tracking-tight">Pipelines</h1>
        <p className="text-sm" style={{ color: "var(--text-muted)" }}>
          Mirrored from GHL every hour. The purpose says what a pipeline is for:
          only Expert hiring pipelines can be given to an expert network
          manager, one by one, on the Staff screen.
        </p>
      </header>
      {failure && (
        <p
          className="text-sm"
          style={{ color: "var(--status-red)" }}
          role="alert"
        >
          {failure}
        </p>
      )}
      {query.isError && !query.data && (
        <p className="text-sm" style={{ color: "var(--status-red)" }}>
          {query.error.message}
        </p>
      )}
      {!query.data && !query.isError && (
        <p className="text-sm" style={{ color: "var(--text-muted)" }}>
          Loading…
        </p>
      )}
      {query.data?.length === 0 && (
        <p className="text-sm" style={{ color: "var(--text-muted)" }}>
          The mirror holds no pipelines yet. Run PIPELINE_MIRROR from Background
          jobs.
        </p>
      )}
      {query.data && query.data.length > 0 && (
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
                <th className="px-4 py-2 font-medium">Pipeline</th>
                <th className="px-4 py-2 font-medium">Stages</th>
                <th className="px-4 py-2 font-medium">Last synced</th>
                <th className="px-4 py-2 font-medium">Purpose</th>
              </tr>
            </thead>
            <tbody>
              {query.data.map((p) => (
                <tr
                  key={p.mirrorId}
                  className="border-t"
                  style={{ borderColor: "var(--border-default)" }}
                >
                  <td className="px-4 py-2 font-medium">
                    {p.name}
                    {p.missingSince && (
                      <span
                        className="ml-2 text-xs"
                        style={{ color: "var(--status-amber)" }}
                      >
                        gone from GHL since{" "}
                        {new Date(p.missingSince).toLocaleDateString()}
                      </span>
                    )}
                  </td>
                  <td className="font-num px-4 py-2 tabular-nums">
                    {p.stages.length}
                  </td>
                  <td className="px-4 py-2 text-xs">
                    {p.syncedAt
                      ? new Date(p.syncedAt).toLocaleString()
                      : "never"}
                  </td>
                  <td className="px-4 py-2">
                    <select
                      aria-label={`Purpose of ${p.name}`}
                      value={p.purpose}
                      onChange={(e) => void change(p.mirrorId, e.target.value)}
                      className="rounded-md border px-2 py-1 text-xs"
                      style={{
                        borderColor: "var(--border-default)",
                        background: "var(--bg-surface)",
                      }}
                    >
                      {PURPOSES.map((purpose) => (
                        <option key={purpose} value={purpose}>
                          {purpose.replace("_", " ").toLowerCase()}
                        </option>
                      ))}
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
