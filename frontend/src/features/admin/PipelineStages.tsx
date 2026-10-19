import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { fetchStages, setStageWon } from "./adminApi";

/**
 * A pipeline's stage count, opening onto its stages and which of them win a deal (V94).
 *
 * Moving a deal onto a won stage closes it won and asks for the production team's note (D70). A new
 * stage named "Won" starts out marked; this is where the GM marks a renamed one ("Closed won") or
 * unmarks one — the Admin sees the flags but cannot change them. EvalOS's flag, not GHL's: the hourly
 * mirror never resets it.
 */
export function PipelineStages({ mirrorId, name, count, canEdit }: {
  mirrorId: string; name: string; count: number; canEdit: boolean
}) {
  const [open, setOpen] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const query = useQuery({
    queryKey: ["admin", "pipelines", mirrorId, "stages"],
    queryFn: ({ signal }) => fetchStages(mirrorId, signal),
    enabled: open,
  });

  const toggle = async (stageId: string, won: boolean) => {
    setFailure(null);
    try {
      await setStageWon(stageId, won);
      void query.refetch();
    } catch (error: unknown) {
      setFailure(error instanceof Error ? error.message : "The stage was not saved");
    }
  };

  return (
    <details onToggle={(e) => setOpen(e.currentTarget.open)}>
      <summary className="font-num cursor-pointer tabular-nums">{count}</summary>
      {failure && (
        <p className="mt-1 text-xs" style={{ color: "var(--status-red)" }} role="alert">
          {failure}
        </p>
      )}
      {query.isError && (
        <p className="mt-1 text-xs" style={{ color: "var(--status-red)" }}>
          {query.error.message}
        </p>
      )}
      <ul className="mt-1 space-y-1 text-xs">
        {query.data?.map((stage) => (
          <li key={stage.id}>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={stage.won}
                disabled={!canEdit}
                aria-label={`${stage.name} on ${name} wins the deal`}
                onChange={(e) => void toggle(stage.id, e.target.checked)}
              />
              <span style={{ color: stage.missingSince ? "var(--text-muted)" : undefined }}>{stage.name}</span>
              {stage.won && <span style={{ color: "var(--status-green)" }}>wins the deal</span>}
            </label>
          </li>
        ))}
      </ul>
    </details>
  );
}
