import { describe, expect, it } from 'vitest'
import { tokenFromFragment } from '@shared/lib/portal'
import { authFailureMessage } from './authService'

describe('set-password link', () => {
  it('reads the credential out of the fragment, like every other portal token', () => {
    expect(tokenFromFragment('#abc123')).toBe('abc123')
  })

  it('treats an empty fragment as no credential', () => {
    expect(tokenFromFragment('#')).toBeNull()
  })
})

describe('authFailureMessage', () => {
  const REFUSED = "That email and password don't match. Please try again."

  it('never sends someone who is typing a password off to find a link', () => {
    // The bug this exists to prevent: the auth screens used `failureMessage`, whose 400 fallback
    // is "We could not load your documents… contact whoever sent you this link." Both halves are
    // wrong on a sign-in screen, and the second one is a falsehood — there is no link.
    for (const status of [400, 429, 500, 502, undefined]) {
      const message = authFailureMessage(status, REFUSED).toLowerCase()
      expect(message).not.toContain('link')
      expect(message).not.toContain('document')
    }
  })

  it('says the same thing for every refusal, because the server does', () => {
    // A wrong password, an account with no password and an unknown email are one 400 by design —
    // `identify` is where the difference is told, once, under the per-IP limiter.
    expect(authFailureMessage(400, REFUSED)).toBe(REFUSED)
  })

  it('tells a rate-limited client to wait, which is the only thing that works', () => {
    expect(authFailureMessage(429, REFUSED).toLowerCase()).toContain('wait')
    expect(authFailureMessage(429, REFUSED)).not.toBe(REFUSED)
  })

  it('blames itself, not the client, for anything that is not a refusal', () => {
    expect(authFailureMessage(500, REFUSED)).toContain('our side')
    expect(authFailureMessage(undefined, REFUSED)).toContain('our side')
  })
})
