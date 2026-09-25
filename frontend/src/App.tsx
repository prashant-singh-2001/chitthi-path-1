import { useState } from 'react'
import { AudioPlayer } from './components/AudioPlayer'
import { DocumentProgress } from './components/DocumentProgress'
import { UploadForm } from './components/UploadForm'
import { isTerminalDocumentStatus } from './api/progress'

function readDocumentIdFromUrl(): string | null {
  return new URLSearchParams(window.location.search).get('doc')
}

export default function App() {
  const [documentId, setDocumentId] = useState<string | null>(readDocumentIdFromUrl)
  const [documentReady, setDocumentReady] = useState(false)

  function handleUploaded(id: string) {
    setDocumentId(id)
    setDocumentReady(false)
    const params = new URLSearchParams(window.location.search)
    params.set('doc', id)
    window.history.replaceState(null, '', `?${params.toString()}`)
  }

  return (
    <main>
      <h1>Chitthi</h1>
      <UploadForm onUploaded={handleUploaded} />
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
