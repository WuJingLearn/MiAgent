import { ArrowRight, GitBranch, History, LoaderCircle, SearchCode, ShieldCheck, Sparkles } from 'lucide-react'
import { Button } from '@/components/ui/button'

interface EmptyStateProps {
  modelLabel: string
  historySession?: boolean
  loading?: boolean
  onSelectPrompt: (prompt: string) => void
}

const suggestions = [
  { icon: SearchCode, label: '定位异常根因', prompt: '请帮我分析最近一次服务异常可能的根因，并给出排查顺序。' },
  { icon: ShieldCheck, label: '评估变更风险', prompt: '请为一次生产发布生成变更前检查清单和回滚判据。' },
  { icon: GitBranch, label: '梳理调用链路', prompt: '请根据现象帮我梳理可能涉及的服务调用链路。' },
]

export function EmptyState({ modelLabel, historySession = false, loading = false, onSelectPrompt }: EmptyStateProps) {
  if (loading) {
    return (
      <section className="empty-state history-empty-state" data-component="History Loading State" data-od-id="history-loading-state" aria-live="polite">
        <div className="empty-icon is-loading" aria-hidden="true"><LoaderCircle size={24} strokeWidth={1.6} /></div>
        <p className="eyebrow">SESSION HISTORY</p>
        <h2>正在载入历史对话</h2>
        <p className="empty-description">正在从服务端恢复这段会话的消息和工具调用记录。</p>
      </section>
    )
  }

  return (
    <section className="empty-state" data-component="Conversation Empty State" data-od-id="conversation-empty-state">
      <div className="empty-icon" aria-hidden="true">
        {historySession ? <History size={24} strokeWidth={1.6} /> : <Sparkles size={24} strokeWidth={1.6} />}
      </div>
      <p className="eyebrow">{historySession ? 'SESSION READY' : 'OPERATIONS COPILOT'}</p>
      <h2>{historySession ? '继续这段对话' : '从一个可验证的问题开始'}</h2>
      <p className="empty-description">
        {historySession
          ? <>暂时没有可展示的历史消息，但会话仍然可用。使用 <code>{modelLabel || '当前模型'}</code> 继续提问即可。</>
          : <>当前将使用 <code>{modelLabel || '可用模型'}</code>。描述现象、影响范围和时间窗口，SRE Agent 会在回答过程中同步展示工具调用状态。</>}
      </p>
      <div className="suggestion-grid">
        {suggestions.map(({ icon: Icon, label, prompt }) => (
          <Button key={label} className="suggestion-card" onClick={() => onSelectPrompt(prompt)}>
            <Icon size={18} strokeWidth={1.7} />
            <span><strong>{historySession ? `继续：${label}` : label}</strong><small>{prompt}</small></span>
            <ArrowRight size={15} className="suggestion-arrow" />
          </Button>
        ))}
      </div>
      <div className="empty-note">
        <span className="status-dot" aria-hidden="true" />
        {historySession ? '输入框已就绪，可以在当前会话中继续交流。' : '新会话将在发送第一条消息时创建。'}
      </div>
    </section>
  )
}
