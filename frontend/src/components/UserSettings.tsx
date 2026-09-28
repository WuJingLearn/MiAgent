import { useState } from 'react'
import { KeyRound, X } from 'lucide-react'
import { Button } from '@/components/ui/button'

interface UserSettingsProps {
  open: boolean
  userId: string
  onClose: () => void
  onSave: (userId: string) => void
}

const USER_ID_PATTERN = /^[A-Za-z0-9._:@-]{1,128}$/

export function UserSettings({ open, userId, onClose, onSave }: UserSettingsProps) {
  const [draft, setDraft] = useState(userId)
  const valid = USER_ID_PATTERN.test(draft)

  if (!open) return null

  return (
    <div className="modal-layer" role="presentation" onMouseDown={onClose}>
      <section className="settings-dialog" role="dialog" aria-modal="true" aria-labelledby="settings-title" onMouseDown={(event) => event.stopPropagation()} data-component="User Settings" data-od-id="user-settings">
        <div className="dialog-header">
          <div>
            <p className="eyebrow">REQUEST IDENTITY</p>
            <h2 id="settings-title">用户请求身份</h2>
          </div>
          <Button className="icon-button" onClick={onClose} aria-label="关闭设置"><X size={18} /></Button>
        </div>
        <div className="settings-callout">
          <KeyRound size={18} />
          <p>该值会作为 <code>X-User-Id</code> 请求头发送。当前仅保存在页面内存中，刷新后会恢复默认值。</p>
        </div>
        <label className="field-label" htmlFor="user-id-input">X-User-Id</label>
        <input
          id="user-id-input"
          className={`text-input ${valid ? '' : 'is-invalid'}`}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          maxLength={128}
          autoFocus
          aria-invalid={!valid}
          aria-describedby="user-id-help"
        />
        <p id="user-id-help" className={`field-help ${valid ? '' : 'is-error'}`}>
          允许字母、数字以及 . _ : @ -，长度 1–128。
        </p>
        <div className="dialog-actions">
          <Button className="button button-default" onClick={onClose}>取消</Button>
          <Button className="button button-primary" disabled={!valid} onClick={() => { onSave(draft); onClose() }}>应用身份</Button>
        </div>
      </section>
    </div>
  )
}
