import { useState } from 'react'

interface Props {
  initialText: string
  onSave: (text: string) => Promise<void>
  onCancel: () => void
}

/** The inline editor a page row swaps in for "Edit text" - see DocumentProgress. */
export function PageEditor({ initialText, onSave, onCancel }: Props) {
  const [text, setText] = useState(initialText)
  const [saving, setSaving] = useState(false)

  async function handleSave() {
    setSaving(true)
    try {
      await onSave(text)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="page-editor">
      <textarea
        value={text}
        onChange={(event) => setText(event.target.value)}
        rows={6}
        disabled={saving}
      />
      <div className="page-editor-actions">
        <button type="button" onClick={handleSave} disabled={saving || text.trim().length === 0}>
          {saving ? 'Saving…' : 'Save'}
        </button>
        <button type="button" onClick={onCancel} disabled={saving}>
          Cancel
        </button>
      </div>
    </div>
  )
}
