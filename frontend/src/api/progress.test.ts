import { describe, expect, it } from 'vitest'
import { canEditPage, isFailed, isTerminalDocumentStatus, percentComplete, stageIndex } from './progress'

describe('stageIndex', () => {
  it('maps known statuses to their position on the track', () => {
    expect(stageIndex('PENDING')).toBe(0)
    expect(stageIndex('OCR_DONE')).toBe(1)
    expect(stageIndex('TRANSLATED')).toBe(2)
    expect(stageIndex('AUDIO_DONE')).toBe(3)
    expect(stageIndex('INDEXED')).toBe(4)
  })

  it('maps an unknown status to 0 instead of throwing', () => {
    expect(stageIndex('SOMETHING_NEW')).toBe(0)
  })
})

describe('isFailed', () => {
  it('is true only for FAILED', () => {
    expect(isFailed('FAILED')).toBe(true)
    expect(isFailed('INDEXED')).toBe(false)
  })
})

describe('percentComplete', () => {
  it('is 0 for an empty page list', () => {
    expect(percentComplete([])).toBe(0)
  })

  it('is 0 when every page is still pending', () => {
    expect(percentComplete([{ status: 'PENDING' }, { status: 'PENDING' }])).toBe(0)
  })

  it('is 100 when every page is indexed', () => {
    expect(percentComplete([{ status: 'INDEXED' }, { status: 'INDEXED' }])).toBe(100)
  })

  it('counts a failed page as fully progressed', () => {
    expect(percentComplete([{ status: 'FAILED' }])).toBe(100)
  })

  it('averages a mix of stages', () => {
    // one PENDING (0/4) + one INDEXED (4/4) = 4/8 = 50%
    expect(percentComplete([{ status: 'PENDING' }, { status: 'INDEXED' }])).toBe(50)
  })
})

describe('isTerminalDocumentStatus', () => {
  it('is true for COMPLETE and PARTIAL', () => {
    expect(isTerminalDocumentStatus('COMPLETE')).toBe(true)
    expect(isTerminalDocumentStatus('PARTIAL')).toBe(true)
  })

  it('is false for PENDING and PROCESSING', () => {
    expect(isTerminalDocumentStatus('PENDING')).toBe(false)
    expect(isTerminalDocumentStatus('PROCESSING')).toBe(false)
  })
})

describe('canEditPage', () => {
  it('is false while the document is still in flight, even for an indexed page', () => {
    expect(canEditPage('PROCESSING', 'INDEXED')).toBe(false)
  })

  it('is false for a page still pending OCR, even once the document is terminal', () => {
    expect(canEditPage('PARTIAL', 'PENDING')).toBe(false)
  })

  it('is true for a failed page once the document is terminal - the recovery path', () => {
    expect(canEditPage('PARTIAL', 'FAILED')).toBe(true)
  })

  it('is true for an indexed page once the document is complete', () => {
    expect(canEditPage('COMPLETE', 'INDEXED')).toBe(true)
  })
})
