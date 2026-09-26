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

// Mirrors com.chitthi.search.SearchHit / SearchResponse.
export interface SearchHit {
  documentId: string
  title: string
  year: number | null
  tags: string[]
  pageNo: number
  snippet: string
  matchedIn: 'TRANSLATED' | 'ORIGINAL'
  rank: number
}

export interface SearchResponse {
  hits: SearchHit[]
  tookMs: number
}

// Mirrors com.chitthi.usage.web.EndpointUsageView / DocumentUsageView.
export interface EndpointUsageView {
  endpoint: string
  calls: number
  units: number
  costInr: number
  p50Ms: number | null
  p95Ms: number | null
}

export interface DocumentUsageView {
  documentId: string
  totalCostInr: number
  byEndpoint: EndpointUsageView[]
}
