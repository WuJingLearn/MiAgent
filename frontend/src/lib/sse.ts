import type { StreamEnvelope } from '@/types'

interface ParsedEvent {
  event: string
  id?: string
  data: string
}

function findBoundary(buffer: string): { index: number; length: number } | null {
  const unix = buffer.indexOf('\n\n')
  const windows = buffer.indexOf('\r\n\r\n')
  if (unix === -1 && windows === -1) return null
  if (windows !== -1 && (unix === -1 || windows < unix)) {
    return { index: windows, length: 4 }
  }
  return { index: unix, length: 2 }
}

function parseEventBlock(block: string): ParsedEvent | null {
  let event = 'message'
  let id: string | undefined
  const data: string[] = []

  for (const line of block.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue
    const separator = line.indexOf(':')
    const field = separator === -1 ? line : line.slice(0, separator)
    const value = separator === -1 ? '' : line.slice(separator + 1).replace(/^ /, '')
    if (field === 'event') event = value
    if (field === 'id') id = value
    if (field === 'data') data.push(value)
  }

  if (data.length === 0) return null
  return { event, id, data: data.join('\n') }
}

export async function consumeSseStream(
  stream: ReadableStream<Uint8Array>,
  onEnvelope: (eventName: string, envelope: StreamEnvelope) => void,
): Promise<void> {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const consumeBlock = (block: string) => {
    const event = parseEventBlock(block)
    if (!event) return
    let envelope: StreamEnvelope
    try {
      envelope = JSON.parse(event.data) as StreamEnvelope
    } catch {
      throw new Error(`无法解析服务端事件：${event.data.slice(0, 120)}`)
    }
    onEnvelope(event.event, envelope)
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let boundary = findBoundary(buffer)
      while (boundary) {
        const block = buffer.slice(0, boundary.index)
        buffer = buffer.slice(boundary.index + boundary.length)
        consumeBlock(block)
        boundary = findBoundary(buffer)
      }
    }
    buffer += decoder.decode()
    if (buffer.trim()) consumeBlock(buffer)
  } finally {
    reader.releaseLock()
  }
}
