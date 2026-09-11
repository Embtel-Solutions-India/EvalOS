import { describe, expect, it } from 'vitest'
import { tokenFromFragment } from '@shared/lib/portal'

describe('set-password link', () => {
  it('reads the credential out of the fragment, like every other portal token', () => {
    expect(tokenFromFragment('#abc123')).toBe('abc123')
  })

  it('treats an empty fragment as no credential', () => {
    expect(tokenFromFragment('#')).toBeNull()
  })
})
