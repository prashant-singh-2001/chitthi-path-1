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

// Mirrors com.chitthi.document.web.PageView / DocumentView.
export interface PageView {
  pageNo: number
  status: string
  edited: boolean
  originalText: string | null
  translatedText: string | null
}

export interface DocumentView {
  id: string
  title: string
  language: string
  status: string
  year: number | null
  tags: string[]
  createdAt: string
  pages: PageView[]
}
