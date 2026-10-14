import type { CSSProperties, ReactNode } from "react";

/**
 * The plain card shell: one surface, one soft shadow, no border — the same look as the dashboard cards.
 *
 * **Not {@link Card} from `card.tsx`.** That one owns a state machine — loading skeleton, empty
 * note, error with retry — and is right for a tile whose whole job is to render one figure's
 * outcome. A panel holding a table or a form has its own states and does not want a second set
 * imposed on it from outside.
 */
export function Surface({ children, style }: { children: ReactNode; style?: CSSProperties }) {
  return (
    <section
      className="rounded-[1.25rem] p-5"
      style={{
        background: "var(--bg-surface)",
        boxShadow: "var(--shadow-soft)",
        ...style,
      }}
    >
      {children}
    </section>
  );
}

/**
 * A {@link Surface} with a heading and an icon.
 *
 * **It lives here, and each panel's own component renders it, because a panel has to be able to
 * render nothing at all.** A panel with nothing to show returns null, and when the page owned the
 * wrapper instead, that null left a titled empty box behind. A heading with nothing under it is worse than
 * either outcome it was meant to distinguish: it reads as a panel that failed to load.
 */
export function Panel({
  title,
  icon,
  action,
  tint,
  children,
}: {
  title: string;
  icon: ReactNode;
  /**
   * A colour for the heading's icon. With it the title row becomes a blue-grey band across the card's top, its
   * corners rounded with the card's, and the icon sits in this colour on a white tile. Absent: the plain row
   * and muted icon.
   */
  tint?: string;
  /** Rendered at the far end of the heading row — an "Add note", an "Edit". */
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <Surface>
      <div
        className={
          tint
            ? "-mx-5 -mt-5 mb-4 flex items-center justify-between gap-2 rounded-t-[1.25rem] px-5 py-3"
            : "mb-4 flex items-center justify-between gap-2"
        }
        style={tint ? { background: "#e3e8f2" } : undefined}
      >
        <h2 className="flex items-center gap-2 text-base font-semibold">
          <span
            className={
              tint
                ? "flex h-8 w-8 items-center justify-center rounded-lg [&>svg]:h-4 [&>svg]:w-4"
                : "[&>svg]:h-4 [&>svg]:w-4"
            }
            style={
              tint
                ? { color: tint, background: "var(--bg-surface)" }
                : { color: "var(--text-muted)" }
            }
            aria-hidden
          >
            {icon}
          </span>
          {title}
        </h2>
        {action}
      </div>
      {children}
    </Surface>
  );
}
