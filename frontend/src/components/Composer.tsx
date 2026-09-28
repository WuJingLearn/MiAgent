import { useEffect, useRef } from 'react'
import { CornerDownLeft, Paperclip, Send, Square } from 'lucide-react'
import { Button } from '@/components/ui/button'

interface ComposerProps {
  value: string
  conversationKey: string
  placeholder?: string
  isStreaming: boolean
  isCreating: boolean
  hasModel: boolean
  onChange: (value: string) => void
  onSend: (value: string) => void
  onStop: () => void
}

export function Composer({ value, conversationKey, placeholder, isStreaming, isCreating, hasModel, onChange, onSend, onStop }: ComposerProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (hasModel && !isCreating) textareaRef.current?.focus()
  }, [conversationKey, hasModel, isCreating])

  const submit = () => {
    if (!value.trim() || isStreaming || isCreating || !hasModel) return
    const message = value
    onChange('')
    onSend(message)
  }

  return (
    <div className="composer-shell" data-component="Message Composer" data-od-id="message-composer">
      <div className={`composer ${isStreaming ? 'is-streaming' : ''}`}>
        <textarea
          ref={textareaRef}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              submit()
            }
          }}
          placeholder={hasModel ? placeholder || '描述异常现象、影响范围或需要验证的假设…' : '模型加载完成后即可发送消息'}
          aria-label="发送给 SRE Agent 的消息"
          rows={1}
          maxLength={20000}
          disabled={!hasModel || isCreating}
        />
        <div className="composer-footer">
          <div className="composer-tools">
            <Button className="icon-button is-quiet" type="button" aria-label="附件功能暂未开放" disabled><Paperclip size={16} /></Button>
            <span><CornerDownLeft size={13} /> Enter 发送 · Shift + Enter 换行</span>
          </div>
          {isStreaming ? (
            <Button className="stop-button" type="button" onClick={onStop}><Square size={13} fill="currentColor" />停止</Button>
          ) : (
            <Button className="send-button" type="button" onClick={submit} disabled={!value.trim() || isCreating || !hasModel}>
              {isCreating ? '创建中' : '发送'}<Send size={15} />
            </Button>
          )}
        </div>
      </div>
      <p className="composer-disclaimer">SRE Agent 可能产生不准确内容。执行生产操作前请验证命令、范围和回滚方案。</p>
    </div>
  )
}
