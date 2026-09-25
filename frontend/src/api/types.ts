export interface PageProgress {
  pageNo: number
  status: string
}

export interface ProgressSnapshot {
  documentId: string
  status: string
  pages: PageProgress[]
}

export interface DocumentUploadResponse {
  id: string
}

export interface AudioUrlResponse {
  url: string
  expiresAt: string
  fallback: boolean
}
