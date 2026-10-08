import { useEffect, useRef } from 'react'
import { Bot, Brain, CheckCircle2, CircleStop, Copy, LoaderCircle, RefreshCw, TriangleAlert, UserRound, Wrench } from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Button } from '@/components/ui/button'
import type { ChatMessage, ReActRound } from '@/types'

interface MessageListProps {
  messages: ChatMessage[]
  onRetry: (prompt: string) => void
}

function MessageActions({ content }: { content: string }) {
  return (
    <Button className="message-action" onClick={() => void navigator.clipboard?.writeText(content)} aria-label="复制回答">
      <Copy size={14} />复制
    </Button>
  )
}

function ToolStatusIcon({ status }: { status: string }) {
  if (status === 'running') return <LoaderCircle size={15} />
  if (status === 'error') return <TriangleAlert size={15} />
  return <CheckCircle2 size={15} />
}

function formatToolInput(input?: string) {
  if (!input) return ''
  try {
    return JSON.stringify(JSON.parse(input), null, 2)
  } catch {
    return input
  }
}

function ReActRoundView({ round }: { round: ReActRound }) {
  return (
    <section className={`react-round is-${round.status}`}>
      <div className="react-round-title"><span>第 {round.index} 轮 ReAct</span><span>{round.status === 'streaming' ? '推理中' : round.status === 'model_complete' ? '执行工具中' : round.status === 'error' ? '执行失败' : '已完成'}</span></div>
      {round.reasoning && <details className="reasoning-block" open={round.status === 'streaming'}><summary><Brain size={14} />模型思考过程</summary><div className="reasoning-body">{round.reasoning}</div></details>}
      {round.tools.length > 0 && <div className="tool-stack" aria-label="工具调用记录">
        {round.tools.map((tool) => {
          const input = formatToolInput(tool.input)
          const hasDetail = Boolean(input) || Boolean(tool.result)
          const statusLabel = tool.status === 'running' ? '调用中' : tool.status === 'error' ? '执行失败' : '已完成'
          const header = <><ToolStatusIcon status={tool.status} /><Wrench size={14} /><code>{tool.name}</code><span>{statusLabel}</span></>
          const detail = <div className="tool-detail">
            {input && <div className="tool-detail-part"><span className="tool-detail-label">入参</span><pre>{input}</pre></div>}
            {tool.result && <div className="tool-detail-part"><span className="tool-detail-label">结果</span><pre>{tool.result}</pre></div>}
          </div>
          const className = `tool-card ${tool.status === 'running' ? 'is-running' : ''} ${tool.status === 'error' ? 'is-error' : ''}`
          return hasDetail ? <details className={className} key={tool.id} open={round.status === 'streaming'}><summary className="tool-row">{header}</summary>{detail}</details>
            : <div className={className} key={tool.id}><div className="tool-row">{header}</div></div>
        })}
      </div>}
      {round.content && <div className="markdown-body"><ReactMarkdown remarkPlugins={[remarkGfm]}>{round.content}</ReactMarkdown></div>}
    </section>
  )
}

export function MessageList({ messages, onRetry }: MessageListProps) {
  const endRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [messages])

  return (
    <div className="message-list" data-component="Message Timeline" data-od-id="message-timeline" aria-live="polite">
      {messages.map((message) => (
        <article className={`message message-${message.role}`} key={message.id}>
          <div className="message-avatar" aria-hidden="true">
            {message.role === 'assistant' ? <Bot size={17} /> : <UserRound size={17} />}
          </div>
          <div className="message-body">
            <div className="message-meta">
              <strong>{message.role === 'assistant' ? 'SRE Agent' : '你'}</strong>
              <time dateTime={message.createdAt}>{new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(message.createdAt))}</time>
              {message.status === 'streaming' && <span className="stream-label"><LoaderCircle size={13} />生成中</span>}
              {message.status === 'stopped' && <span className="muted-label"><CircleStop size={13} />已停止</span>}
            </div>

            {message.role === 'assistant' ? (
              message.rounds && message.rounds.length > 0
                ? <div className="react-round-list">{message.rounds.map((round) => <ReActRoundView key={round.id} round={round} />)}</div>
                : <div className={`markdown-body ${message.status === 'streaming' ? 'is-streaming' : ''}`}>
                    {message.content ? <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown> : message.status === 'streaming' ? <span className="thinking-line">正在组织诊断思路</span> : null}
                  </div>
            ) : <p className="user-copy">{message.content}</p>}

            {message.error && (
              <div className="inline-error" role="alert">
                <span>{message.error}</span>
                {message.status === 'error' && message.sourcePrompt && (
                  <Button onClick={() => onRetry(message.sourcePrompt!)}><RefreshCw size={14} />重试</Button>
                )}
              </div>
            )}

            {message.role === 'assistant' && message.status === 'complete' && message.content && <MessageActions content={message.content} />}
          </div>
        </article>
      ))}
      <div ref={endRef} />
    </div>
  )
}
