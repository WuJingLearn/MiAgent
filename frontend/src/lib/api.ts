import type { ChatMessage, ChatSession, ModelOption, ProblemDetail } from '@/types'

export class ApiError extends Error {
  readonly status: number
  readonly title?: string

  constructor(status: number, message: string, title?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.title = title
  }
}

async function throwApiError(response: Response): Promise<never> {
  let problem: ProblemDetail | undefined
  try {
    problem = (await response.json()) as ProblemDetail
  } catch {
    // The backend may return an empty or non-JSON error response.
  }

  const fallback = response.status === 401
    ? '用户身份无效，请检查 X-User-Id。'
    : response.status === 409
      ? '请求与当前会话状态冲突，请稍后重试。'
      : `请求失败（HTTP ${response.status}）`

  throw new ApiError(response.status, problem?.detail || problem?.title || fallback, problem?.title)
}

export async function fetchModels(signal?: AbortSignal): Promise<ModelOption[]> {
  const response = await fetch('/api/models', { signal })
  if (!response.ok) await throwApiError(response)
  return response.json() as Promise<ModelOption[]>
}

export async function fetchSessions(userId: string, signal?: AbortSignal): Promise<ChatSession[]> {
  const response = await fetch('/api/chat/sessions', {
    headers: { 'X-User-Id': userId },
    signal,
  })
  if (!response.ok) await throwApiError(response)
  return response.json() as Promise<ChatSession[]>
}

export async function fetchSessionMessages(
  userId: string,
  sessionId: string,
  signal?: AbortSignal,
): Promise<ChatMessage[]> {
  const response = await fetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`, {
    headers: { 'X-User-Id': userId },
    signal,
  })
  if (!response.ok) await throwApiError(response)
  return response.json() as Promise<ChatMessage[]>
}

export async function createSession(
  userId: string,
  modelKey: string,
  title?: string,
  signal?: AbortSignal,
): Promise<ChatSession> {
  const response = await fetch('/api/chat/sessions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': userId,
    },
    body: JSON.stringify({ modelKey, ...(title ? { title } : {}) }),
    signal,
  })
  if (!response.ok) await throwApiError(response)
  return response.json() as Promise<ChatSession>
}

export async function openMessageStream(
  userId: string,
  sessionId: string,
  message: string,
  requestId: string,
  signal: AbortSignal,
): Promise<Response> {
  const response = await fetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`, {
    method: 'POST',
    headers: {
      'Accept': 'text/event-stream',
      'Content-Type': 'application/json',
      'X-User-Id': userId,
    },
    body: JSON.stringify({ message, requestId }),
    signal,
  })
  if (!response.ok) await throwApiError(response)
  if (!response.body) throw new ApiError(502, '服务端未返回可读取的事件流。')
  return response
}
