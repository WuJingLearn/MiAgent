import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError, createSession, fetchModels, fetchSessionMessages, fetchSessions, openMessageStream } from '@/lib/api'
import { consumeSseStream } from '@/lib/sse'
import type { ChatMessage, ChatSession, ModelOption, StreamEnvelope, ToolCallState } from '@/types'

const USER_ID_PATTERN = /^[A-Za-z0-9._:@-]{1,128}$/
const now = () => new Date().toISOString()
const makeId = () => crypto.randomUUID()

export function useChatWorkspace(userId: string) {
  const [models, setModels] = useState<ModelOption[]>([])
  const [sessions, setSessions] = useState<ChatSession[]>([])
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
  const [selectedModelKey, setSelectedModelKey] = useState('')
  const [messagesBySession, setMessagesBySession] = useState<Record<string, ChatMessage[]>>({})
  const [isBootstrapping, setIsBootstrapping] = useState(true)
  const [loadingHistorySessionId, setLoadingHistorySessionId] = useState<string | null>(null)
  const [isCreating, setIsCreating] = useState(false)
  const [isStreaming, setIsStreaming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lastPrompt, setLastPrompt] = useState('')
  const streamController = useRef<AbortController | null>(null)
  const loadedSessions = useRef<Set<string>>(new Set())

  const activeSession = useMemo(
    () => sessions.find((session) => session.sessionId === activeSessionId) ?? null,
    [activeSessionId, sessions],
  )
  const messages = activeSessionId ? messagesBySession[activeSessionId] ?? [] : []

  const bootstrap = useCallback(async (signal?: AbortSignal) => {
    if (!USER_ID_PATTERN.test(userId)) {
      setError('用户 ID 仅支持字母、数字及 . _ : @ -，长度 1–128。')
      setIsBootstrapping(false)
      return
    }
    setIsBootstrapping(true)
    setError(null)
    loadedSessions.current.clear()
    setMessagesBySession({})
    try {
      const [modelList, sessionList] = await Promise.all([
        fetchModels(signal),
        fetchSessions(userId, signal),
      ])
      setModels(modelList)
      setSessions(sessionList)
      setSelectedModelKey((current) => current || modelList.find((item) => item.defaultModel)?.key || modelList[0]?.key || '')
      setActiveSessionId((current) => current && sessionList.some((item) => item.sessionId === current)
        ? current
        : sessionList[0]?.sessionId ?? null)
    } catch (cause) {
      if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
        setError(cause instanceof Error ? cause.message : '初始化工作台失败。')
      }
    } finally {
      setIsBootstrapping(false)
    }
  }, [userId])

  useEffect(() => {
    const controller = new AbortController()
    queueMicrotask(() => {
      if (!controller.signal.aborted) void bootstrap(controller.signal)
    })
    return () => controller.abort()
  }, [bootstrap])

  const loadSessionMessages = useCallback(async (sessionId: string, signal?: AbortSignal) => {
    if (loadedSessions.current.has(sessionId)) return
    loadedSessions.current.add(sessionId)
    setLoadingHistorySessionId(sessionId)
    try {
      const history = await fetchSessionMessages(userId, sessionId, signal)
      setMessagesBySession((current) => {
        // 本地已有实时消息（正在生成或刚发送）时不覆盖，避免抹掉未落库的最新内容。
        if ((current[sessionId]?.length ?? 0) > 0) return current
        return { ...current, [sessionId]: history }
      })
    } catch (cause) {
      loadedSessions.current.delete(sessionId)
      if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
        setError(cause instanceof Error ? cause.message : '加载历史对话失败。')
      }
    } finally {
      setLoadingHistorySessionId((current) => current === sessionId ? null : current)
    }
  }, [userId])

  useEffect(() => {
    if (!activeSessionId) return
    const controller = new AbortController()
    queueMicrotask(() => {
      if (!controller.signal.aborted) void loadSessionMessages(activeSessionId, controller.signal)
    })
    return () => controller.abort()
  }, [activeSessionId, loadSessionMessages])

  const startNewChat = useCallback(() => {
    if (isStreaming) return
    setActiveSessionId(null)
    setError(null)
  }, [isStreaming])

  const selectSession = useCallback((sessionId: string) => {
    if (isStreaming) return
    const session = sessions.find((item) => item.sessionId === sessionId)
    if (session?.modelKey) setSelectedModelKey(session.modelKey)
    setActiveSessionId(sessionId)
    setError(null)
  }, [isStreaming, sessions])

  const createNewSession = useCallback(async (title?: string) => {
    if (!selectedModelKey) {
      setError('请先选择一个可用模型。')
      return null
    }
    setIsCreating(true)
    setError(null)
    try {
      const session = await createSession(userId, selectedModelKey, title?.trim() || undefined)
      loadedSessions.current.add(session.sessionId)
      setSessions((current) => [session, ...current.filter((item) => item.sessionId !== session.sessionId)])
      setActiveSessionId(session.sessionId)
      return session
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '创建会话失败。')
      return null
    } finally {
      setIsCreating(false)
    }
  }, [selectedModelKey, userId])

  const updateAssistant = useCallback((sessionId: string, assistantId: string, updater: (message: ChatMessage) => ChatMessage) => {
    setMessagesBySession((current) => ({
      ...current,
      [sessionId]: (current[sessionId] ?? []).map((message) => message.id === assistantId ? updater(message) : message),
    }))
  }, [])

  const sendMessage = useCallback(async (rawMessage: string) => {
    const message = rawMessage.trim()
    if (!message || isStreaming) return
    setError(null)
    setLastPrompt(message)

    let session = activeSession
    if (!session) session = await createNewSession(message.slice(0, 80))
    if (!session) return

    const sessionId = session.sessionId
    const assistantId = makeId()
    const userMessage: ChatMessage = { id: makeId(), role: 'user', content: message, createdAt: now(), status: 'complete' }
    const assistantMessage: ChatMessage = {
      id: assistantId,
      role: 'assistant',
      content: '',
      createdAt: now(),
      status: 'streaming',
      tools: [],
      sourcePrompt: message,
    }
    setMessagesBySession((current) => ({
      ...current,
      [sessionId]: [...(current[sessionId] ?? []), userMessage, assistantMessage],
    }))
    setIsStreaming(true)

    const controller = new AbortController()
    streamController.current = controller
    let receivedDone = false

    try {
      const response = await openMessageStream(userId, sessionId, message, makeId(), controller.signal)
      await consumeSseStream(response.body!, (eventName, envelope: StreamEnvelope) => {
        const type = envelope.type || eventName
        const data = envelope.data as Record<string, unknown>
        if (type === 'text_delta') {
          updateAssistant(sessionId, assistantId, (item) => ({ ...item, content: item.content + String(data.delta ?? '') }))
        } else if (type === 'thinking_delta') {
          updateAssistant(sessionId, assistantId, (item) => ({ ...item, reasoning: (item.reasoning ?? '') + String(data.delta ?? '') }))
        } else if (type === 'tool_start') {
          const tool: ToolCallState = {
            id: String(data.toolCallId ?? makeId()),
            name: String(data.toolName ?? '未命名工具'),
            status: 'running',
          }
          updateAssistant(sessionId, assistantId, (item) => ({ ...item, tools: [...(item.tools ?? []), tool] }))
        } else if (type === 'tool_delta') {
          const toolId = String(data.toolCallId ?? '')
          updateAssistant(sessionId, assistantId, (item) => ({
            ...item,
            tools: (item.tools ?? []).map((tool) => tool.id === toolId ? { ...tool, input: (tool.input ?? '') + String(data.delta ?? '') } : tool),
          }))
        } else if (type === 'tool_end') {
          const toolId = String(data.toolCallId ?? '')
          updateAssistant(sessionId, assistantId, (item) => ({
            ...item,
            tools: (item.tools ?? []).map((tool) => tool.id === toolId ? { ...tool, status: 'completed' } : tool),
          }))
        } else if (type === 'tool_result_delta') {
          const toolId = String(data.toolCallId ?? '')
          updateAssistant(sessionId, assistantId, (item) => ({
            ...item,
            tools: (item.tools ?? []).map((tool) => tool.id === toolId ? { ...tool, result: (tool.result ?? '') + String(data.delta ?? '') } : tool),
          }))
        } else if (type === 'tool_result_end') {
          const toolId = String(data.toolCallId ?? '')
          const resultState = String(data.state ?? 'SUCCESS')
          updateAssistant(sessionId, assistantId, (item) => ({
            ...item,
            tools: (item.tools ?? []).map((tool) => tool.id === toolId
              ? { ...tool, resultState, status: resultState === 'ERROR' ? 'error' : 'completed' }
              : tool),
          }))
        } else if (type === 'result') {
          updateAssistant(sessionId, assistantId, (item) => ({ ...item, content: String(data.text ?? item.content) }))
        } else if (type === 'error') {
          const detail = data.code === 'MAX_ITERATIONS_EXCEEDED'
            ? `已达到最大工具迭代次数（${String(data.maxIters ?? '未知')}）。`
            : String(data.message ?? '流式响应发生错误。')
          throw new ApiError(502, detail)
        } else if (type === 'done') {
          receivedDone = true
          updateAssistant(sessionId, assistantId, (item) => ({ ...item, status: 'complete' }))
        }
      })
      if (!receivedDone) updateAssistant(sessionId, assistantId, (item) => ({ ...item, status: 'complete' }))
      setSessions((current) => current.map((item) => item.sessionId === sessionId ? { ...item, updatedAt: now() } : item))
    } catch (cause) {
      const stopped = cause instanceof DOMException && cause.name === 'AbortError'
      const detail = stopped ? '已停止生成。' : cause instanceof Error ? cause.message : '消息发送失败。'
      updateAssistant(sessionId, assistantId, (item) => ({
        ...item,
        status: stopped ? 'stopped' : 'error',
        error: detail,
      }))
      if (!stopped) setError(detail)
    } finally {
      streamController.current = null
      setIsStreaming(false)
    }
  }, [activeSession, createNewSession, isStreaming, updateAssistant, userId])

  const stopStreaming = useCallback(() => streamController.current?.abort(), [])

  return {
    models,
    sessions,
    activeSession,
    activeSessionId,
    selectedModelKey,
    messages,
    isBootstrapping,
    isLoadingHistory: Boolean(activeSessionId && loadingHistorySessionId === activeSessionId),
    isCreating,
    isStreaming,
    error,
    lastPrompt,
    setSelectedModelKey,
    setError,
    bootstrap,
    startNewChat,
    selectSession,
    createNewSession,
    sendMessage,
    stopStreaming,
  }
}
