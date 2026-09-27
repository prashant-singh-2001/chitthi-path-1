export interface Me {
  ownerId: string
  displayName: string | null
  email: string | null
}

type SessionListener = () => void
const sessionExpiredListeners = new Set<SessionListener>()

/** App subscribes to fall back to the signed-out view on any 401, not just the initial /api/me probe. */
export function onSessionExpired(listener: SessionListener): () => void {
  sessionExpiredListeners.add(listener)
  return () => sessionExpiredListeners.delete(listener)
}

/** Every api/*.ts fetch calls this right after the response comes back. */
export function reportIfSessionExpired(response: Response): void {
  if (response.status === 401) {
    sessionExpiredListeners.forEach((listener) => listener())
  }
}

/** Reads Spring Security's CookieCsrfTokenRepository cookie - present once any page load has hit the app. */
export function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : null
}

/** Null means signed out (a 401) - the SPA's "am I signed in?" probe. */
export async function fetchMe(): Promise<Me | null> {
  const response = await fetch('/api/me')
  if (response.status === 401) {
    return null
  }
  if (!response.ok) {
    throw new Error(`Failed to load account (${response.status})`)
  }
  return (await response.json()) as Me
}

export async function signOut(): Promise<void> {
  const token = csrfToken()
  await fetch('/logout', {
    method: 'POST',
    headers: token ? { 'X-XSRF-TOKEN': token } : undefined,
  })
}
