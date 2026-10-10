import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  confirmToolCallStream,
  createSession,
  fetchModels,
  fetchSessionMessages,
  fetchSessions,
  fetchStreamStatus,
  interruptStream,
  openMessageStream,
  reconnectStream,
} from '@/lib/api'
import { consumeSseStream } from '@/lib/sse'
import type { ChatMessage, ChatSession, ModelOption, ReActRound, StreamEnvelope, ToolCallState } from '@/types'

const USER_ID_PATTERN = /^[A-Za-z0-9._:@-]{1,128}$/
const now = () => new Date().toISOString()
const makeId = () => crypto.randomUUID()
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

function updateRound(message: ChatMessage, roundId: string, updater: (round: ReActRound) => ReActRound): ChatMessage {
  const rounds = message.rounds ?? []
  const index = rounds.findIndex((round) => round.id === roundId)
  const current = index === -1
    ? { id: roundId, index: rounds.length + 1, status: 'streaming' as const, content: '', tools: [] }
    : rounds[index]
  const next = updater(current)
  return { ...message, rounds: index === -1 ? [...rounds, next] : rounds.map((round, i) => i === index ? next : round) }
}

/** 一轮流式消费的累积状态，供事件处理器与调用方共享。 */
interface StreamState {
  done: boolean
  awaitingConfirmation: boolean
  errored: boolean
  errorDetail: string
}

const MAX_RECONNECT_ATTEMPTS = 6

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
  // 同步镜像，避免 state 异步更新导致的重复启动
  const isStreamingRef = useRef(false)
  // 当前正在流式的 {会话, 助手气泡}，供停止/中断定位
  const streamingSessionRef = useRef<{ sessionId: string; assistantId: string } | null>(null)
  // 正在探测/续流的会话，避免重复 resume
  const resumeSessionRef = useRef<string | null>(null)
  // SSE 在确认暂停后已结束，仍需记住对应助手气泡以继续渲染。
  const pendingConfirmationRef = useRef<{ sessionId: string; assistantId: string } | null>(null)

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

  const updateAssistant = useCallback((sessionId: string, assistantId: string, updater: (message: ChatMessage) => ChatMessage) => {
    setMessagesBySession((current) => ({
      ...current,
      [sessionId]: (current[sessionId] ?? []).map((message) => message.id === assistantId ? updater(message) : message),
    }))
  }, [])

  /**
   * 把一条流事件应用到助手气泡。实时发送与断线重连共用，保证两条路径渲染一致。
   * 通过 state 累积 done/error，供调用方决定收尾方式（不因 error 后再来的 done 覆盖错误态）。
   */
  const applyStreamEvent = useCallback((
    sessionId: string,
    assistantId: string,
    state: StreamState,
    eventName: string,
    envelope: StreamEnvelope,
  ) => {
    const type = envelope.type || eventName
    const data = (envelope.data ?? {}) as Record<string, unknown>
    const roundId = String(data.roundId ?? 'round-1')
    switch (type) {
      case 'round_start':
        updateAssistant(sessionId, assistantId, (item) => updateRound(item, roundId, (round) => ({ ...round, status: 'streaming' })))
        break
      case 'round_model_end':
        updateAssistant(sessionId, assistantId, (item) => updateRound(item, roundId, (round) => ({ ...round, status: 'model_complete' })))
        break
      case 'text_delta':
        updateAssistant(sessionId, assistantId, (item) => updateRound(item, roundId, (round) => ({ ...round, content: round.content + String(data.delta ?? '') })))
        break
      case 'thinking_delta':
        updateAssistant(sessionId, assistantId, (item) => updateRound(item, roundId, (round) => ({ ...round, reasoning: (round.reasoning ?? '') + String(data.delta ?? '') })))
        break
      case 'tool_start': {
        const tool: ToolCallState = { id: String(data.toolCallId ?? makeId()), name: String(data.toolName ?? '未命名工具'), status: 'running' }
        updateAssistant(sessionId, assistantId, (item) => updateRound(item, roundId, (round) => ({ ...round, tools: [...round.tools, tool] })))
        break
      }
      case 'confirm_required': {
        const requestedTools = Array.isArray(data.tools) ? data.tools as Array<Record<string, unknown>> : []
        const requestedIds = new Set(requestedTools.map((tool) => String(tool.toolCallId ?? '')))
        state.awaitingConfirmation = true
        pendingConfirmationRef.current = { sessionId, assistantId }
        updateAssistant(sessionId, assistantId, (item) => ({
          ...item,
          status: 'awaiting_confirmation',
          rounds: item.rounds?.map((round) => ({
            ...round,
            tools: round.tools.map((tool) => requestedIds.has(tool.id)
              ? { ...tool, status: 'awaiting_confirmation', confirmationMessage: '该工具需要你的确认后才会执行。' }
              : tool),
          })),
        }))
        break
      }
      case 'tool_delta':
      case 'tool_result_delta':
      case 'tool_result_end': {
        const toolId = String(data.toolCallId ?? '')
        const resultState = String(data.state ?? 'SUCCESS')
        updateAssistant(sessionId, assistantId, (item) => updateRound(item, roundId, (round) => ({
          ...round,
          tools: round.tools.map((tool) => tool.id !== toolId ? tool : type === 'tool_delta'
            ? { ...tool, input: (tool.input ?? '') + String(data.delta ?? '') }
            : type === 'tool_result_delta'
              ? { ...tool, result: (tool.result ?? '') + String(data.delta ?? '') }
              : { ...tool, resultState, status: resultState === 'ERROR' ? 'error' : 'completed' }),
        })))
        break
      }
      case 'result':
        updateAssistant(sessionId, assistantId, (item) => updateRound(item, roundId, (round) => ({ ...round, content: String(data.text ?? round.content), status: 'complete' })))
        break
      case 'error': {
        const detail = data.code === 'MAX_ITERATIONS_EXCEEDED'
          ? `已达到最大工具迭代次数（${String(data.maxIters ?? '未知')}）。`
          : String(data.message ?? '流式响应发生错误。')
        state.errored = true
        state.errorDetail = detail
        updateAssistant(sessionId, assistantId, (item) => ({ ...item, status: 'error', error: detail }))
        break
      }
      case 'done':
        state.done = true
        if (!state.errored && !state.awaitingConfirmation) updateAssistant(sessionId, assistantId, (item) => ({
          ...item,
          status: 'complete',
          rounds: item.rounds?.map((round) => round.status === 'streaming' || round.status === 'model_complete'
            ? { ...round, status: 'complete' }
            : round),
        }))
        break
      default:
        break
    }
  }, [updateAssistant])

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

  const sendMessage = useCallback(async (rawMessage: string) => {
    const message = rawMessage.trim()
    if (!message || isStreamingRef.current) return
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
      rounds: [],
      sourcePrompt: message,
    }
    setMessagesBySession((current) => ({
      ...current,
      [sessionId]: [...(current[sessionId] ?? []), userMessage, assistantMessage],
    }))
    setIsStreaming(true)
    isStreamingRef.current = true

    const controller = new AbortController()
    streamController.current = controller
    streamingSessionRef.current = { sessionId, assistantId }
    const state: StreamState = { done: false, awaitingConfirmation: false, errored: false, errorDetail: '' }

    try {
      const response = await openMessageStream(userId, sessionId, message, makeId(), controller.signal)
      await consumeSseStream(response.body!, (eventName, envelope: StreamEnvelope) => {
        applyStreamEvent(sessionId, assistantId, state, eventName, envelope)
      })
      if (!state.done && !state.errored && !state.awaitingConfirmation) updateAssistant(sessionId, assistantId, (item) => ({ ...item, status: 'complete' }))
      if (state.errored) setError(state.errorDetail)
      setSessions((current) => current.map((item) => item.sessionId === sessionId ? { ...item, updatedAt: now() } : item))
    } catch (cause) {
      const stopped = cause instanceof DOMException && cause.name === 'AbortError'
      if (stopped) {
        // 用户主动停止：保留已生成内容，仅把状态标记为 stopped
        updateAssistant(sessionId, assistantId, (item) => ({ ...item, status: 'stopped' }))
      } else if (!state.errored) {
        const detail = cause instanceof Error ? cause.message : '消息发送失败。'
        updateAssistant(sessionId, assistantId, (item) => ({ ...item, status: 'error', error: detail }))
        setError(detail)
      }
    } finally {
      streamController.current = null
      streamingSessionRef.current = null
      isStreamingRef.current = false
      setIsStreaming(false)
    }
  }, [activeSession, applyStreamEvent, createNewSession, updateAssistant, userId])

  /** 对一项待确认工具作出决定，并在同一助手气泡中继续展示 Agent 输出。 */
  const confirmToolCall = useCallback(async (toolCallId: string, confirmed: boolean) => {
    const info = pendingConfirmationRef.current
    if (!info || isStreamingRef.current) return
    const { sessionId, assistantId } = info
    const controller = new AbortController()
    streamController.current = controller
    isStreamingRef.current = true
    setIsStreaming(true)
    updateAssistant(sessionId, assistantId, (item) => ({ ...item, status: 'streaming', error: undefined }))
    const state: StreamState = { done: false, awaitingConfirmation: false, errored: false, errorDetail: '' }
    try {
      const response = await confirmToolCallStream(userId, sessionId, [toolCallId], confirmed, makeId(), controller.signal)
      await consumeSseStream(response.body!, (eventName, envelope: StreamEnvelope) => {
        applyStreamEvent(sessionId, assistantId, state, eventName, envelope)
      })
      if (!state.done && !state.errored && !state.awaitingConfirmation) {
        updateAssistant(sessionId, assistantId, (item) => ({ ...item, status: 'complete' }))
      }
      if (state.errored) setError(state.errorDetail)
      if (!state.awaitingConfirmation) pendingConfirmationRef.current = null
    } catch (cause) {
      if (!(cause instanceof DOMException && cause.name === 'AbortError')) {
        const detail = cause instanceof Error ? cause.message : '提交工具确认失败。'
        updateAssistant(sessionId, assistantId, (item) => ({ ...item, status: 'awaiting_confirmation', error: detail }))
        setError(detail)
      }
    } finally {
      streamController.current = null
      isStreamingRef.current = false
      setIsStreaming(false)
    }
  }, [applyStreamEvent, updateAssistant, userId])

  /**
   * 带退避重连的续流：从缓冲 offset 起回放并持续 tail，直到本轮结束、出错或客户端中止。
   * 网络抖动导致的意外断流会自动重连（offset 续读，不重复累积内容）。
   */
  const runReconnect = useCallback(async (sessionId: string, turnId: string, assistantId: string, signal: AbortSignal) => {
    const state: StreamState = { done: false, awaitingConfirmation: false, errored: false, errorDetail: '' }
    let offset = 0
    let attempt = 0
    const backoff = () => sleep(Math.min(1000 * 2 ** attempt, 5000))

    while (!signal.aborted) {
      let response: Response
      try {
        response = await reconnectStream(userId, sessionId, turnId, offset, signal)
      } catch {
        if (signal.aborted) return
        if (++attempt >= MAX_RECONNECT_ATTEMPTS) {
          updateAssistant(sessionId, assistantId, (item) => ({ ...item, status: 'error', error: '重连失败，请刷新页面重试。' }))
          return
        }
        await backoff()
        continue
      }
      attempt = 0
      let notLive = false
      try {
        await consumeSseStream(response.body!, (eventName, envelope: StreamEnvelope) => {
          const type = envelope.type || eventName
          if (type === 'reconnect_status') { notLive = true; return }
          offset++
          applyStreamEvent(sessionId, assistantId, state, type, envelope)
        })
      } catch {
        if (signal.aborted) return
        if (++attempt >= MAX_RECONNECT_ATTEMPTS) {
          updateAssistant(sessionId, assistantId, (item) => ({ ...item, status: 'error', error: '重连失败，请刷新页面重试。' }))
          return
        }
        await backoff()
        continue
      }

      if (notLive) {
        // 探测到 running，但重连时本轮已结束/丢失：清空占位并从历史加载已持久化内容
        setMessagesBySession((current) => ({ ...current, [sessionId]: [] }))
        loadedSessions.current.delete(sessionId)
        await loadSessionMessages(sessionId)
        return
      }
      if (state.done || state.errored) {
        if (state.errored) setError(state.errorDetail)
        setSessions((current) => current.map((item) => item.sessionId === sessionId ? { ...item, updatedAt: now() } : item))
        return
      }
      // 流意外结束但未见 done：后台可能仍在跑，退避后重连续读
      if (++attempt >= MAX_RECONNECT_ATTEMPTS) {
        updateAssistant(sessionId, assistantId, (item) => ({ ...item, status: item.status === 'streaming' ? 'complete' : item.status }))
        return
      }
      await backoff()
    }
  }, [applyStreamEvent, loadSessionMessages, updateAssistant, userId])

  /** 会话激活时探测是否有正在进行的轮次；若有则建占位气泡并重连续流。 */
  const resumeIfRunning = useCallback(async (sessionId: string, signal?: AbortSignal) => {
    if (isStreamingRef.current) return
    if (resumeSessionRef.current === sessionId) return
    let status
    try {
      status = await fetchStreamStatus(userId, sessionId, signal)
    } catch {
      return
    }
    if (signal?.aborted) return
    if (status.state !== 'running' || !status.turnId) return

    resumeSessionRef.current = sessionId
    isStreamingRef.current = true
    const assistantId = makeId()
    const placeholder: ChatMessage = {
      id: assistantId,
      role: 'assistant',
      content: '',
      createdAt: now(),
      status: 'streaming',
      tools: [],
      rounds: [],
    }
    setMessagesBySession((current) => ({
      ...current,
      [sessionId]: [...(current[sessionId] ?? []), placeholder],
    }))
    setIsStreaming(true)

    const controller = new AbortController()
    streamController.current = controller
    streamingSessionRef.current = { sessionId, assistantId }
    try {
      await runReconnect(sessionId, status.turnId, assistantId, controller.signal)
    } finally {
      // 若续流结束时气泡仍在 streaming（用户停止或意外中止），保留已生成内容并标记为 stopped
      updateAssistant(sessionId, assistantId, (item) => item.status === 'streaming' ? { ...item, status: 'stopped' } : item)
      streamController.current = null
      streamingSessionRef.current = null
      isStreamingRef.current = false
      resumeSessionRef.current = null
      setIsStreaming(false)
    }
  }, [runReconnect, updateAssistant, userId])

  useEffect(() => {
    if (!activeSessionId) return
    const controller = new AbortController()
    queueMicrotask(() => {
      if (controller.signal.aborted) return
      void (async () => {
        await loadSessionMessages(activeSessionId, controller.signal)
        if (controller.signal.aborted) return
        await resumeIfRunning(activeSessionId, controller.signal)
      })()
    })
    return () => controller.abort()
  }, [activeSessionId, loadSessionMessages, resumeIfRunning])

  const startNewChat = useCallback(() => {
    if (isStreamingRef.current) return
    setActiveSessionId(null)
    setError(null)
  }, [])

  const selectSession = useCallback((sessionId: string) => {
    if (isStreamingRef.current) return
    const session = sessions.find((item) => item.sessionId === sessionId)
    if (session?.modelKey) setSelectedModelKey(session.modelKey)
    setActiveSessionId(sessionId)
    setError(null)
  }, [sessions])

  /**
   * 停止生成：先通知后端 interrupt（框架聚合半截内容落库并优雅结束本轮），再中止本地流。
   * 已生成内容保留在气泡中；因内容已持久化到 state_data，刷新后历史仍能看到。
   */
  const stopStreaming = useCallback(() => {
    const controller = streamController.current
    const info = streamingSessionRef.current
    streamController.current = null
    if (info) {
      void interruptStream(userId, info.sessionId).catch(() => undefined)
    }
    controller?.abort()
  }, [userId])

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
    confirmToolCall,
    stopStreaming,
  }
}
