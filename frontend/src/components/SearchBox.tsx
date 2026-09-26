import { useState, type FormEvent } from 'react'
import type { SearchHit } from '../api/types'
import { search } from '../api/search'

interface Props {
  onSelectDocument: (documentId: string) => void
}

export function SearchBox({ onSelectDocument }: Props) {
  const [q, setQ] = useState('')
  const [tag, setTag] = useState('')
  const [year, setYear] = useState('')
  const [hits, setHits] = useState<SearchHit[] | null>(null)
  const [tookMs, setTookMs] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [searching, setSearching] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (q.trim().length < 2) {
      setError('Enter at least 2 characters')
      return
    }

    setSearching(true)
    setError(null)
    try {
      const response = await search(q.trim(), tag.trim() || undefined, year ? Number(year) : undefined)
      setHits(response.hits)
      setTookMs(response.tookMs)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Search failed')
      setHits(null)
    } finally {
      setSearching(false)
    }
  }

  return (
    <section>
      <h2>Search</h2>
      <form onSubmit={handleSubmit}>
        <label>
          Query
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search original or translated text" />
        </label>
        <label>
          Tag (optional)
          <input value={tag} onChange={(e) => setTag(e.target.value)} />
        </label>
        <label>
          Year (optional)
          <input value={year} onChange={(e) => setYear(e.target.value)} type="number" />
        </label>
        <button type="submit" disabled={searching}>
          {searching ? 'Searching…' : 'Search'}
        </button>
      </form>

      {error && <p className="error">{error}</p>}

      {hits && (
        <>
          <p className="notice">
            {hits.length} result{hits.length === 1 ? '' : 's'} in {tookMs}ms
          </p>
          <ul className="page-list">
            {hits.map((hit) => (
              <li key={`${hit.documentId}-${hit.pageNo}`}>
                <button type="button" className="search-hit" onClick={() => onSelectDocument(hit.documentId)}>
                  <strong>{hit.title}</strong> — page {hit.pageNo}
                  {hit.year && ` (${hit.year})`}
                </button>
                <p className="search-snippet">{hit.snippet}</p>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
