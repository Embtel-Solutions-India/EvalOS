import { useQuery } from "@tanstack/react-query";
import {
  BadgeDollarSign,
  Briefcase,
  Building2,
  CalendarDays,
  CalendarPlus,
  Circle,
  Contact,
  FileText,
  GitBranch,
  Inbox,
  Kanban,
  LayoutDashboard,
  ListChecks,
  Megaphone,
  MessageSquare,
  PackageCheck,
  PlusCircle,
  Receipt,
  RefreshCw,
  StickyNote,
  Timer,
  TrendingUp,
  UserCheck,
  UserCog,
  UserPlus,
  Users,
  Wallet,
  type LucideIcon,
} from "lucide-react";
import { NavLink } from "react-router-dom";
import { useMe } from "../../lib/authContext";
import { ROLE_LABELS } from "../../lib/session";
import { matchesExactly, navSectionsFor } from "./navigation";
import { UnreadBadge } from "@evalos/chat";
import {
  BADGE_FOR_PATH,
  fetchNavBadges,
  isUrgentBadge,
  type NavBadges,
} from "./navBadges";

/**
 * The nav, as a **flush full-height tinted rail**. Filtered by role from the one table the router
 * also guards against (`navigation.ts`), so a listed item is always reachable and a reachable
 * item is always listed.
 *
 * **Icons are inline SVG here, and that is now a local exception rather than a policy.** This
 * file argued twice: first against icons at all, then for them as part of the adopted visual
 * language. What changed since is that `lucide-react` is a dependency (Unit 22), so the
 * "seven glyphs do not earn a package" reasoning no longer applies — these paths stay because
 * they work and rewriting them buys nothing, and new glyphs come from Lucide.
 *
 * **The grouping stays**, built from consecutive runs in `NAV_ITEMS`. The template ships a
 * flat accordion, and flattening this would undo the fix that stopped three roles having
 * their primary screen listed last.
 *
 * **No left accent bar on the active item.** The template marks the current screen with a
 * tinted fill and accent text only — it even leaves the border rule commented out in its
 * own stylesheet — and a fill plus a bar is two markers for one state.
 */
export default function LeftNav() {
  const me = useMe();
  // A query (Unit 70), not a mount-only read: any write, a tab refocus and a live `case.changed`
  // re-read the counts. Failure stays silent — a rail showing an error where a count should be is
  // worse than one with no counts; the screens report their own load failures.
  const badges: NavBadges | null =
    useQuery({
      queryKey: ["nav-badges"],
      queryFn: ({ signal }) => fetchNavBadges(signal),
      // The Administrator is outside `/api/metrics/nav` (spec 78) and has no queues to count.
      enabled: me.role !== "ADMIN",
    }).data ?? null;

  return (
    <nav
      className="fixed inset-y-0 left-0 z-30 flex flex-col overflow-hidden"
      style={{
        width: "var(--sidebar-width)",
        // The same fixed-attachment gradient the top bar paints, so the two merge without a seam.
        background: "var(--frame-bg)",
        backgroundAttachment: "fixed",
        color: "var(--sidebar-text)",
      }}
      aria-label="Main"
    >
      <div className="flex items-center gap-2.5 px-4 pt-5 pb-4">
        <span
          aria-hidden
          className="grid h-9 w-9 shrink-0 place-items-center text-sm font-bold"
          style={{
            background: "var(--accent-primary)",
            color: "#fff",
            borderRadius: "var(--radius-md)",
          }}
        >
          IE
        </span>
        <span className="min-w-0">
          {/* The brand you are actually in, not the product name. A Brand Manager holds one brand
              and could not previously see which — `/api/brands` is GM-only, so the name now comes
              down on `/api/me`. The GM is cross-brand and says so. */}
          <span className="block truncate text-sm font-semibold">
            {me.brandName ?? "EvalOS"}
          </span>
          <span
            className="block truncate text-[11px]"
            style={{ color: "var(--sidebar-muted)" }}
          >
            {me.role === "GM" || me.role === "ADMIN" ? "All brands" : "EvalOS"}
          </span>
        </span>
      </div>

      <div className="scroll-hidden flex-1 overflow-y-auto px-3 pb-3">
        {navSectionsFor(me.role).map((section) => (
          <div key={section.group} className="mb-3 last:mb-0">
            <h2
              className="px-3 pb-1.5 text-[10px] font-semibold tracking-[0.1em] uppercase"
              style={{ color: "var(--sidebar-muted)" }}
            >
              {section.group}
            </h2>
            <ul className="space-y-1">
              {section.items.map((item) => (
                <li key={item.path}>
                  <NavLink
                    to={item.path}
                    // Exact only where a sibling nav item lives beneath this one (`matchesExactly`).
                    end={matchesExactly(item.path)}
                    className="flex items-center gap-2.5 px-3 text-sm transition-colors"
                    style={({ isActive }) => ({
                      height: "2.25rem",
                      borderRadius: "var(--radius-md)",
                      background: isActive
                        ? "var(--sidebar-active-bg)"
                        : "transparent",
                      color: isActive ? "#fff" : "var(--sidebar-muted)",
                      fontWeight: isActive ? 600 : 500,
                    })}
                  >
                    <span aria-hidden className="shrink-0">
                      <NavIcon path={item.path} />
                    </span>
                    <span className="truncate">{item.label}</span>
                    <Badge path={item.path} badges={badges} />
                    {item.path === "/conversations" && (
                      <span className="ml-auto shrink-0">
                        <UnreadBadge />
                      </span>
                    )}
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      <div
        className="flex items-center gap-2.5 px-4 py-3.5"
        style={{ borderTop: "1px solid var(--sidebar-border)" }}
      >
        <span
          aria-hidden
          className="grid h-9 w-9 shrink-0 place-items-center text-xs font-semibold"
          style={{
            background: "var(--sidebar-active-bg)",
            color: "#fff",
            borderRadius: "var(--radius-md)",
          }}
        >
          {initials(me.displayName)}
        </span>
        <span className="min-w-0">
          <span className="block truncate text-sm font-semibold">
            {me.displayName}
          </span>
          <span
            className="block truncate text-xs"
            style={{ color: "var(--sidebar-muted)" }}
          >
            {ROLE_LABELS[me.role] ?? me.role}
          </span>
        </span>
      </div>
    </nav>
  );
}

/**
 * The count beside a screen's name, when there is one and it is not zero.
 *
 * **Zero renders nothing.** A rail carrying a row of noughts trains people to stop reading it, and
 * "nothing is waiting" is already said by the absence. This is the opposite of the dashboard rule,
 * where a metric of zero renders `0` — there the number is the answer, here it is an interruption.
 */
function Badge({ path, badges }: { path: string; badges: NavBadges | null }) {
  const key = BADGE_FOR_PATH[path];
  const count = key && badges ? badges[key] : 0;
  if (!key || count === 0) {
    return null;
  }
  const urgent = isUrgentBadge(key);
  return (
    <span
      className="font-num ml-auto shrink-0 rounded-md px-1.5 py-0.5 text-[11px] font-semibold tabular-nums"
      style={{
        // Not white-on-a-white-tint: that was drawn for the old dark rail and disappears on the light one.
        // A white pill with accent text reads on the rail and on the active blue row alike.
        background: urgent ? "var(--status-red)" : "#fff",
        color: urgent ? "#fff" : "var(--accent-primary)",
      }}
    >
      {count}
      {/* The number alone is ambiguous on a rail — say what it counts, for a screen reader and
          for anyone who has not learned the layout yet. */}
      <span className="sr-only"> waiting</span>
    </span>
  );
}

/**
 * One Lucide glyph per nav path, keyed by the same `path` the router uses so there is no second
 * list to keep in step — an entry missing here degrades to the fallback rather than breaking the
 * item. Purely presentational: `navigation.ts` is untouched. One icon set, as in the Sketch.
 */
const ICONS: Record<string, LucideIcon> = {
  "/dashboard": LayoutDashboard,
  "/conversations": MessageSquare,
  "/dashboard/sales": TrendingUp,
  "/dashboard/marketing": Megaphone,
  "/opportunities/board": Kanban,
  "/opportunities/new": PlusCircle,
  "/marketing/leads/new": PlusCircle,
  "/meetings": CalendarDays,
  "/meetings/new": CalendarPlus,
  "/hiring": UserPlus,
  "/hiring/new": UserPlus,
  "/board": Kanban,
  "/inbox": Inbox,
  "/drafts": FileText,
  "/expert-assignment": UserCheck,
  "/pm-notes": StickyNote,
  "/my-drafts": FileText,
  "/my-cases": Briefcase,
  "/delivery": PackageCheck,
  "/checklists": ListChecks,
  "/contacts": Contact,
  "/experts": Users,
  "/payouts": Wallet,
  "/payouts/cases": Receipt,
  "/payouts/experts": Users,
  "/payouts/pay": BadgeDollarSign,
  "/admin/staff": UserCog,
  "/admin/pipelines": GitBranch,
  "/admin/sync": RefreshCw,
  "/admin/jobs": Timer,
  "/brands": Building2,
};

function NavIcon({ path }: { path: string }) {
  const Icon = ICONS[path] ?? Circle;
  return <Icon className="h-5 w-5" strokeWidth={1.6} aria-hidden />;
}

/** First and last initial, so "Brandon Iyer" reads BI and a single name still renders. */
function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  const first = parts[0][0];
  return (
    parts.length > 1 ? first + parts[parts.length - 1][0] : first
  ).toUpperCase();
}
