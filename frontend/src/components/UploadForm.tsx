import { useState, type FormEvent } from 'react'
import type { DocumentUploadResponse } from '../api/types'

// Bulbul's 11 TTS-supported languages, matching SarvamLanguage.supportsTts
// on the backend - a document in one of these gets an original-language
// audio track, not just an English one.
const LANGUAGES = [
  { code: 'hi', label: 'Hindi' },
  { code: 'bn', label: 'Bengali' },
  { code: 'gu', label: 'Gujarati' },
  { code: 'kn', label: 'Kannada' },
  { code: 'ml', label: 'Malayalam' },
  { code: 'mr', label: 'Marathi' },
  { code: 'od', label: 'Odia' },
  { code: 'pa', label: 'Punjabi' },
  { code: 'ta', label: 'Tamil' },
  { code: 'te', label: 'Telugu' },
  { code: 'en', label: 'English' },
]

interface Props {
  onUploaded: (documentId: string) => void
}

export function UploadForm({ onUploaded }: Props) {
  const [files, setFiles] = useState<FileList | null>(null)
  const [title, setTitle] = useState('')
  const [language, setLanguage] = useState('hi')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!files || files.length === 0) {
      setError('Choose at least one file')
      return
    }

    setSubmitting(true)
    setError(null)
    const body = new FormData()
    for (const file of Array.from(files)) {
      body.append('files', file)
    }
    body.append('title', title || 'Untitled')
    body.append('language', language)

    try {
      const response = await fetch('/api/documents', { method: 'POST', body })
      if (!response.ok) {
        const text = await response.text()
        throw new Error(text || `Upload failed (${response.status})`)
      }
      const data = (await response.json()) as DocumentUploadResponse
      onUploaded(data.id)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={handleSubmit}>
      <h2>Upload a document</h2>
      <label>
        Files (PDF, JPG or PNG)
        <input
          type="file"
          multiple
          accept=".pdf,.jpg,.jpeg,.png"
          onChange={(event) => setFiles(event.target.files)}
        />
      </label>
      <label>
        Title
        <input
          type="text"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          placeholder="Nanaji's 1987 letter"
        />
      </label>
      <label>
        Language
        <select value={language} onChange={(event) => setLanguage(event.target.value)}>
          {LANGUAGES.map((lang) => (
            <option key={lang.code} value={lang.code}>
              {lang.label}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={submitting}>
        {submitting ? 'Uploading…' : 'Upload'}
      </button>
      {error && <p className="error">{error}</p>}
    </form>
  )
}
