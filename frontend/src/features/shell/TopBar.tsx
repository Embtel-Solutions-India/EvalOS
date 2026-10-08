import { useEffect, useState } from "react";
import { useLocation } from "react-router-dom";
import { useAuth, useMe } from "../../lib/authContext";
import { LogOut } from "lucide-react";
import DateFilter from "./DateFilter";
import { greetingFor } from "./greeting";
import NotificationBell from "./NotificationBell";

/**
 * Scope on the left, account on the right, with the search field between them.
 *
 * The two controls on the left both answer "which work am I looking at" — brand, then
 * period — so they sit together rather than floating in a row of equals. Search is a
 * controlled input that goes nowhere: there is no search endpoint, and a box that silently
 * does nothing is better than one that pretends by filtering the current page. Its
 * placeholder says so rather than promising a date.
 *
 * **No background, no border, no shadow** (`UI_MIGRATION_GUIDE.md`): the header sits on the
 * canvas rather than being a white bar, which is what keeps the nav card and the content
 * cards the only white surfaces on screen. `sticky` rather than `fixed`, so the canvas
 * colour travels with it and nothing below needs a hardcoded offset.
 *
 * **The page title is not here yet, deliberately.** The template puts a 36px title in this
 * bar, and six screens currently render their own `h1` paired with their own eyebrow. Moving
 * it here means editing all six in one go or shipping a duplicated heading in between, so it
 * is the follow-up once the last screen is migrated — recorded in the guide rather than left
 * as a surprise.
 */
export default function TopBar() {
  const { logout } = useAuth();
  const me = useMe();
  const first = me.displayName.trim().split(/\s+/)[0];
  // The period filter drives the role dashboards only. Everywhere else it filtered nothing, so it is not drawn;
  // the Administrator's overview has no period either.
  const showPeriod =
    useLocation().pathname === "/dashboard" && me.role !== "ADMIN";

  return (
    <header
      className="sticky top-0 z-20 flex items-center gap-3"
      style={{
        background: "var(--topbar-bg)",
        minHeight: "var(--header-height)",
        padding: `0 var(--shell-gutter)`,
      }}
    >
      {/* The Sketch's header opens with a greeting; it goes first and yields to the controls on
          narrow screens rather than pushing them off the bar. */}
      <Greeting name={first} />

      {/* The brand switcher and the search box are hidden for now (2026-10-08) and come back later:
          `BrandSwitcher.tsx` stays in the tree untouched, and the GM keeps the all-brands view. */}
      <div className="min-w-0 flex-1" />

      <div className="flex items-center gap-2">
        {/* Next to the bell, on the right: it is a control for the screen, like the bell is for the account. */}
        {showPeriod && <DateFilter />}
        <NotificationBell />
        <button
          type="button"
          onClick={logout}
          className="inline-flex h-9 items-center gap-2 px-4 text-sm font-medium text-white transition-opacity hover:opacity-90"
          style={{
            background: "var(--status-red)",
            borderRadius: "999px",
            boxShadow: "var(--shadow-soft)",
          }}
        >
          <LogOut className="h-4 w-4" aria-hidden />
          Sign out
        </button>
      </div>
    </header>
  );
}

/**
 * "Good Morning, Alex" — big, black monospace in regular weight, typed out a character at a time.
 *
 * <p>Black sits straight on the pale bar (14:1), so there is no pill behind it: the pill existed only to carry
 * white text. The hour is re-read every minute, so a screen left open across noon changes its greeting, and typing
 * again only happens when the words change — a minute's tick with the same greeting does not restart it. Under
 * `prefers-reduced-motion` the whole line appears at once.
 */
function Greeting({ name }: { name: string }) {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(timer);
  }, []);

  // Each word of the greeting capitalised; the name is left exactly as it is on the account.
  const text = `${greetingFor(now).replace(/\b[a-z]/g, (c) => c.toUpperCase())}, ${name}`;
  const [typed, setTyped] = useState(0);
  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      setTyped(text.length);
      return;
    }
    setTyped(0);
    const timer = setInterval(() => {
      setTyped((count) => {
        if (count >= text.length) clearInterval(timer);
        return Math.min(count + 1, text.length);
      });
    }, 70);
    return () => clearInterval(timer);
  }, [text]);

  return (
    <p
      aria-label={text}
      className="hidden shrink-0 items-center text-3xl leading-none font-normal lg:flex"
      style={{ fontFamily: "var(--font-mono)", color: "#000000" }}
    >
      <span aria-hidden>{text.slice(0, typed)}</span>
      {/* The caret rides the typing and goes when the line is written. */}
      {typed < text.length && (
        <span
          aria-hidden
          className="caret ml-0.5 inline-block h-[1em] w-[0.5ch] bg-black"
        />
      )}
      <span aria-hidden className="invisible">
        {text.slice(typed)}
      </span>
    </p>
  );
}
