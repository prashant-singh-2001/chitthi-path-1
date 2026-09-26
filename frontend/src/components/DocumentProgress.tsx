import { useEffect, useState } from 'react'
import type { PageView, ProgressSnapshot } from '../api/types'
import { canEditPage, isFailed, isTerminalDocumentStatus, STAGE_LABELS, stageIndex } from '../api/progress'
import { editPageText, fetchDocument } from '../api/document'
import { PageEditor } from './PageEditor'

interface Props {
  documentId: string
  onStatusChange?: (status: string) => void
}

export function DocumentProgress({ documentId, onStatusChange }: Props) {
  const [snapshot, setSnapshot] = useState<ProgressSnapshot | null>(null)
  const [connectionLost, setConnectionLost] = useState(false)
  // Bumped after a successful edit to reopen the SSE stream - it already
  // closed itself once the document first reached a terminal status.
  const [streamKey, setStreamKey] = useState(0)
  const [pageDetails, setPageDetails] = useState<Record<number, PageView>>({})
  const [editingPageNo, setEditingPageNo] = useState<number | null>(null)
  const [editError, setEditError] = useState<string | null>(null)

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
  }, [documentId, streamKey])

  // Fetches each page's text once the document settles, so "Edit text" has
  // something to preload - the SSE snapshot only ever carries page/document
  // status, never the text itself.
  useEffect(() => {
    if (!snapshot || !isTerminalDocumentStatus(snapshot.status)) {
      return
    }
    let cancelled = false
    fetchDocument(documentId)
      .then((document) => {
        if (cancelled) return
        const byPageNo: Record<number, PageView> = {}
        for (const page of document.pages) {
          byPageNo[page.pageNo] = page
        }
        setPageDetails(byPageNo)
      })
      .catch(() => {
        // Non-fatal: edit buttons just won't have preloaded text yet: the
        // user can still open one and it will fall back to an empty draft.
      })
    return () => {
      cancelled = true
    }
    // snapshot itself intentionally excluded: this should only refetch when
    // the document's status transitions, not on every progress event.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documentId, snapshot?.status])

  async function handleSave(pageNo: number, text: string) {
    setEditError(null)
    try {
      await editPageText(documentId, pageNo, text)
      setEditingPageNo(null)
      setStreamKey((key) => key + 1)
    } catch (error) {
      setEditError(error instanceof Error ? error.message : 'Failed to save the edit')
    }
  }

  if (!snapshot) {
    return <p>Connecting…</p>
  }

  return (
    <section>
      <h2>
        Document status: <span className="status">{snapshot.status}</span>
      </h2>
      {connectionLost && <p className="warning">Connection lost; showing the last known state.</p>}
      {editError && <p className="error">{editError}</p>}
      <ul className="page-list">
        {snapshot.pages.map((page) => {
          const detail = pageDetails[page.pageNo]
          const isEditing = editingPageNo === page.pageNo
          return (
            <li key={page.pageNo} className={isFailed(page.status) ? 'page-failed' : undefined}>
              <div className="page-row">
                <span>Page {page.pageNo}</span>
                <span>
                  {isFailed(page.status) ? 'Failed' : (STAGE_LABELS[stageIndex(page.status)] ?? page.status)}
                  {detail?.edited && <span className="edited-badge"> (edited)</span>}
                </span>
                {!isEditing && canEditPage(snapshot.status, page.status) && (
                  <button
                    type="button"
                    onClick={() => {
                      setEditingPageNo(page.pageNo)
                      setEditError(null)
                    }}
                  >
                    Edit text
                  </button>
                )}
              </div>
              {isEditing && (
                <PageEditor
                  initialText={detail?.originalText ?? ''}
                  onCancel={() => setEditingPageNo(null)}
                  onSave={(text) => handleSave(page.pageNo, text)}
                />
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
