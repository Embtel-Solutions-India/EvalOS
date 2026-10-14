import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from "lucide-react";
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

/**
 * "1–10 of 42", the page, and first / previous / next / last. Works over a server page too: pass the server's
 * total and the page size it was asked for.
 *
 * <p>By default it renders nothing for a list that fits one page. A screen whose reader chooses the page size
 * (`sizes`) shows it always — the size control is how a short list becomes a paged one, so hiding it would hide
 * the way in.
 */
export function Pager({
  paging,
  noun = "rows",
  sizes,
  onSizeChange,
}: {
  paging: {
    page: number;
    pages: number;
    total: number;
    size: number;
    setPage: (page: number) => void;
  };
  noun?: string;
  /** Page sizes offered; with `onSizeChange`, adds a "Rows per page" select and keeps the pager always visible. */
  sizes?: number[];
  onSizeChange?: (size: number) => void;
}) {
  const choosesSize = sizes !== undefined && onSizeChange !== undefined;
  if (!choosesSize && paging.total <= paging.size) return null;
  const from = paging.total === 0 ? 0 : paging.page * paging.size + 1;
  const to = Math.min(paging.page * paging.size + paging.size, paging.total);
  const last = Math.max(paging.pages - 1, 0);
  const step = (label: string, target: number, disabled: boolean, icon: React.ReactNode) => (
    <button
      type="button"
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={() => paging.setPage(target)}
      className="grid h-8 w-8 place-items-center rounded-full disabled:opacity-40"
      style={{ background: "var(--bg-raised)", color: "var(--text-muted)" }}
    >
      {icon}
    </button>
  );
  return (
    <nav
      aria-label={`Paging ${noun}`}
      className="mt-3 flex flex-wrap items-center justify-between gap-x-4 gap-y-2 text-xs"
      style={{ color: "var(--text-muted)" }}
    >
      <span className="flex flex-wrap items-center gap-3">
        <span className="font-num tabular-nums">
          {from}–{to} of {paging.total} {noun}
        </span>
        {choosesSize && (
          <label className="flex items-center gap-1.5">
            Rows per page
            <select
              className="rounded-md border px-1.5 py-1 text-xs"
              style={{ borderColor: "var(--border-default)", background: "var(--bg-surface)", color: "var(--text-primary)" }}
              value={paging.size}
              onChange={(event) => onSizeChange(Number(event.target.value))}
            >
              {sizes.map((size) => (
                <option key={size} value={size}>
                  {size}
                </option>
              ))}
            </select>
          </label>
        )}
      </span>
      <span className="flex items-center gap-2">
        <span className="font-num tabular-nums">
          Page {paging.page + 1} of {Math.max(paging.pages, 1)}
        </span>
        {step("First page", 0, paging.page === 0, <ChevronsLeft className="h-4 w-4" aria-hidden />)}
        {step("Previous page", paging.page - 1, paging.page === 0, <ChevronLeft className="h-4 w-4" aria-hidden />)}
        {step("Next page", paging.page + 1, paging.page >= last, <ChevronRight className="h-4 w-4" aria-hidden />)}
        {step("Last page", last, paging.page >= last, <ChevronsRight className="h-4 w-4" aria-hidden />)}
      </span>
    </nav>
  );
}
