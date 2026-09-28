export interface ModelOption {
  key: string
  displayName: string
  defaultModel: boolean
}

export interface ChatSession {
  sessionId: string
  modelKey: string
  title: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export interface ProblemDetail {
  title?: string
  status?: number
  detail?: string
}

export type ToolCallStatus = 'running' | 'completed' | 'error'

export interface ToolCallState {
  id: string
  name: string
  status: ToolCallStatus
  input?: string
  result?: string
  resultState?: string
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  reasoning?: string
  createdAt: string
  status?: 'streaming' | 'complete' | 'stopped' | 'error'
  tools?: ToolCallState[]
  error?: string
  sourcePrompt?: string
}

export interface StreamEnvelope<T = Record<string, unknown>> {
  type: string
  id?: string | null
  data: T
}
