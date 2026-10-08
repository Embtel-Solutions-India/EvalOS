/**
 * What to say for the hour the reader is in: morning until noon, afternoon until five, evening after.
 *
 * The reader's own clock, not the desks' Pacific one (`lib/meetingTime`): a greeting is about the person
 * looking at the screen, and "good morning" at 11pm because the location is on the West coast would be wrong.
 */
export function greetingFor(
  now: Date,
): "Good morning" | "Good afternoon" | "Good evening" {
  const hour = now.getHours();
  return hour < 12
    ? "Good morning"
    : hour < 17
      ? "Good afternoon"
      : "Good evening";
}
