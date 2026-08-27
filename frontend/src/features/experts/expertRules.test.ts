import { describe, expect, it } from 'vitest'
import {
  AVAILABILITIES,
  AVAILABILITY_TOKEN,
  FIELD_TAGS,
  LETTER_TYPES,
  MAPPABLE_FIELDS,
  NO_FILTERS,
  csvHeaders,
  guessMapping,
  hasFilters,
  initials,
  label,
} from './expertRules'

/**
 * The roster's pure parts. The vocabulary is the interesting one: it is duplicated from
 * `domain/FieldTag` and V18's CHECK so the ENM can pick from a list instead of typing into a
 * box, and a duplicate is only safe if something notices when it drifts.
 */

describe('the closed vocabulary', () => {
  it('is SCREAMING_SNAKE and has no duplicates, so it can be compared to the enum by eye', () => {
    for (const list of [FIELD_TAGS, LETTER_TYPES, AVAILABILITIES]) {
      expect(new Set(list).size, 'a repeated value would render twice in the multi-select').toBe(list.length)
      for (const value of list) {
        expect(value, `${value} is not an enum constant`).toMatch(/^[A-Z][A-Z_]*$/)
      }
    }
  })

  it('gives every availability a colour, since the board renders each as a column', () => {
    // A missing entry would be a runtime crash on the board rather than a missing badge.
    for (const availability of AVAILABILITIES) {
      expect(AVAILABILITY_TOKEN[availability], availability).toBeDefined()
    }
    // Capacity is a legitimate RAG use; being on leave is not a problem, so it is not red.
    expect(AVAILABILITY_TOKEN.AVAILABLE.fg).toBe('var(--status-green)')
    expect(AVAILABILITY_TOKEN.AT_CAPACITY.fg).toBe('var(--status-amber)')
    expect(AVAILABILITY_TOKEN.ON_LEAVE.fg).toBe('var(--text-muted)')
  })

  it('offers no payment-detail column to map', () => {
    // The server refuses such a mapping outright; not offering it is the other half.
    expect(MAPPABLE_FIELDS.map((field) => field.field)).not.toContain('paymentDetail')
  })
})

describe('label', () => {
  it('reads an enum constant as a sentence', () => {
    expect(label('MECHANICAL_ENGINEERING')).toBe('Mechanical engineering')
    expect(label('LAW')).toBe('Law')
    expect(label('TIER_1')).toBe('Tier 1')
  })

  it('keeps an acronym an acronym', () => {
    // "Rfe response" is a term of art spelled wrong, on the screen the ENM works in.
    expect(label('RFE_RESPONSE')).toBe('RFE response')
    expect(label('PERM_LETTER')).toBe('PERM letter')
  })

  it('says nothing rather than "null" for a field with nothing on file', () => {
    expect(label(null)).toBe('—')
    expect(label('')).toBe('—')
  })
})

describe('hasFilters', () => {
  it('is false for a roster nobody has narrowed', () => {
    expect(hasFilters(NO_FILTERS)).toBe(false)
  })

  it('is false again once a filter is cleared', () => {
    // The regression this replaced: `filters === NO_FILTERS` was identity, and every change
    // makes a new object — so typing one character and deleting it left the header saying
    // "matching" and the empty state blaming filters, for good.
    expect(hasFilters({ ...NO_FILTERS, search: 'osei' })).toBe(true)
    expect(hasFilters({ ...NO_FILTERS, search: '' })).toBe(false)
    // Spaces are not a filter: the server trims before it searches.
    expect(hasFilters({ ...NO_FILTERS, search: '   ' })).toBe(false)
  })

  it('counts every dropdown, not just the search box', () => {
    expect(hasFilters({ ...NO_FILTERS, fieldTag: 'LAW' })).toBe(true)
    expect(hasFilters({ ...NO_FILTERS, letterType: 'RFE_RESPONSE' })).toBe(true)
    expect(hasFilters({ ...NO_FILTERS, availability: 'AVAILABLE' })).toBe(true)
    expect(hasFilters({ ...NO_FILTERS, tier: 'TIER_1' })).toBe(true)
  })
})

describe('guessMapping', () => {
  it('maps the headers an ENM actually uses', () => {
    expect(
      guessMapping(['Name', 'Email Address', 'Institution', 'Primary Fields', 'Fee', 'Availability']),
    ).toEqual({
      Name: 'fullName',
      'Email Address': 'email',
      Institution: 'institution',
      'Primary Fields': 'primaryFields',
      Fee: 'standardFee',
      Availability: 'availability',
    })
  })

  it('leaves a header it does not recognise alone rather than guessing', () => {
    // A wrong guess that looks right is worse than a blank: it would import phone numbers as
    // titles, and the ENM would confirm a screen that read plausibly.
    expect(guessMapping(['Passport No', 'Random'])).toEqual({})
  })

  it('gives a field to the first header that claims it', () => {
    // Two headers meaning one field would otherwise both map to it, and the second would
    // silently win — the server then rejects the whole mapping as a duplicate target.
    const guessed = guessMapping(['Name', 'Expert Name'])
    expect(Object.values(guessed)).toEqual(['fullName'])
    expect(guessed.Name).toBe('fullName')
    expect(guessed['Expert Name']).toBeUndefined()
  })

  it('ignores a blank header', () => {
    expect(guessMapping(['', '   ', 'Email'])).toEqual({ Email: 'email' })
  })
})

describe('csvHeaders', () => {
  it('reads the header row and drops the byte-order mark Excel writes', () => {
    expect(csvHeaders('﻿Name,Email,Fee')).toEqual(['Name', 'Email', 'Fee'])
    expect(csvHeaders('"Name", "Email" ,Fee,')).toEqual(['Name', 'Email', 'Fee'])
  })

  it('returns nothing for an empty line, so the mapper asks for the columns by hand', () => {
    expect(csvHeaders('')).toEqual([])
  })
})

describe('initials', () => {
  it('drops the honorific, which most of this roster carries', () => {
    // Initialling the title is what makes every academic on the roster a D.
    expect(initials('Dr. John Smith')).toBe('JS')
    expect(initials('Prof Sarah Wilson')).toBe('SW')
    expect(initials('professor amara okafor')).toBe('AO')
  })

  it('takes the first and last word, so a middle name does not become the second letter', () => {
    expect(initials('John Ronald Reuel Tolkien')).toBe('JT')
    expect(initials('  spaced   out  ')).toBe('SO')
  })

  it('uses two letters of a single name rather than one lonely letter', () => {
    expect(initials('Cher')).toBe('CH')
  })

  it('has something to render for an expert with no name on file', () => {
    // fullName is nullable on the roster row, and a blank circle reads as a broken image.
    expect(initials(null)).toBe('—')
    expect(initials('')).toBe('—')
    expect(initials('Dr.')).toBe('—')
  })
})
