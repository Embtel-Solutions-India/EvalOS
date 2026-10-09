import { ChevronLeft, ChevronRight } from "lucide-react";
import { useState } from "react";

/**
 * Client-side paging over a list that is already in memory — the admin ledgers, which the server returns whole.
 *
 * <p>The page is clamped on read rather than reset on change: when a refetch shortens the list the reader is
 * moved to the last page that still exists instead of being shown an empty one, and nothing needs an effect.
 */
export function usePaging<T>(items: readonly T[], size: number) {
  const [requested, setPage] = useState(0);
  const pages = Math.max(1, Math.ceil(items.length / size));
  const page = Math.min(requested, pages - 1);
  return {
    page,
    pages,
    setPage,
    total: items.length,
    size,
    slice: items.slice(page * size, page * size + size),
  };
}

/** "1–10 of 42" with previous / next. Renders nothing for a list that fits one page. */
export function Pager({
  paging,
  noun = "rows",
}: {
  paging: {
    page: number;
    pages: number;
    total: number;
    size: number;
    setPage: (page: number) => void;
  };
  noun?: string;
}) {
  if (paging.total <= paging.size) return null;
  const from = paging.page * paging.size + 1;
  const to = Math.min(from + paging.size - 1, paging.total);
  const step = (
    label: string,
    to: number,
    disabled: boolean,
    icon: React.ReactNode,
  ) => (
    <button
      type="button"
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={() => paging.setPage(to)}
      className="grid h-8 w-8 place-items-center rounded-full disabled:opacity-40"
      style={{ background: "var(--bg-raised)", color: "var(--text-muted)" }}
    >
      {icon}
    </button>
  );
  return (
    <nav
      aria-label={`Paging ${noun}`}
      className="mt-3 flex items-center justify-between gap-3 text-xs"
      style={{ color: "var(--text-muted)" }}
    >
      <span className="font-num tabular-nums">
        {from}–{to} of {paging.total} {noun}
      </span>
      <span className="flex items-center gap-2">
        <span className="font-num tabular-nums">
          Page {paging.page + 1} of {paging.pages}
        </span>
        {step(
          "Previous page",
          paging.page - 1,
          paging.page === 0,
          <ChevronLeft className="h-4 w-4" aria-hidden />,
        )}
        {step(
          "Next page",
          paging.page + 1,
          paging.page >= paging.pages - 1,
          <ChevronRight className="h-4 w-4" aria-hidden />,
        )}
      </span>
    </nav>
  );
}
