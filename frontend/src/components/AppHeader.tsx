import { Menu, Moon, Settings, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'

interface AppHeaderProps {
  title: string
  modelLabel: string
  status: string
  theme: 'light' | 'dark'
  onOpenSidebar: () => void
  onToggleTheme: () => void
  onOpenSettings: () => void
}

export function AppHeader({
  title,
  modelLabel,
  status,
  theme,
  onOpenSidebar,
  onToggleTheme,
  onOpenSettings,
}: AppHeaderProps) {
  return (
    <header className="app-header" data-component="Workspace Header" data-od-id="workspace-header">
      <div className="header-title-group">
        <Button className="icon-button mobile-menu" onClick={onOpenSidebar} aria-label="打开会话导航">
          <Menu size={18} />
        </Button>
        <div>
          <div className="breadcrumb"><span>SRE Agent</span><span>/</span><span>conversations</span></div>
          <h1>{title}</h1>
        </div>
      </div>
      <div className="header-actions">
        <div className="runtime-status" title="当前会话运行状态">
          <span className={`status-dot ${status === '生成中' ? 'is-pulsing' : ''}`} aria-hidden="true" />
          <span>{status}</span>
          <code>{modelLabel || 'model unavailable'}</code>
        </div>
        <Button className="icon-button" onClick={onToggleTheme} aria-label={theme === 'light' ? '切换到深色外观' : '切换到浅色外观'}>
          {theme === 'light' ? <Moon size={17} /> : <Sun size={17} />}
        </Button>
        <Button className="icon-button" onClick={onOpenSettings} aria-label="打开用户设置">
          <Settings size={17} />
        </Button>
      </div>
    </header>
  )
}
