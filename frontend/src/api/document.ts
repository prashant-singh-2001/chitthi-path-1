import { csrfToken, reportIfSessionExpired } from './auth'
import type { DocumentView, PageView } from './types'

export async function fetchDocument(documentId: string): Promise<DocumentView> {
  const response = await fetch(`/api/documents/${documentId}`)
  reportIfSessionExpired(response)
  if (!response.ok) {
    throw new Error(`Failed to load document (${response.status})`)
  }
  return (await response.json()) as DocumentView
}

/** FR8: PUT .../pages/{pageNo}/text. Throws with the server's error message when the edit is rejected (400/404/409). */
export async function editPageText(documentId: string, pageNo: number, text: string): Promise<PageView> {
  const response = await fetch(`/api/documents/${documentId}/pages/${pageNo}/text`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() ?? '' },
    body: JSON.stringify({ text }),
  })
  reportIfSessionExpired(response)
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error((body && body.message) || `Failed to save page text (${response.status})`)
  }
  return (await response.json()) as PageView
}
