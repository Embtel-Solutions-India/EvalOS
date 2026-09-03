import { LogOut, Menu, UserRound } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Avatar, AvatarFallback } from '@shared/components/ui/avatar'
import { Button } from '@shared/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@shared/components/ui/dropdown-menu'
import { useExpertAuth } from '@/hooks/useExpertAuth'
import { getInitials } from '@shared/utils/formatters'

interface ExpertHeaderProps {
  title: string
  onMenuClick: () => void
}

export function ExpertHeader({ title, onMenuClick }: ExpertHeaderProps) {
  const { expert, logout } = useExpertAuth()
  const navigate = useNavigate()

  async function handleLogout() {
    await logout()
    navigate('/expert/login', { replace: true })
  }

  return (
    <header className="sticky top-0 z-30 flex h-16 items-center justify-between border-b-2 border-b-destructive bg-background/95 px-4 backdrop-blur supports-[backdrop-filter]:bg-background/80 sm:px-6">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" className="lg:hidden" onClick={onMenuClick} aria-label="Open menu">
          <Menu className="h-5 w-5" />
        </Button>
        <h1 className="text-base font-semibold text-foreground sm:text-lg">{title}</h1>
      </div>
      <div className="flex items-center gap-1 sm:gap-2">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              className="flex items-center gap-2 rounded-full pl-1 pr-1 transition-colors hover:bg-accent sm:pr-2.5"
            >
              <Avatar className="h-8 w-8">
                <AvatarFallback>{expert ? getInitials(expert.firstName, expert.lastName) : 'E'}</AvatarFallback>
              </Avatar>
              <span className="hidden text-sm font-medium text-foreground sm:inline">
                {expert?.firstName} {expert?.lastName}
              </span>
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            <DropdownMenuLabel>{expert?.email}</DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => navigate('/expert/profile')}>
              <UserRound className="h-4 w-4" />
              Profile
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => void handleLogout()}>
              <LogOut className="h-4 w-4" />
              Logout
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </header>
  )
}
