import { useEffect, useState } from 'react'
import type { ProgressSnapshot } from '../api/types'
import { isFailed, STAGE_LABELS, stageIndex } from '../api/progress'

interface Props {
  documentId: string
  onStatusChange?: (status: string) => void
}

export function DocumentProgress({ documentId, onStatusChange }: Props) {
  const [snapshot, setSnapshot] = useState<ProgressSnapshot | null>(null)
  const [connectionLost, setConnectionLost] = useState(false)

  useEffect(() => {
    setSnapshot(null)
    setConnectionLost(false)

    const source = new EventSource(`/api/documents/${documentId}/events`)
    source.addEventListener('progress', (event) => {
      const data = JSON.parse((event as MessageEvent<string>).data) as ProgressSnapshot
      setSnapshot(data)
      onStatusChange?.(data.status)
    })
    source.onerror = () => setConnectionLost(true)

    return () => source.close()
    // onStatusChange intentionally excluded: re-subscribing whenever the
    // caller passes a fresh closure would drop and reopen the stream.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documentId])

  if (!snapshot) {
    return <p>Connecting…</p>
  }

  return (
    <section>
      <h2>
        Document status: <span className="status">{snapshot.status}</span>
      </h2>
      {connectionLost && <p className="warning">Connection lost; showing the last known state.</p>}
      <ul className="page-list">
        {snapshot.pages.map((page) => (
          <li key={page.pageNo} className={isFailed(page.status) ? 'page-failed' : undefined}>
            <span>Page {page.pageNo}</span>
            <span>
              {isFailed(page.status) ? 'Failed' : (STAGE_LABELS[stageIndex(page.status)] ?? page.status)}
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}
