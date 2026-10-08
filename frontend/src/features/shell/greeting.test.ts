import { describe, expect, it } from "vitest";
import { greetingFor } from "./greeting";

describe("greetingFor", () => {
  const at = (hour: number, minute = 0) => new Date(2026, 9, 9, hour, minute);

  it("is morning until noon, afternoon until five, evening after", () => {
    expect(greetingFor(at(0))).toBe("Good morning");
    expect(greetingFor(at(11, 59))).toBe("Good morning");
    expect(greetingFor(at(12))).toBe("Good afternoon");
    expect(greetingFor(at(16, 59))).toBe("Good afternoon");
    expect(greetingFor(at(17))).toBe("Good evening");
    expect(greetingFor(at(23, 59))).toBe("Good evening");
  });
});
