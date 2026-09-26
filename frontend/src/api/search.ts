import type { SearchResponse } from './types'

export async function search(q: string, tag?: string, year?: number): Promise<SearchResponse> {
  const params = new URLSearchParams({ q })
  if (tag) params.set('tag', tag)
  if (year) params.set('year', String(year))

  const response = await fetch(`/api/search?${params.toString()}`)
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error((body && body.message) || `Search failed (${response.status})`)
  }
  return (await response.json()) as SearchResponse
}
