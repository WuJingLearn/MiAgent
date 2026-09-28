import { useEffect, useMemo, useState } from 'react'
import { AlertCircle, RefreshCw, X } from 'lucide-react'
import { AppHeader } from '@/components/AppHeader'
import { Composer } from '@/components/Composer'
import { EmptyState } from '@/components/EmptyState'
import { MessageList } from '@/components/MessageList'
import { Sidebar } from '@/components/Sidebar'
import { UserSettings } from '@/components/UserSettings'
import { Button } from '@/components/ui/button'
import { useChatWorkspace } from '@/hooks/useChatWorkspace'

function App() {
  const [userId, setUserId] = useState('demo-user')
  const [theme, setTheme] = useState<'light' | 'dark'>(() => matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [draft, setDraft] = useState('')
  const workspace = useChatWorkspace(userId)
  const startNewChat = workspace.startNewChat

  useEffect(() => {
    document.documentElement.dataset.appearance = theme
  }, [theme])

  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        startNewChat()
        setDraft('')
      }
    }
    window.addEventListener('keydown', handleShortcut)
    return () => window.removeEventListener('keydown', handleShortcut)
  }, [startNewChat])

  const modelLabel = useMemo(() => {
    const key = workspace.activeSession?.modelKey || workspace.selectedModelKey
    return workspace.models.find((model) => model.key === key)?.displayName || key
  }, [workspace.activeSession, workspace.models, workspace.selectedModelKey])

  const title = workspace.activeSession?.title || (workspace.activeSession ? '未命名会话' : '新会话')
  const status = workspace.isStreaming ? '生成中' : workspace.activeSession?.status === 'OPEN' ? '已连接' : '就绪'

  return (
    <div className="app-shell" data-component="SRE Agent Workspace" data-od-id="sre-agent-workspace">
      <Sidebar
        open={sidebarOpen}
        sessions={workspace.sessions}
        activeSessionId={workspace.activeSessionId}
        models={workspace.models}
        selectedModelKey={workspace.selectedModelKey}
        userId={userId}
        disabled={workspace.isStreaming || workspace.isCreating}
        onClose={() => setSidebarOpen(false)}
        onNewChat={() => { workspace.startNewChat(); setDraft('') }}
        onSelectSession={(sessionId) => { workspace.selectSession(sessionId); setDraft('') }}
        onModelChange={workspace.setSelectedModelKey}
      />

      <section className="workspace-main" aria-label="聊天工作区">
        <AppHeader
          title={title}
          modelLabel={modelLabel}
          status={status}
          theme={theme}
          onOpenSidebar={() => setSidebarOpen(true)}
          onToggleTheme={() => setTheme((current) => current === 'light' ? 'dark' : 'light')}
          onOpenSettings={() => setSettingsOpen(true)}
        />

        {workspace.error && (
          <div className="error-banner" role="alert" data-component="Error Banner" data-od-id="error-banner">
            <AlertCircle size={17} />
            <span>{workspace.error}</span>
            <Button onClick={() => void workspace.bootstrap()}><RefreshCw size={14} />重试</Button>
            <Button className="icon-button is-quiet" onClick={() => workspace.setError(null)} aria-label="关闭错误提示"><X size={15} /></Button>
          </div>
        )}

        <section className="conversation-panel" aria-label="当前对话">
          {workspace.isBootstrapping ? (
            <div className="loading-state" aria-label="正在加载工作台">
              <div className="skeleton-line is-short" /><div className="skeleton-line" /><div className="skeleton-line is-medium" />
            </div>
          ) : workspace.messages.length === 0 ? (
            <EmptyState
              modelLabel={modelLabel}
              historySession={Boolean(workspace.activeSession)}
              loading={workspace.isLoadingHistory}
              onSelectPrompt={setDraft}
            />
          ) : (
            <MessageList messages={workspace.messages} onRetry={(prompt) => void workspace.sendMessage(prompt)} />
          )}
        </section>

        <Composer
          value={draft}
          conversationKey={workspace.activeSessionId || 'new-session'}
          placeholder={workspace.activeSession ? '继续此会话，补充新的现象、日志或验证结果…' : undefined}
          isStreaming={workspace.isStreaming}
          isCreating={workspace.isCreating}
          hasModel={Boolean(workspace.activeSession?.modelKey || workspace.selectedModelKey)}
          onChange={setDraft}
          onSend={(message) => void workspace.sendMessage(message)}
          onStop={workspace.stopStreaming}
        />
      </section>

      <UserSettings key={`${userId}-${settingsOpen}`} open={settingsOpen} userId={userId} onClose={() => setSettingsOpen(false)} onSave={setUserId} />
    </div>
  )
}

export default App
