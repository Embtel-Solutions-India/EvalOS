import * as DialogPrimitive from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import { ExpertSidebar } from '@/components/layout/ExpertSidebar'

interface ExpertMobileNavDrawerProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function ExpertMobileNavDrawer({ open, onOpenChange }: ExpertMobileNavDrawerProps) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-40 bg-black/50 data-[state=open]:animate-fade-in lg:hidden" />
        <DialogPrimitive.Content
          className="fixed inset-y-0 left-0 z-50 w-72 max-w-[85vw] data-[state=open]:animate-slide-in-left lg:hidden"
          aria-describedby={undefined}
        >
          <DialogPrimitive.Title className="sr-only">Navigation menu</DialogPrimitive.Title>
          <div className="relative h-full">
            <DialogPrimitive.Close className="absolute right-3 top-3 z-10 rounded-md p-1.5 text-sidebar-foreground hover:bg-sidebar-accent">
              <X className="h-5 w-5" />
              <span className="sr-only">Close menu</span>
            </DialogPrimitive.Close>
            <ExpertSidebar onNavigate={() => onOpenChange(false)} />
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
