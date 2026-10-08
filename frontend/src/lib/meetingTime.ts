/**
 * The zone every meeting on this desk is shown and entered in.
 *
 * Pacific, not the browser's: the desks work to the location's clock, and a diary that renders in whatever
 * zone the laptop is set to books a 9:00 call for 9:30 pm to anyone sitting in India. `America/Los_Angeles`
 * rather than a fixed `-08:00`, so the label is PST in winter and PDT in summer without anyone editing it.
 */
export const MEETING_TZ = "America/Los_Angeles";

/** "PST" or "PDT" at this instant. */
export function zoneAbbreviation(at: Date = new Date()): string {
  return (
    new Intl.DateTimeFormat("en-US", {
      timeZone: MEETING_TZ,
      timeZoneName: "short",
    })
      .formatToParts(at)
      .find((part) => part.type === "timeZoneName")?.value ?? "PT"
  );
}

/** `3:00 PM` in Pacific time. */
export function formatTime(iso: string | Date): string {
  return new Date(iso).toLocaleTimeString("en-US", {
    hour: "numeric",
    minute: "2-digit",
    timeZone: MEETING_TZ,
  });
}

/** `Tue, Oct 6` in Pacific time. */
export function formatDay(iso: string | Date): string {
  return new Date(iso).toLocaleDateString("en-US", {
    weekday: "short",
    month: "short",
    day: "numeric",
    timeZone: MEETING_TZ,
  });
}

/** The Pacific calendar day an instant falls on, as `YYYY-MM-DD` — what "today" and day headings compare. */
export function pacificDay(at: string | Date): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: MEETING_TZ }).format(
    new Date(at),
  );
}

/** How far Pacific time is from UTC at this instant, in minutes (negative: behind). */
function offsetMinutes(at: Date): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: MEETING_TZ,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).formatToParts(at);
  const get = (type: string) =>
    Number(parts.find((p) => p.type === type)?.value);
  const asUtc = Date.UTC(
    get("year"),
    get("month") - 1,
    get("day"),
    get("hour"),
    get("minute"),
    get("second"),
  );
  return Math.round((asUtc - at.getTime()) / 60_000);
}

/**
 * A wall-clock time typed as Pacific (`2026-10-09T11:30`, what `<input type="datetime-local">` yields) → the
 * instant it names. Two passes, because the offset itself depends on which side of a clock change the answer
 * lands on.
 */
export function pacificToInstant(wall: string): Date {
  const naive = new Date(`${wall}:00Z`).getTime();
  const first = naive - offsetMinutes(new Date(naive)) * 60_000;
  return new Date(naive - offsetMinutes(new Date(first)) * 60_000);
}
