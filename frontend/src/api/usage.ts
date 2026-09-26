import type { DocumentUsageView } from './types'

export async function fetchDocumentUsage(documentId: string): Promise<DocumentUsageView> {
  const response = await fetch(`/api/documents/${documentId}/usage`)
  if (!response.ok) {
    throw new Error(`Failed to load usage (${response.status})`)
  }
  return (await response.json()) as DocumentUsageView
}
