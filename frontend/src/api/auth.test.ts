import { afterEach, describe, expect, it, vi } from 'vitest'
import { csrfToken } from './auth'

// No jsdom in this project's vitest setup (node environment) - `document`
// isn't global, and csrfToken() only ever reads document.cookie as a plain
// string, so a minimal stub is enough without pulling in a full DOM.
function stubCookie(value: string) {
  vi.stubGlobal('document', { cookie: value })
}

describe('csrfToken', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('returns null when there is no XSRF-TOKEN cookie', () => {
    stubCookie('other=1')
    expect(csrfToken()).toBeNull()
  })

  it('reads the token when it is the only cookie', () => {
    stubCookie('XSRF-TOKEN=abc123')
    expect(csrfToken()).toBe('abc123')
  })

  it('finds the token among several cookies', () => {
    stubCookie('other=1; XSRF-TOKEN=abc123; another=2')
    expect(csrfToken()).toBe('abc123')
  })

  it('decodes a URI-encoded token', () => {
    stubCookie('XSRF-TOKEN=abc%2F123')
    expect(csrfToken()).toBe('abc/123')
  })
})
