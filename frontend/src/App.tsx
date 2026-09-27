import { useEffect, useState } from 'react'
import { AudioPlayer } from './components/AudioPlayer'
import { DocumentProgress } from './components/DocumentProgress'
import { SearchBox } from './components/SearchBox'
import { UploadForm } from './components/UploadForm'
import { isTerminalDocumentStatus } from './api/progress'
import { fetchMe, onSessionExpired, signOut, type Me } from './api/auth'

function readDocumentIdFromUrl(): string | null {
  return new URLSearchParams(window.location.search).get('doc')
}

export default function App() {
  const [me, setMe] = useState<Me | null | 'loading'>('loading')
  const [documentId, setDocumentId] = useState<string | null>(readDocumentIdFromUrl)
  const [documentReady, setDocumentReady] = useState(false)

  useEffect(() => {
    fetchMe()
      .then(setMe)
      .catch(() => setMe(null))
  }, [])

  // A session expiring mid-visit (any 401 from any api/*.ts call) drops back
  // to the sign-in view, rather than leaving a dead page behind.
  useEffect(() => onSessionExpired(() => setMe(null)), [])

  function openDocument(id: string) {
    setDocumentId(id)
    setDocumentReady(false)
    const params = new URLSearchParams(window.location.search)
    params.set('doc', id)
    window.history.replaceState(null, '', `?${params.toString()}`)
  }

  async function handleSignOut() {
    await signOut()
    setMe(null)
  }

  if (me === 'loading') {
    return (
      <main>
        <h1>Chitthi</h1>
      </main>
    )
  }

  if (me === null) {
    return (
      <main>
        <h1>Chitthi</h1>
        <p>
          <a href="/oauth2/authorization/google">Sign in with Google</a>
        </p>
      </main>
    )
  }

  return (
    <main>
      <header className="account-bar">
        <h1>Chitthi</h1>
        <p>
          Signed in as {me.displayName ?? me.ownerId}
          {' · '}
          <button type="button" onClick={handleSignOut}>
            Sign out
          </button>
        </p>
      </header>
      <UploadForm onUploaded={openDocument} />
      <SearchBox onSelectDocument={openDocument} />
      {documentId && (
        <>
          <DocumentProgress
            documentId={documentId}
            onStatusChange={(status) => setDocumentReady(isTerminalDocumentStatus(status))}
          />
          {documentReady && <AudioPlayer documentId={documentId} />}
        </>
      )}
    </main>
  )
}
