import { useEffect, useState } from 'react'
import type { AudioUrlResponse } from '../api/types'

type Lang = 'orig' | 'en'

interface TrackState {
  lang: Lang
  url?: string
  fallback?: boolean
  missing?: boolean
}

const TRACK_TITLES: Record<Lang, string> = {
  orig: 'Original language',
  en: 'English',
}

interface Props {
  documentId: string
}

export function AudioPlayer({ documentId }: Props) {
  const [tracks, setTracks] = useState<TrackState[]>([{ lang: 'orig' }, { lang: 'en' }])

  useEffect(() => {
    let cancelled = false

    async function loadTrack(lang: Lang) {
      try {
        const response = await fetch(`/api/documents/${documentId}/audio?lang=${lang}`)
        if (response.status === 404) {
          if (!cancelled) markMissing(lang)
          return
        }
        if (!response.ok) {
          throw new Error(`Failed to load ${lang} audio (${response.status})`)
        }
        const data = (await response.json()) as AudioUrlResponse
        if (!cancelled) {
          setTracks((prev) =>
            prev.map((track) =>
              track.lang === lang ? { ...track, url: data.url, fallback: data.fallback } : track,
            ),
          )
        }
      } catch {
        if (!cancelled) markMissing(lang)
      }
    }

    function markMissing(lang: Lang) {
      setTracks((prev) => prev.map((track) => (track.lang === lang ? { ...track, missing: true } : track)))
    }

    loadTrack('orig')
    loadTrack('en')

    return () => {
      cancelled = true
    }
  }, [documentId])

  return (
    <section>
      <h2>Listen</h2>
      {tracks.map((track) => (
        <div key={track.lang} className="audio-track">
          <h3>{TRACK_TITLES[track.lang]}</h3>
          {track.fallback && (
            <p className="notice">No original-language voice available; playing English instead.</p>
          )}
          {track.missing && <p className="notice">Not available.</p>}
          {track.url && <audio controls src={track.url} />}
        </div>
      ))}
    </section>
  )
}
