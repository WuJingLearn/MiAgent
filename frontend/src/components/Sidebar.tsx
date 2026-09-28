import { Bot, ChevronRight, MessageSquareText, Plus, ServerCog, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { ChatSession, ModelOption } from '@/types'

interface SidebarProps {
  open: boolean
  sessions: ChatSession[]
  activeSessionId: string | null
  models: ModelOption[]
  selectedModelKey: string
  userId: string
  disabled: boolean
  onClose: () => void
  onNewChat: () => void
  onSelectSession: (sessionId: string) => void
  onModelChange: (modelKey: string) => void
}

function formatUpdatedAt(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间未知'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

export function Sidebar({
  open,
  sessions,
  activeSessionId,
  models,
  selectedModelKey,
  userId,
  disabled,
  onClose,
  onNewChat,
  onSelectSession,
  onModelChange,
}: SidebarProps) {
  return (
    <>
      <Button className={`sidebar-scrim ${open ? 'is-visible' : ''}`} aria-label="关闭会话导航" onClick={onClose} />
      <aside className={`sidebar ${open ? 'is-open' : ''}`} data-component="Session Sidebar" data-od-id="session-sidebar">
        <div className="sidebar-brand">
          <div className="brand-mark" aria-hidden="true"><Bot size={20} strokeWidth={1.8} /></div>
          <div>
            <strong>SRE Agent</strong>
            <span>AI operations workspace</span>
          </div>
          <Button className="icon-button sidebar-close" onClick={onClose} aria-label="关闭侧栏"><X size={18} /></Button>
        </div>

        <div className="sidebar-actions">
          <Button className="new-chat-button" onClick={onNewChat} disabled={disabled}>
            <Plus size={16} />
            新建会话
            <span className="key-hint">⌘ K</span>
          </Button>
          <label className="field-label" htmlFor="new-session-model">新会话模型</label>
          <div className="select-wrap">
            <ServerCog size={15} aria-hidden="true" />
            <select
              id="new-session-model"
              value={selectedModelKey}
              onChange={(event) => onModelChange(event.target.value)}
              disabled={disabled || models.length === 0}
            >
              {models.length === 0 && <option value="">暂无可用模型</option>}
              {models.map((model) => (
                <option key={model.key} value={model.key}>{model.displayName}</option>
              ))}
            </select>
          </div>
        </div>

        <nav className="session-nav" aria-label="历史会话">
          <div className="nav-heading">
            <span>会话</span>
            <span className="count-badge">{sessions.length}</span>
          </div>
          <div className="session-list">
            {sessions.length === 0 ? (
              <div className="sidebar-empty">
                <MessageSquareText size={19} />
                <p>还没有会话</p>
                <span>选择模型后发起第一次诊断。</span>
              </div>
            ) : sessions.map((session) => {
              const active = session.sessionId === activeSessionId
              return (
                <Button
                  className={`session-row ${active ? 'is-active' : ''}`}
                  key={session.sessionId}
                  onClick={() => { onSelectSession(session.sessionId); onClose() }}
                  aria-current={active ? 'page' : undefined}
                >
                  <MessageSquareText size={16} aria-hidden="true" />
                  <span className="session-copy">
                    <strong>{session.title || '未命名会话'}</strong>
                    <small>{session.modelKey} · {formatUpdatedAt(session.updatedAt)}</small>
                  </span>
                  <ChevronRight size={14} aria-hidden="true" />
                </Button>
              )
            })}
          </div>
        </nav>

        <div className="sidebar-footer">
          <span className="status-dot" aria-hidden="true" />
          <span className="truncate">身份：{userId}</span>
        </div>
      </aside>
    </>
  )
}
