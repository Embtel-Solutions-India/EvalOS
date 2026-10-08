import { describe, expect, it } from 'vitest'
import { formatTime, pacificDay, pacificToInstant, zoneAbbreviation } from './meetingTime'

describe('meeting time is Pacific whatever the laptop says', () => {
  it('reads 11:30 typed as Pacific as 18:30 UTC in PDT and 19:30 UTC in PST', () => {
    expect(pacificToInstant('2026-10-09T11:30').toISOString()).toBe('2026-10-09T18:30:00.000Z') // PDT, UTC-7
    expect(pacificToInstant('2026-12-09T11:30').toISOString()).toBe('2026-12-09T19:30:00.000Z') // PST, UTC-8
  })

  it('round-trips: what is typed is what is shown', () => {
    expect(formatTime(pacificToInstant('2026-10-09T11:30'))).toBe('11:30 AM')
  })

  it('names the zone for the date', () => {
    expect(zoneAbbreviation(new Date('2026-12-09T12:00:00Z'))).toBe('PST')
    expect(zoneAbbreviation(new Date('2026-07-09T12:00:00Z'))).toBe('PDT')
  })

  it('puts 02:00 UTC on the previous Pacific day', () => {
    expect(pacificDay('2026-10-09T02:00:00Z')).toBe('2026-10-08')
  })
})
